package com.example.firstapp.network

import android.util.Log
import com.example.firstapp.core.crypto.CryptoUtils
import com.example.firstapp.core.crypto.E2eCrypto
import com.example.firstapp.core.device.DeviceIdentity
import com.example.firstapp.data.TrustedDeviceRecord
import com.example.firstapp.data.TrustedDeviceStore
import com.example.firstapp.model.DeviceInfo
import com.example.firstapp.protocol.PairRequest
import com.example.firstapp.protocol.PairResponse
import com.example.firstapp.protocol.SessionRequest
import com.example.firstapp.protocol.SessionResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

private const val SESSION_TTL_SECONDS = 600L

class SecureTransport(
    private val store: TrustedDeviceStore
) {
    private val defaultPort = 48084
    private val json = Json { ignoreUnknownKeys = true }
    private val mediaType = "application/json".toMediaType()
    private val sessionCache = ConcurrentHashMap<String, SessionCache>()

    suspend fun pair(device: DeviceInfo, identity: DeviceIdentity, code: String): PairingResult =
        withContext(Dispatchers.IO) {
            val keyPair = E2eCrypto.generateKeyPair()
            val connection = resolveConnection(device, null)
            Log.d("PulseSend", "Pair request to ${connection.host}:${connection.port}")
            val requestBody = PairRequest(
                deviceId = identity.id,
                deviceName = identity.name,
                publicKey = CryptoUtils.toBase64(E2eCrypto.publicKeyBytes(keyPair)),
                code = code
            )
            val request = Request.Builder()
                .url(buildUrl(connection, "/pair"))
                .post(json.encodeToString(PairRequest.serializer(), requestBody).toRequestBody(mediaType))
                .build()

            val response = OkHttpFactory.pairingClient().newCall(request).execute()
            if (!response.isSuccessful) {
                error("配对失败：${response.code}")
            }
            val responseBody = response.body?.string()
                ?: error("Empty pairing response")
            val parsed = json.decodeFromString(PairResponse.serializer(), responseBody)
            val cert = response.handshake
                ?.peerCertificates
                ?.firstOrNull() as? X509Certificate
            val pin = cert?.let { OkHttpFactory.certificatePin(it) }
                ?: parsed.fingerprint.ifBlank { error("Missing peer fingerprint") }
            val existing = store.get(parsed.deviceId)
            store.save(
                TrustedDeviceRecord(
                    deviceId = parsed.deviceId,
                    deviceName = parsed.deviceName,
                    fingerprint = pin,
                    outgoingToken = parsed.token,
                    incomingToken = existing?.incomingToken,
                    token = null,
                    lastAddress = connection.host,
                    lastPort = connection.port
                )
            )
            PairingResult(fingerprint = pin, token = parsed.token ?: "")
        }

    suspend fun openSession(device: DeviceInfo, identity: DeviceIdentity): E2eSession =
        withContext(Dispatchers.IO) {
            val trusted = store.get(device.id) ?: error("设备尚未配对。")
            val outgoingToken = trusted.outgoingToken
            if (outgoingToken.isNullOrBlank()) {
                error("设备尚未配对。")
            }
            val cache = sessionCache.computeIfAbsent(device.id) { SessionCache() }
            cache.acquire()
            try {
                if (cache.isValid()) {
                    return@withContext cache.session!!
                }
                val connection = resolveConnection(device, trusted)
                Log.d("PulseSend", "Opening session to ${connection.host}:${connection.port}")
                val keyPair = E2eCrypto.generateKeyPair()
                val requestBody = SessionRequest(
                    deviceId = identity.id,
                    publicKey = CryptoUtils.toBase64(E2eCrypto.publicKeyBytes(keyPair)),
                    token = outgoingToken
                )
                val request = Request.Builder()
                    .url(buildUrl(connection, "/session"))
                    .post(json.encodeToString(SessionRequest.serializer(), requestBody).toRequestBody(mediaType))
                    .build()

                val client = OkHttpFactory.pinnedClient(connection.host, trusted.fingerprint ?: "")
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    error("会话失败：${response.code}")
                }
                val cert = response.handshake
                    ?.peerCertificates
                    ?.firstOrNull() as? X509Certificate
                val observedPin = cert?.let { OkHttpFactory.certificatePin(it) }
                val updatedPin = observedPin ?: connection.fingerprint ?: trusted.fingerprint
                if (!updatedPin.isNullOrBlank() && updatedPin != trusted.fingerprint) {
                    Log.d("PulseSend", "Fingerprint rotated: ${trusted.fingerprint} -> $updatedPin")
                    store.save(trusted.copy(fingerprint = updatedPin))
                }
                val body = response.body?.string() ?: error("Empty session response")
                val parsed = json.decodeFromString(SessionResponse.serializer(), body)
                val peerKey = E2eCrypto.publicKeyFromBytes(CryptoUtils.fromBase64(parsed.peerPublicKey))
                val sharedSecret = E2eCrypto.deriveSharedSecret(keyPair.private, peerKey)
                val salt = CryptoUtils.fromBase64(parsed.salt)
                val sessionKey = CryptoUtils.hkdfSha256(
                    ikm = sharedSecret,
                    salt = salt,
                    info = "pulse-session".toByteArray(),
                    size = 32
                )
                val session = E2eSession(
                    key = sessionKey,
                    token = outgoingToken,
                    host = connection.host,
                    port = connection.port,
                    fingerprint = updatedPin
                )
                cache.update(session)
                session
            } finally {
                cache.release()
            }
        }

    suspend fun ping(device: DeviceInfo): Boolean =
        withContext(Dispatchers.IO) {
            val trusted = store.get(device.id)
            val connection = resolveConnection(device, trusted)
            val request = Request.Builder()
                .url(buildUrl(connection, "/ping"))
                .get()
                .build()
            val pin = connection.fingerprint ?: trusted?.fingerprint ?: ""
            val client = OkHttpFactory.pinnedClient(connection.host, pin)
            val response = client.newCall(request).execute()
            response.isSuccessful
        }

    private fun resolveConnection(device: DeviceInfo, record: TrustedDeviceRecord?): ConnectionInfo {
        val host = device.address.takeUnless { it.isBlank() }
            ?: record?.lastAddress?.takeUnless { it.isNullOrBlank() }
            ?: throw IllegalStateException("Missing host for device ${device.id}")
        val port = if (device.tlsPort > 0) {
            device.tlsPort
        } else {
            record?.lastPort?.takeIf { it > 0 } ?: defaultPort
        }
        val fingerprint = device.fingerprint?.takeUnless { it.isBlank() }
            ?: record?.fingerprint
        return ConnectionInfo(host, port, fingerprint)
    }

    private fun buildUrl(connection: ConnectionInfo, path: String): String =
        "https://${connection.host}:${connection.port}$path"

    private data class ConnectionInfo(
        val host: String,
        val port: Int,
        val fingerprint: String?
    )

    private class SessionCache {
        private val lock = Semaphore(1)
        var session: E2eSession? = null
        private var expiresAt: Instant = Instant.EPOCH

        fun isValid(): Boolean {
            return session != null && Instant.now().isBefore(expiresAt)
        }

        fun update(session: E2eSession) {
            this.session = session
            expiresAt = Instant.now().plusSeconds(SESSION_TTL_SECONDS)
        }

        fun acquire() {
            lock.acquire()
        }

        fun release() {
            lock.release()
        }
    }
}
