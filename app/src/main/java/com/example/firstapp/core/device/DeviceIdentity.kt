package com.example.firstapp.core.device

import android.content.Context
import android.os.Build
import android.provider.Settings

data class DeviceIdentity(
    val id: String,
    val name: String,
    val platform: String = "android"
)

object DeviceIdentityProvider {
    fun from(context: Context): DeviceIdentity {
        val id = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "android-${Build.MODEL}-${System.currentTimeMillis()}"
        val name = listOfNotNull(Build.MANUFACTURER, Build.MODEL)
            .joinToString(" ")
            .ifBlank { "Android Device" }
        return DeviceIdentity(id = id, name = name, platform = "android")
    }
}
