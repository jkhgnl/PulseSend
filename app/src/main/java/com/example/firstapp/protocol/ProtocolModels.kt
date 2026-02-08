package com.example.firstapp.protocol

import kotlinx.serialization.Serializable

@Serializable
data class DiscoveryMessage(
    val type: String,
    val deviceId: String,
    val deviceName: String,
    val platform: String,
    val tlsPort: Int? = null,
    val fingerprint: String? = null
)

@Serializable
data class PairRequest(
    val deviceId: String,
    val deviceName: String,
    val publicKey: String,
    val code: String
)

@Serializable
data class PairResponse(
    val deviceId: String,
    val deviceName: String,
    val fingerprint: String,
    val peerPublicKey: String,
    val salt: String,
    val token: String? = null
)

@Serializable
data class SessionRequest(
    val deviceId: String,
    val publicKey: String,
    val token: String? = null
)

@Serializable
data class SessionResponse(
    val peerPublicKey: String,
    val salt: String
)

@Serializable
data class TransferInitRequest(
    val fileName: String,
    val fileSize: Long,
    val mimeType: String?,
    val sha256: String,
    val chunkSize: Int
)

@Serializable
data class TransferInitResponse(
    val transferId: String,
    val accepted: Boolean,
    val missingChunks: List<Int> = emptyList()
)

@Serializable
data class TransferChunkRequest(
    val transferId: String,
    val index: Int,
    val totalChunks: Int,
    val nonce: String,
    val cipherText: String,
    val aad: String
)

@Serializable
data class TransferChunkResponse(
    val received: Boolean
)

@Serializable
data class TextMessageRequest(
    val messageId: String,
    val nonce: String,
    val cipherText: String,
    val aad: String
)

@Serializable
data class TextMessageResponse(
    val received: Boolean
)
