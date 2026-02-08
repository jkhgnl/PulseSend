package com.example.firstapp.network

import android.content.Context
import android.os.Environment
import com.example.firstapp.core.crypto.CryptoUtils
import com.example.firstapp.core.crypto.E2eCrypto
import com.example.firstapp.core.device.DeviceIdentity
import com.example.firstapp.data.TrustedDeviceRecord
import com.example.firstapp.data.TrustedDeviceStore
import com.example.firstapp.model.MessageDirection
import com.example.firstapp.model.MessageItem
import com.example.firstapp.model.ServerSnapshot
import com.example.firstapp.model.TransferDirection
import com.example.firstapp.model.TransferItem
import com.example.firstapp.model.TransferStatus
import com.example.firstapp.protocol.PairRequest
import com.example.firstapp.protocol.PairResponse
import com.example.firstapp.protocol.SessionRequest
import com.example.firstapp.protocol.SessionResponse
import com.example.firstapp.protocol.TextMessageRequest
import com.example.firstapp.protocol.TextMessageResponse
import com.example.firstapp.protocol.TransferChunkRequest
import com.example.firstapp.protocol.TransferChunkResponse
import com.example.firstapp.protocol.TransferInitRequest
import com.example.firstapp.protocol.TransferInitResponse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.IOException
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlinx.coroutines.runBlocking

