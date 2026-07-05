package com.cappielloantonio.tempo.sonos.network

import com.cappielloantonio.tempo.sonos.SonosConstants
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import java.util.concurrent.TimeUnit

/**
 * Utility class for creating OkHttpClient instances configured for Sonos API communication.
 * 
 * Sonos uses self-signed certificates for its local API, so we need to accept all certificates.
 * This is safe for local network communication but should NOT be used for internet requests.
 */
object SonosOkHttpClient {
    
    /**
     * Creates an OkHttpClient that accepts Sonos self-signed certificates.
     * 
     * @param timeoutMillis Connection and read timeout in milliseconds
     * @return Configured OkHttpClient instance
     */
    fun createUnsafeClient(timeoutMillis: Long = SonosConstants.HTTP_TIMEOUT_MS): OkHttpClient {
        // Trust manager that accepts all certificates (including self-signed)
        val trustAllCerts = arrayOf<X509TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(
                chain: Array<X509Certificate>,
                authType: String
            ) {
                // Accept all client certificates
            }
            
            override fun checkServerTrusted(
                chain: Array<X509Certificate>,
                authType: String
            ) {
                // Accept all server certificates (including Sonos self-signed)
            }
            
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        
        // Create SSL context with our trust manager
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, trustAllCerts, SecureRandom())
        }
        
        val sslSocketFactory = sslContext.socketFactory
        
        return OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustAllCerts[0])
            .hostnameVerifier { _, _ -> true } // Accept all hostnames
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .build()
    }
    
    /**
     * Creates an OkHttpClient specifically for WebSocket connections to Sonos devices.
     * 
     * @return Configured OkHttpClient for WebSocket with longer timeout
     */
    fun createWebSocketClient(): OkHttpClient {
        return createUnsafeClient(SonosConstants.WEBSOCKET_TIMEOUT_MS)
    }
}
