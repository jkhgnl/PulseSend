package com.example.firstapp.model

data class ServerSnapshot(
    val statusText: String = "未启动",
    val pairCode: String = "------",
    val fingerprint: String = "",
    val port: Int = 48084
)
