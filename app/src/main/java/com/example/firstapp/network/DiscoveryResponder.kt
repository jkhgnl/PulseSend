package com.example.firstapp.network

import android.content.Context
import android.net.wifi.WifiManager
import com.example.firstapp.core.device.DeviceIdentity
import com.example.firstapp.protocol.DiscoveryMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket

class DiscoveryResponder(
    private val context: Context,
    private val identity: DeviceIdentity,
    private val tlsPort: Int,
    private val fingerprint: String,
    private val port: Int = 24821
) {
    private val json = Json { ignoreUnknownKeys = true }
    private var socket: DatagramSocket? = null
    private var lock: WifiManager.MulticastLock? = null
    private var job: Job? = null

    fun start() {
        if (job != null) return
        lock = acquireMulticastLock()
        val responderSocket = DatagramSocket(port).apply {
            broadcast = true
            reuseAddress = true
        }
        socket = responderSocket
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        job = scope.launch {
            val buffer = ByteArray(2048)
            while (isActive) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    responderSocket.receive(packet)
                    val raw = packet.data.copyOf(packet.length).toString(Charsets.UTF_8)
                    val request = runCatching {
                        json.decodeFromString(DiscoveryMessage.serializer(), raw)
                    }.getOrNull() ?: continue
                    if (request.type != "DISCOVER") continue
                    val response = DiscoveryMessage(
                        type = "ADVERTISE",
                        deviceId = identity.id,
                        deviceName = identity.name,
                        platform = identity.platform,
                        tlsPort = tlsPort,
                        fingerprint = fingerprint
                    )
                    val payload = json.encodeToString(DiscoveryMessage.serializer(), response)
                        .toByteArray()
                    val reply = DatagramPacket(payload, payload.size, packet.address, packet.port)
                    responderSocket.send(reply)
                } catch (ex: Exception) {
                    delay(200)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        socket?.close()
        socket = null
        lock?.release()
        lock = null
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wifi?.createMulticastLock("PulseSendResponder")?.apply { setReferenceCounted(false); acquire() }
    }
}
