package com.example.firstapp.model

import java.util.UUID

data class TransferItem(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val totalBytes: Long,
    val sentBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val status: TransferStatus = TransferStatus.Pending,
    val direction: TransferDirection = TransferDirection.Upload,
    val localPath: String? = null,
    val localUri: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class TransferStatus {
    Pending,
    Preparing,
    Transferring,
    Completed,
    Failed
}

enum class TransferDirection {
    Upload,
    Download
}
