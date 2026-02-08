package com.example.firstapp.core.crypto

import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

object E2eCrypto {
    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("X25519")
        return generator.generateKeyPair()
    }

    fun publicKeyBytes(keyPair: KeyPair): ByteArray = keyPair.public.encoded

    fun publicKeyFromBytes(encoded: ByteArray): PublicKey {
        val spec = X509EncodedKeySpec(encoded)
        val factory = KeyFactory.getInstance("X25519")
        return factory.generatePublic(spec)
    }

    fun deriveSharedSecret(privateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val keyAgreement = KeyAgreement.getInstance("X25519")
        keyAgreement.init(privateKey)
        keyAgreement.doPhase(peerPublicKey, true)
        return keyAgreement.generateSecret()
    }

    fun encrypt(
        key: ByteArray,
        nonce: ByteArray,
        plaintext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "ChaCha20"),
            IvParameterSpec(nonce)
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(plaintext)
    }

    fun decrypt(
        key: ByteArray,
        nonce: ByteArray,
        ciphertext: ByteArray,
        aad: ByteArray
    ): ByteArray {
        val cipher = Cipher.getInstance("ChaCha20-Poly1305")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "ChaCha20"),
            IvParameterSpec(nonce)
        )
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext)
    }
}
