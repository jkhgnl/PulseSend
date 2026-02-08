package com.example.firstapp.network

data class PairingResult(
    val fingerprint: String,
    val token: String
)

data class E2eSession(
    val key: ByteArray,
    val token: String?,
    val host: String,
    val port: Int,
    val fingerprint: String?
)
