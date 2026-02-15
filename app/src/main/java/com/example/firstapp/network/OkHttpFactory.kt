package com.example.firstapp.network

import android.util.Log
import com.example.firstapp.core.crypto.CryptoUtils
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object OkHttpFactory {
    private val clientCache = ConcurrentHashMap<String, OkHttpClient>()
    private val sharedPool = ConnectionPool(16, 5, TimeUnit.MINUTES)

    fun pairingClient(): OkHttpClient {
        val trustAll = trustAllManager()
        val sslSocketFactory = sslSocketFactory(trustAll)
        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustAll)
            .hostnameVerifier(HostnameVerifier { _, _ -> true })
            .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
            .connectionPool(sharedPool)
            .retryOnConnectionFailure(true)
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun pinnedClient(host: String, pin: String): OkHttpClient {
        require(pin.isNotBlank()) { "Pinned client requires a non-empty pin for host $host" }
        val cacheKey = "$host|$pin"
        return clientCache.getOrPut(cacheKey) {
            Log.d("PulseSend", "Creating TLS client for $host with pin verification")
            val trustAll = trustAllManager()
            val sslSocketFactory = sslSocketFactory(trustAll)
            OkHttpClient.Builder()
                .sslSocketFactory(sslSocketFactory, trustAll)
                .hostnameVerifier(HostnameVerifier { _, session ->
                    val cert = runCatching {
                        session.peerCertificates.firstOrNull() as? X509Certificate
                    }.getOrNull() ?: return@HostnameVerifier false
                    certificatePin(cert) == pin
                })
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectionPool(sharedPool)
                .retryOnConnectionFailure(true)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
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
