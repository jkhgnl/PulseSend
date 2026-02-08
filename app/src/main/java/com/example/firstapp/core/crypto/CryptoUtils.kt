package com.example.firstapp.core.crypto

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object CryptoUtils {
    private val secureRandom = SecureRandom()

    fun sha256(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input)

    fun randomBytes(size: Int): ByteArray = ByteArray(size).also { secureRandom.nextBytes(it) }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        var output = ByteArray(0)
        var previous = ByteArray(0)
        var counter = 1
        while (output.size < size) {
            val data = previous + info + byteArrayOf(counter.toByte())
            previous = hmacSha256(prk, data)
            output += previous
            counter++
        }
        return output.copyOf(size)
    }

    fun toBase64(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.NO_WRAP)

    fun fromBase64(value: String): ByteArray =
        Base64.decode(value, Base64.NO_WRAP)

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