class ServerHost(
    private val context: Context,
    private val identity: DeviceIdentity,
    private val store: TrustedDeviceStore,
    private val port: Int = 48084
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    private val server = MockWebServer()
    private val tokenIndex = ConcurrentHashMap<String, TrustedDeviceRecord>()
    private val sessions = ConcurrentHashMap<String, SessionContext>()
    private val transfersById = ConcurrentHashMap<String, TransferState>()
    private val transfersByHash = ConcurrentHashMap<String, TransferState>()
    private var discoveryResponder: DiscoveryResponder? = null
    private var certificates: HandshakeCertificates? = null
    private var fingerprint: String = ""
    private var pairCode: String = "------"
    private val infoBytes = "pulse-session".toByteArray()
    private val certFile: File by lazy {
        File(context.filesDir, "pulse_server.pem")
    }

    private val _snapshot = MutableStateFlow(ServerSnapshot(port = port))
    val snapshot: StateFlow<ServerSnapshot> = _snapshot

    private val _transferUpdates = MutableSharedFlow<TransferItem>(extraBufferCapacity = 64)
    val transferUpdates: SharedFlow<TransferItem> = _transferUpdates

    private val _messageEvents = MutableSharedFlow<MessageItem>(extraBufferCapacity = 64)
    val messageEvents: SharedFlow<MessageItem> = _messageEvents

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events

    suspend fun start() {
        if (_snapshot.value.statusText == "运行中") return

        tokenIndex.clear()
        val trusted = store.trustedDevices.first()
        trusted.values.forEach { record ->
            val token = record.incomingToken
            if (!token.isNullOrBlank()) {
                tokenIndex[token] = record
            }
        }

        val heldCertificate = loadOrCreateCertificate()
        certificates = HandshakeCertificates.Builder()
            .heldCertificate(heldCertificate)
            .build()
        fingerprint = OkHttpFactory.certificatePin(heldCertificate.certificate)
        pairCode = generatePairCode()

        server.useHttps(requireNotNull(certificates).sslSocketFactory(), false)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return try {
                    when (request.path?.substringBefore("?")) {
                        "/pair" -> handlePair(request)
                        "/session" -> handleSession(request)
                        "/transfer/init" -> handleTransferInit(request)
                        "/transfer/chunk" -> handleTransferChunk(request)
                        "/ping" -> jsonResponse("""{"ok":true}""")
                        "/message" -> handleTextMessage(request)
                        else -> MockResponse().setResponseCode(404)
                    }
                } catch (ex: Exception) {
                    MockResponse().setResponseCode(500)
                }
            }
        }

        server.start(InetAddress.getByName("0.0.0.0"), port)
        discoveryResponder = DiscoveryResponder(context, identity, port, fingerprint)
        discoveryResponder?.start()
        updateSnapshot("运行中")
        _events.tryEmit("本机服务已启动")
    }

    fun stop() {
        discoveryResponder?.stop()
        discoveryResponder = null
        runCatching { server.shutdown() }
        updateSnapshot("已停止")
    }

    fun regeneratePairCode() {
        pairCode = generatePairCode()
        updateSnapshot(_snapshot.value.statusText)
    }

    private fun handlePair(request: RecordedRequest): MockResponse {
        val payload = request.body.readUtf8()
        val parsed = json.decodeFromString(PairRequest.serializer(), payload)
        if (parsed.code != pairCode) {
            return MockResponse().setResponseCode(403)
        }
        val keyPair = E2eCrypto.generateKeyPair()
        val peerKey = E2eCrypto.publicKeyFromBytes(CryptoUtils.fromBase64(parsed.publicKey))
        val salt = CryptoUtils.randomBytes(16)
        val shared = E2eCrypto.deriveSharedSecret(keyPair.private, peerKey)
        CryptoUtils.hkdfSha256(shared, salt, infoBytes, 32)

        val token = CryptoUtils.toBase64(CryptoUtils.randomBytes(32))
        val existing = runBlocking { store.get(parsed.deviceId) }
        val record = TrustedDeviceRecord(
            deviceId = parsed.deviceId,
            deviceName = parsed.deviceName,
            fingerprint = existing?.fingerprint ?: "",
            outgoingToken = existing?.outgoingToken,
            incomingToken = token,
            token = null
        )
        runBlocking { store.save(record) }
        tokenIndex[token] = record

        pairCode = generatePairCode()
        updateSnapshot(_snapshot.value.statusText)

        val response = PairResponse(
            deviceId = identity.id,
            deviceName = identity.name,
            fingerprint = fingerprint,
            peerPublicKey = CryptoUtils.toBase64(E2eCrypto.publicKeyBytes(keyPair)),
            salt = CryptoUtils.toBase64(salt),
            token = token
        )
        _events.tryEmit("已与 ${parsed.deviceName} 配对")
        return jsonResponse(json.encodeToString(PairResponse.serializer(), response))
    }

    private fun handleSession(request: RecordedRequest): MockResponse {
        val payload = request.body.readUtf8()
        val parsed = json.decodeFromString(SessionRequest.serializer(), payload)
        val token = parsed.token ?: ""
        if (token.isBlank() || !tokenIndex.containsKey(token)) {
            return MockResponse().setResponseCode(401)
        }

        val keyPair = E2eCrypto.generateKeyPair()
        val peerKey = E2eCrypto.publicKeyFromBytes(CryptoUtils.fromBase64(parsed.publicKey))
        val salt = CryptoUtils.randomBytes(16)
        val shared = E2eCrypto.deriveSharedSecret(keyPair.private, peerKey)
        val sessionKey = CryptoUtils.hkdfSha256(shared, salt, infoBytes, 32)
        sessions[token] = SessionContext(sessionKey, System.currentTimeMillis() + 30 * 60 * 1000)

        val response = SessionResponse(
            peerPublicKey = CryptoUtils.toBase64(E2eCrypto.publicKeyBytes(keyPair)),
            salt = CryptoUtils.toBase64(salt)
        )
        return jsonResponse(json.encodeToString(SessionResponse.serializer(), response))
    }

    private fun handleTransferInit(request: RecordedRequest): MockResponse {
        if (getSession(request) == null) {
            return MockResponse().setResponseCode(401)
        }
        val payload = request.body.readUtf8()
        val parsed = json.decodeFromString(TransferInitRequest.serializer(), payload)
        if (parsed.fileName.isBlank() || parsed.fileSize <= 0 || parsed.chunkSize <= 0) {
            return MockResponse().setResponseCode(400)
        }

        val state = synchronized(transfersByHash) {
            val existing = transfersByHash[parsed.sha256]
            if (existing != null && !existing.completed) {
                existing
            } else {
                val created = TransferState.create(parsed, resolveIncomingFolder())
                transfersById[created.transferId] = created
                transfersByHash[parsed.sha256] = created
                created
            }
        }
        val missing = state.missingChunks()
        publishTransfer(state, TransferStatus.Preparing)

        val response = TransferInitResponse(
            transferId = state.transferId,
            accepted = true,
            missingChunks = missing
        )
        return jsonResponse(json.encodeToString(TransferInitResponse.serializer(), response))
    }

    private fun handleTransferChunk(request: RecordedRequest): MockResponse {
        val session = getSession(request) ?: return MockResponse().setResponseCode(401)
        val payload = request.body.readUtf8()
        val parsed = json.decodeFromString(TransferChunkRequest.serializer(), payload)
        val state = transfersById[parsed.transferId] ?: return MockResponse().setResponseCode(404)
        if (parsed.index < 0 || parsed.index >= state.totalChunks) {
            return MockResponse().setResponseCode(400)
        }

        synchronized(state.lock) {
            if (state.completed || state.received[parsed.index]) {
                return jsonResponse(json.encodeToString(TransferChunkResponse.serializer(), TransferChunkResponse(true)))
            }
            val nonce = CryptoUtils.fromBase64(parsed.nonce)
            val aad = CryptoUtils.fromBase64(parsed.aad)
            val cipher = CryptoUtils.fromBase64(parsed.cipherText)
            val plain = E2eCrypto.decrypt(session.key, nonce, cipher, aad)
            state.stream.seek(parsed.index.toLong() * state.chunkSize)
            state.stream.write(plain)
            state.received[parsed.index] = true
            state.receivedBytes += plain.size
        }

        publishTransfer(state, TransferStatus.Transferring)

        if (state.isComplete()) {
            finalizeTransfer(state)
        }

        return jsonResponse(json.encodeToString(TransferChunkResponse.serializer(), TransferChunkResponse(true)))
    }

    private fun handleTextMessage(request: RecordedRequest): MockResponse {
        val sessionInfo = getSessionWithToken(request) ?: return MockResponse().setResponseCode(401)
        val payload = request.body.readUtf8()
        val parsed = json.decodeFromString(TextMessageRequest.serializer(), payload)
        if (parsed.cipherText.isBlank() || parsed.nonce.isBlank() || parsed.aad.isBlank()) {
            return MockResponse().setResponseCode(400)
        }
        val nonce = CryptoUtils.fromBase64(parsed.nonce)
        val aad = CryptoUtils.fromBase64(parsed.aad)
        val cipher = CryptoUtils.fromBase64(parsed.cipherText)
        val plain = E2eCrypto.decrypt(sessionInfo.second.key, nonce, cipher, aad)
        val text = plain.toString(Charsets.UTF_8)
        val deviceName = tokenIndex[sessionInfo.first]?.deviceName?.ifBlank { "未知设备" } ?: "未知设备"
        val message = MessageItem(
            peerName = deviceName,
            content = text,
            timestamp = System.currentTimeMillis(),
            direction = MessageDirection.Incoming
        )
        _messageEvents.tryEmit(message)
        _events.tryEmit("收到来自 $deviceName 的消息")
        val response = TextMessageResponse(received = true)
        return jsonResponse(json.encodeToString(TextMessageResponse.serializer(), response))
    }

    private fun getSession(request: RecordedRequest): SessionContext? =
        getSessionWithToken(request)?.second

    private fun getSessionWithToken(request: RecordedRequest): Pair<String, SessionContext>? {
        val token = request.getHeader("X-Session-Token") ?: return null
        if (token.isBlank()) return null
        val session = sessions[token] ?: return null
        if (session.expiresAt < System.currentTimeMillis()) {
            sessions.remove(token)
            return null
        }
        return token to session
    }

    private fun finalizeTransfer(state: TransferState) {
        synchronized(state.lock) {
            if (state.completed) return
            state.completed = true
            state.stream.fd.sync()
            state.stream.close()
            if (state.finalFile.exists()) {
                state.finalFile.delete()
            }
            state.partFile.renameTo(state.finalFile)
        }
        publishTransfer(state, TransferStatus.Completed)
        _events.tryEmit("文件已保存：${state.finalFile.name}")
    }

    private fun publishTransfer(state: TransferState, status: TransferStatus) {
        val item = TransferItem(
            id = state.transferId,
            fileName = state.fileName,
            totalBytes = state.totalBytes,
            sentBytes = state.receivedBytes,
            speedBytesPerSec = 0L,
            status = status,
            direction = TransferDirection.Download
        )
        _transferUpdates.tryEmit(item)
    }

    private fun updateSnapshot(status: String) {
        _snapshot.value = ServerSnapshot(
            statusText = status,
            pairCode = pairCode,
            fingerprint = fingerprint,
            port = port
        )
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private fun generatePairCode(): String =
        random.nextInt(1_000_000).toString().padStart(6, '0')

    private fun resolveIncomingFolder(): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val folder = File(base, "PulseSend/Incoming")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        return folder
    }

    private fun loadOrCreateCertificate(): HeldCertificate {
        val existing = readStoredCertificate()
        if (existing != null) {
            return existing
        }
        val created = HeldCertificate.Builder()
            .commonName("PulseSend")
            .build()
        writeStoredCertificate(created)
        return created
    }

    private fun readStoredCertificate(): HeldCertificate? {
        if (!certFile.exists()) return null
        val pem = runCatching { certFile.readText() }.getOrNull() ?: return null
        return runCatching { HeldCertificate.decode(pem) }.getOrNull()
    }

    private fun writeStoredCertificate(certificate: HeldCertificate) {
        try {
            certFile.parentFile?.mkdirs()
            val pem = certificate.certificatePem() + certificate.privateKeyPkcs8Pem()
            certFile.writeText(pem)
        } catch (_: IOException) {
            // ignore persistence errors; fallback to ephemeral cert
        }
    }

    private data class SessionContext(val key: ByteArray, val expiresAt: Long)

    private class TransferState(
        val transferId: String,
        val sha256: String,
        val fileName: String,
        val partFile: File,
        val finalFile: File,
        val chunkSize: Int,
        val totalChunks: Int,
        val totalBytes: Long,
        val stream: RandomAccessFile
    ) {
        val received = BooleanArray(totalChunks)
        var receivedBytes: Long = 0L
        var completed: Boolean = false
        val lock = Any()

        fun missingChunks(): List<Int> {
            val missing = mutableListOf<Int>()
            for (i in received.indices) {
                if (!received[i]) missing.add(i)
            }
            return missing
        }

        fun isComplete(): Boolean = received.all { it }

        companion object {
            fun create(request: TransferInitRequest, folder: File): TransferState {
                val sanitized = sanitizeFileName(request.fileName)
                val base = File(folder, sanitized)
                val finalFile = ensureUniqueFile(base)
                val partFile = File(finalFile.absolutePath + ".part")
                val totalChunks = maxOf(1, ceil(request.fileSize / request.chunkSize.toDouble()).toInt())
                val stream = RandomAccessFile(partFile, "rw")
                return TransferState(
                    transferId = UUID.randomUUID().toString(),
                    sha256 = request.sha256,
                    fileName = finalFile.name,
                    partFile = partFile,
                    finalFile = finalFile,
                    chunkSize = request.chunkSize,
                    totalChunks = totalChunks,
                    totalBytes = request.fileSize,
                    stream = stream
                )
            }

            private fun sanitizeFileName(name: String): String {
                val invalid = "[\\\\/:*?\"<>|]".toRegex()
                val cleaned = name.replace(invalid, "_").trim()
                return if (cleaned.isBlank()) "file.bin" else cleaned
            }

            private fun ensureUniqueFile(base: File): File {
                if (!base.exists()) return base
                val name = base.nameWithoutExtension
                val ext = base.extension
                val parent = base.parentFile ?: return base
                for (i in 1..999) {
                    val candidate = if (ext.isBlank()) {
                        File(parent, "$name ($i)")
                    } else {
                        File(parent, "$name ($i).$ext")
                    }
                    if (!candidate.exists()) return candidate
                }
                val fallbackName = "${name}-${UUID.randomUUID()}.${ext.ifBlank { "bin" }}"
                return File(parent, fallbackName)
            }
        }
    }
}
