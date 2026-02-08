package com.example.firstapp.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.trustedDeviceStore by preferencesDataStore(name = "trusted_devices")

@Serializable
data class TrustedDeviceRecord(
    val deviceId: String,
    val deviceName: String = "",
    val fingerprint: String,
    val outgoingToken: String? = null,
    val incomingToken: String? = null,
    val token: String? = null,
    val lastAddress: String? = null,
    val lastPort: Int? = null
)

class TrustedDeviceStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val storageKey = stringPreferencesKey("trusted_devices_json")

    val trustedDevices: Flow<Map<String, TrustedDeviceRecord>> =
        context.trustedDeviceStore.data.map { prefs ->
            decode(prefs)
        }

    suspend fun get(deviceId: String): TrustedDeviceRecord? =
        decode(context.trustedDeviceStore.data.first())[deviceId]

    suspend fun save(record: TrustedDeviceRecord) {
        context.trustedDeviceStore.edit { prefs ->
            val current = decode(prefs).toMutableMap()
            current[record.deviceId] = record
            prefs[storageKey] = json.encodeToString(current)
        }
    }

    suspend fun updateLastSeen(deviceId: String, address: String?, port: Int?) {
        val current = get(deviceId) ?: return
        val updated = current.copy(
            lastAddress = address ?: current.lastAddress,
            lastPort = port ?: current.lastPort
        )
        save(updated)
    }

    suspend fun resetTokens(deviceId: String) {
        val current = get(deviceId) ?: return
        val updated = current.copy(
            outgoingToken = null,
            incomingToken = null,
            token = null
        )
        save(updated)
    }

    suspend fun clearAll() {
        context.trustedDeviceStore.edit { prefs ->
            prefs[storageKey] = json.encodeToString(emptyMap<String, TrustedDeviceRecord>())
        }
    }

    private fun decode(prefs: Preferences): Map<String, TrustedDeviceRecord> {
        val raw = prefs[storageKey] ?: return emptyMap()
        return runCatching {
            json.decodeFromString<Map<String, TrustedDeviceRecord>>(raw)
        }.getOrElse { emptyMap() }
    }
}
