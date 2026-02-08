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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedInputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.ceil

class TransferClient(
    private val context: Context,
    private val transport: SecureTransport,
    private val identity: DeviceIdentity
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()
    private val chunkSize = 1_048_576

    suspend fun sendFile(
        device: DeviceInfo,
        file: FileDescriptor,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit
    ) = withContext(Dispatchers.IO) {
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
        val initResponse = postInit(device, session, initRequest)
        if (!initResponse.accepted) {
            error("Transfer rejected by receiver")
        }
        val missing = initResponse.missingChunks.toSet()
        val totalChunks = ceil(fileSize / chunkSize.toDouble()).toInt().coerceAtLeast(1)
        val resolver = context.contentResolver
        resolver.openInputStream(file.uri)?.use { input ->
            val buffered = BufferedInputStream(input, chunkSize)
            var index = 0
            var totalSent = 0L
            val buffer = ByteArray(chunkSize)
            while (true) {
                val read = buffered.read(buffer)
                if (read <= 0) break
                val chunk = buffer.copyOf(read)
                val shouldSend = missing.isEmpty() || missing.contains(index)
                if (shouldSend) {
                    val nonce = CryptoUtils.randomBytes(12)
                    val aad = "${initResponse.transferId}:$index".toByteArray()
                    val cipher = E2eCrypto.encrypt(
                        key = session.key,
                        nonce = nonce,
                        plaintext = chunk,
                        aad = aad
                    )
                    postChunk(
                        device = device,
                        session = session,
                        transferId = initResponse.transferId,
                        index = index,
                        totalChunks = totalChunks,
                        nonce = nonce,
                        aad = aad,
                        cipherText = cipher
                    )
                }
                totalSent += read
                onProgress(totalSent, fileSize)
                index++
            }
        } ?: error("Unable to open file")
    }

    suspend fun sendMessage(
        device: DeviceInfo,
        text: String
    ) = withContext(Dispatchers.IO) {
        val session = transport.openSession(device, identity)
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
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            error("Message failed with ${response.code}")
        }
        val responseBody = response.body?.string() ?: error("Empty message response")
        val parsed = json.decodeFromString(TextMessageResponse.serializer(), responseBody)
        if (!parsed.received) {
            error("Message rejected")
        }
    }

    private fun postInit(
        device: DeviceInfo,
        session: E2eSession,
        initRequest: TransferInitRequest
    ): TransferInitResponse {
        Log.d("PulseSend", "Sending transfer init to ${session.host}:${session.port}")
        val body = json.encodeToString(TransferInitRequest.serializer(), initRequest)
        val request = Request.Builder()
            .url(buildUrl(session, "/transfer/init"))
            .post(body.toRequestBody(mediaType))
            .header("X-Session-Token", session.token ?: "")
            .build()
        val fingerprint = requireFingerprint(device, session)
        val client = OkHttpFactory.pinnedClient(session.host, fingerprint)
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            error("Init failed with ${response.code}")
        }
        val responseBody = response.body?.string() ?: error("Empty init response")
        return json.decodeFromString(TransferInitResponse.serializer(), responseBody)
    }

    private fun postChunk(
        device: DeviceInfo,
        session: E2eSession,
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
        val fingerprint = requireFingerprint(device, session)
        val client = OkHttpFactory.pinnedClient(session.host, fingerprint)
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            error("Chunk failed with ${response.code}")
        }
        val responseBody = response.body?.string() ?: error("Empty chunk response")
        val parsed = json.decodeFromString(TransferChunkResponse.serializer(), responseBody)
        if (!parsed.received) {
            error("Chunk rejected")
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
