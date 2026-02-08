package com.example.firstapp.model

import java.util.UUID

data class MessageItem(
    val id: String = UUID.randomUUID().toString(),
    val peerName: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val direction: MessageDirection = MessageDirection.Incoming
)

enum class MessageDirection {
    Incoming,
    Outgoing
}
