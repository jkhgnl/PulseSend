package com.example.firstapp.util

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.example.firstapp.model.FileDescriptor

object FileResolver {
    fun resolve(context: Context, uri: Uri): FileDescriptor {
        val resolver = context.contentResolver
        var name = "unknown"
        var size = 0L
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) {
                    name = cursor.getString(nameIndex) ?: name
                }
                if (sizeIndex >= 0) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
        val mime = resolver.getType(uri)
        return FileDescriptor(uri = uri, name = name, size = size, mimeType = mime)
    }

    fun openInputStream(resolver: ContentResolver, uri: Uri) =
        resolver.openInputStream(uri)
}
