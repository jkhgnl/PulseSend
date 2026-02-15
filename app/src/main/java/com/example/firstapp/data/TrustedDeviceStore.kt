package com.example.firstapp.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
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
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

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
            prefs[storageKey] = encrypt(json.encodeToString(current))
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
            prefs[storageKey] = encrypt(json.encodeToString(emptyMap<String, TrustedDeviceRecord>()))
        }
    }

    private fun decode(prefs: Preferences): Map<String, TrustedDeviceRecord> {
        val raw = prefs[storageKey] ?: return emptyMap()
        val plain = decrypt(raw) ?: raw
        return runCatching {
            json.decodeFromString<Map<String, TrustedDeviceRecord>>(plain)
        }.getOrElse { emptyMap() }
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val ivPart = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val dataPart = Base64.encodeToString(cipherText, Base64.NO_WRAP)
        return "$ivPart:$dataPart"
    }

    private fun decrypt(encoded: String): String? {
        val parts = encoded.split(':', limit = 2)
        if (parts.size != 2) return null
        return runCatching {
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val data = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = javax.crypto.spec.GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), spec)
            val plain = cipher.doFinal(data)
            plain.toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "PulseSendTrustedStoreKey"
    }
}
