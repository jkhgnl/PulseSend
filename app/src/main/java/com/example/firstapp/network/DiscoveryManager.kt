package com.example.firstapp.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.example.firstapp.core.device.DeviceIdentity
import com.example.firstapp.data.TrustedDeviceStore
import com.example.firstapp.model.DeviceInfo
import com.example.firstapp.protocol.DiscoveryMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class DiscoveryManager(
    private val context: Context,
    private val identity: DeviceIdentity,
    private val store: TrustedDeviceStore,
    private val port: Int = 24821
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun discover(scope: CoroutineScope): Flow<DeviceInfo> = callbackFlow {
        val socket = DatagramSocket(0).apply {
            broadcast = true
            reuseAddress = true
        }
        val lock = acquireMulticastLock()

        val senderJob = scope.launch(Dispatchers.IO) {
            val broadcastAddress = InetAddress.getByName("255.255.255.255")
            while (isActive) {
                val message = DiscoveryMessage(
                    type = "DISCOVER",
                    deviceId = identity.id,
                    deviceName = identity.name,
                    platform = identity.platform
                )
                val payload = json.encodeToString(DiscoveryMessage.serializer(), message).toByteArray()
                val packet = DatagramPacket(payload, payload.size, broadcastAddress, port)
                socket.send(packet)
                delay(2_000)
            }
        }

        val receiverJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            while (isActive) {
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val raw = packet.data.copyOf(packet.length).toString(Charsets.UTF_8)
                val response = runCatching {
                    json.decodeFromString(DiscoveryMessage.serializer(), raw)
                }.getOrNull() ?: continue
                if (response.type != "ADVERTISE") {
                    continue
                }
                if (response.deviceId == identity.id) {
                    continue
                }
                val device = DeviceInfo(
                    id = response.deviceId,
                    name = response.deviceName,
                    platform = response.platform,
                    address = packet.address.hostAddress ?: packet.address.hostName ?: "",
                    tlsPort = response.tlsPort ?: 0,
                    fingerprint = response.fingerprint,
                    lastSeen = System.currentTimeMillis()
                )
                Log.d("PulseSend", "Discovery advertise from ${device.name} @ ${device.address}:${device.tlsPort}")
                try {
                    store.updateLastSeen(
                        device.id,
                        device.address.takeIf { it.isNotBlank() },
                        device.tlsPort.takeIf { it > 0 }
                    )
                } catch (ex: Exception) {
                    Log.w("PulseSend", "Ignore discovery last-seen update", ex)
                }
                trySend(device)
            }
        }

        awaitClose {
            senderJob.cancel()
            receiverJob.cancel()
            socket.close()
            lock?.release()
        }
    }

    private fun acquireMulticastLock(): WifiManager.MulticastLock? {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        return wifi?.createMulticastLock("PulseSendDiscovery")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }
}
