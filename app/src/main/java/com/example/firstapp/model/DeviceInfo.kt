package com.example.firstapp.model

data class DeviceInfo(
    val id: String,
    val name: String,
    val platform: String,
    val address: String,
    val tlsPort: Int,
    val fingerprint: String?,
    val lastSeen: Long = 0L
)
