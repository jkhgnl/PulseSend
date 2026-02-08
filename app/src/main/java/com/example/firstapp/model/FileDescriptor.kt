package com.example.firstapp.model

import android.net.Uri

data class FileDescriptor(
    val uri: Uri,
    val name: String,
    val size: Long,
    val mimeType: String?
)
