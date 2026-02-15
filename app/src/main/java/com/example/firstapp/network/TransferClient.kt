package com.example.firstapp.network

import android.content.Context
import android.util.Log
import com.example.firstapp.core.crypto.CryptoUtils
import com.example.firstapp.core.crypto.E2eCrypto
import com.example.firstapp.core.device.DeviceIdentity
import com.example.firstapp.model.DeviceInfo
import com.example.firstapp.model.FileDescriptor
import com.example.firstapp.protocol.TransferChunkRequest
import com.example.firstapp.protocol.TransferChunkResponse
import com.example.firstapp.protocol.TransferInitRequest
import com.example.firstapp.protocol.TransferInitResponse
import com.example.firstapp.protocol.TextMessageRequest
import com.example.firstapp.protocol.TextMessageResponse
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

class TransferClient(
    private val context: Context,
    private val transport: SecureTransport,
    private val identity: DeviceIdentity
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()
    private val chunkSize = 2_097_152
    private val maxParallelChunks = 8

    suspend fun sendFile(
        device: DeviceInfo,
        file: FileDescriptor,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
        suspend fun sendFileOnce() {
            val session = transport.openSession(device, identity)
            val hashResult = computeSha256(file)
            val fileSize = if (file.size > 0) file.size else hashResult.size
            val initRequest = TransferInitRequest(
                fileName = file.name,
                fileSize = fileSize,
                mimeType = file.mimeType,
                sha256 = hashResult.hash,
                chunkSize = chunkSize
            )
            val fingerprint = requireFingerprint(device, session)
            val client = OkHttpFactory.pinnedClient(session.host, fingerprint)
            val initResponse = postInit(session, client, initRequest)
            if (!initResponse.accepted) {
                error("Transfer rejected by receiver")
            }
            val missing = initResponse.missingChunks.toSet()
            val totalChunks = ceil(fileSize / chunkSize.toDouble()).toInt().coerceAtLeast(1)
            val resolver = context.contentResolver
            val parallelism = min(maxParallelChunks, max(2, Runtime.getRuntime().availableProcessors()))
            val sentBytes = AtomicLong(0L)
            val progressLock = Any()
            fun reportProgress(current: Long) {
                synchronized(progressLock) {
                    onProgress(current.coerceAtMost(fileSize), fileSize)
                }
            }
            coroutineScope {
                data class ChunkJob(val index: Int, val bytes: ByteArray, val size: Int)
                val jobs = Channel<ChunkJob>(capacity = parallelism * 2)
                val workerHandler = CoroutineExceptionHandler { _, throwable ->
                    val cancellation = CancellationException("Chunk worker failed")
                    cancellation.initCause(throwable)
                    jobs.cancel(cancellation)
                }
                val workers = List(parallelism) {
                    launch(Dispatchers.IO + workerHandler) {
                        for (job in jobs) {
                            val nonce = CryptoUtils.randomBytes(12)
                            val aad = "${initResponse.transferId}:${job.index}".toByteArray()
                            val cipher = E2eCrypto.encrypt(
                                key = session.key,
                                nonce = nonce,
                                plaintext = job.bytes,
                                aad = aad
                            )
                            postChunk(
                                session = session,
                                client = client,
                                transferId = initResponse.transferId,
                                index = job.index,
                                totalChunks = totalChunks,
                                nonce = nonce,
                                aad = aad,
                                cipherText = cipher
                            )
                            val current = sentBytes.addAndGet(job.size.toLong())
                            reportProgress(current)
                        }
                    }
                }
                val producer = async(Dispatchers.IO) {
                    resolver.openInputStream(file.uri)?.use { input ->
                        val buffered = BufferedInputStream(input, chunkSize)
                        var index = 0
                        while (true) {
                            val chunkBuffer = ByteArray(chunkSize)
                            val read = buffered.read(chunkBuffer)
                            if (read <= 0) break
                            val shouldSend = missing.isEmpty() || missing.contains(index)
                            if (shouldSend) {
                                val payload = if (read == chunkBuffer.size) chunkBuffer else chunkBuffer.copyOf(read)
                                jobs.send(ChunkJob(index = index, bytes = payload, size = read))
                            } else {
                                val current = sentBytes.addAndGet(read.toLong())
                                reportProgress(current)
                            }
                            index++
                        }
                    } ?: error("Unable to open file")
                    jobs.close()
                }
                try {
                    producer.await()
                    workers.joinAll()
                } catch (t: Throwable) {
                    val cancellation = CancellationException("Chunk upload cancelled")
                    cancellation.initCause(t)
                    jobs.cancel(cancellation)
                    throw t
                }
            }
        }

        try {
            sendFileOnce()
        } catch (e: Exception) {
            if (isUnauthorized(e)) {
                Log.w("PulseSend", "File transfer returned 401, invalidate cached session and retry once")
                transport.invalidateSession(device.id)
                sendFileOnce()
                return@withContext
            }
            throw e
        }
    }

        suspend fun sendMessage(
        device: DeviceInfo,
        text: String
    ) = withContext(Dispatchers.IO) {
        fun sendOnce(session: E2eSession) {
            val messageId = UUID.randomUUID().toString()
            val nonce = CryptoUtils.randomBytes(12)
            val aad = "message:$messageId".toByteArray()
            val cipher = E2eCrypto.encrypt(
                key = session.key,
                nonce = nonce,
                plaintext = text.toByteArray(Charsets.UTF_8),
                aad = aad
            )
            val body = TextMessageRequest(
                messageId = messageId,
                nonce = CryptoUtils.toBase64(nonce),
                cipherText = CryptoUtils.toBase64(cipher),
                aad = CryptoUtils.toBase64(aad)
            )
            Log.d("PulseSend", "Sending message to ${session.host}:${session.port}")
            val request = Request.Builder()
                .url(buildUrl(session, "/message"))
                .post(json.encodeToString(TextMessageRequest.serializer(), body).toRequestBody(mediaType))
                .header("X-Session-Token", session.token ?: "")
                .build()
            val fingerprint = requireFingerprint(device, session)
            val client = OkHttpFactory.pinnedClient(session.host, fingerprint)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Message failed with ${response.code}")
                }
                val responseBody = response.body?.string() ?: throw Exception("Empty message response")
                val parsed = json.decodeFromString(TextMessageResponse.serializer(), responseBody)
                if (!parsed.received) {
                    throw Exception("Message rejected")
                }
            }
        }

        try {
            val firstSession = transport.openSession(device, identity)
            sendOnce(firstSession)
        } catch (e: Exception) {
            if (isUnauthorized(e)) {
                Log.w("PulseSend", "Message returned 401, invalidate cached session and retry once")
                transport.invalidateSession(device.id)
                val retrySession = transport.openSession(device, identity)
                sendOnce(retrySession)
                return@withContext
            }
            Log.e("PulseSend", "Error sending message", e)
            throw e
        }
    }

    private fun isUnauthorized(error: Throwable): Boolean {
        val message = error.message?.lowercase() ?: return false
        return message.contains("401") ||
            message.contains("unauthorized") ||
            message.contains("未授权") ||
            message.contains("未配对")
    }

    private fun postInit(
        session: E2eSession,
        client: OkHttpClient,
        initRequest: TransferInitRequest
    ): TransferInitResponse {
        Log.d("PulseSend", "Sending transfer init to ${session.host}:${session.port}")
        val body = json.encodeToString(TransferInitRequest.serializer(), initRequest)
        val request = Request.Builder()
            .url(buildUrl(session, "/transfer/init"))
            .post(body.toRequestBody(mediaType))
            .header("X-Session-Token", session.token ?: "")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Init failed with ${response.code}")
            }
            val responseBody = response.body?.string() ?: error("Empty init response")
            return json.decodeFromString(TransferInitResponse.serializer(), responseBody)
        }
    }

    private fun postChunk(
        session: E2eSession,
        client: OkHttpClient,
        transferId: String,
        index: Int,
        totalChunks: Int,
        nonce: ByteArray,
        aad: ByteArray,
        cipherText: ByteArray
    ) {
        Log.d("PulseSend", "Sending chunk $index/$totalChunks to ${session.host}:${session.port}")
        val body = TransferChunkRequest(
            transferId = transferId,
            index = index,
            totalChunks = totalChunks,
            nonce = CryptoUtils.toBase64(nonce),
            cipherText = CryptoUtils.toBase64(cipherText),
            aad = CryptoUtils.toBase64(aad)
        )
        val request = Request.Builder()
            .url(buildUrl(session, "/transfer/chunk"))
            .post(json.encodeToString(TransferChunkRequest.serializer(), body).toRequestBody(mediaType))
            .header("X-Session-Token", session.token ?: "")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error("Chunk failed with ${response.code}")
            }
            val responseBody = response.body?.string() ?: error("Empty chunk response")
            val parsed = json.decodeFromString(TransferChunkResponse.serializer(), responseBody)
            if (!parsed.received) {
                error("Chunk rejected")
            }
        }
    }

    private data class HashResult(val hash: String, val size: Long)

    private fun computeSha256(file: FileDescriptor): HashResult {
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        val resolver = context.contentResolver
        resolver.openInputStream(file.uri)?.use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read > 0) {
                digest.update(buffer, 0, read)
                total += read
                read = input.read(buffer)
            }
        } ?: error("Unable to open file")
        return HashResult(CryptoUtils.toBase64(digest.digest()), total)
    }

    private fun buildUrl(session: E2eSession, path: String): String =
        "https://${session.host}:${session.port}$path"

    private fun requireFingerprint(device: DeviceInfo, session: E2eSession): String =
        session.fingerprint?.takeUnless { it.isBlank() }
            ?: device.fingerprint?.takeUnless { it.isBlank() }
            ?: error("Missing fingerprint for ${device.id}")
}

