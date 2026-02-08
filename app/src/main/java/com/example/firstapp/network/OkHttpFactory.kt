package com.example.firstapp.network

import android.util.Log
import com.example.firstapp.core.crypto.CryptoUtils
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object OkHttpFactory {
    fun pairingClient(): OkHttpClient {
        val trustAll = trustAllManager()
        val sslSocketFactory = sslSocketFactory(trustAll)
        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustAll)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .build()
    }

    fun pinnedClient(host: String, pin: String): OkHttpClient {
        Log.d("PulseSend", "Creating TLS client for $host (pin verification disabled; saved pin=$pin)")
        val trustAll = trustAllManager()
        val sslSocketFactory = sslSocketFactory(trustAll)
        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustAll)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .build()
    }

    fun certificatePin(cert: X509Certificate): String {
        val hash = CryptoUtils.sha256(cert.publicKey.encoded)
        return "sha256/${CryptoUtils.toBase64(hash)}"
    }

    private fun trustAllManager(): X509TrustManager =
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

    private fun sslSocketFactory(trustManager: X509TrustManager): SSLSocketFactory {
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        return sslContext.socketFactory
    }
}
