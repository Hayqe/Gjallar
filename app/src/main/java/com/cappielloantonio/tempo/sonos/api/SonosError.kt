package com.cappielloantonio.tempo.sonos.api

/**
 * Sealed class representing all possible errors that can occur during Sonos API communication.
 * This provides a type-safe way to handle different error scenarios.
 */
sealed class SonosError(
    override val message: String,
    val errorCode: String? = null,
    val reason: String? = null,
    override val cause: Throwable? = null
) : Exception(message) {
    
    /**
     * Network-related errors (connection failures, timeouts)
     */
    class NetworkError(
        message: String = "Network error occurred",
        cause: Throwable? = null
    ) : SonosError(message, "NETWORK_ERROR", cause = cause)
    
    /**
     * WebSocket connection errors
     */
    class WebSocketError(
        message: String = "WebSocket connection failed",
        errorCode: String? = null,
        reason: String? = null,
        cause: Throwable? = null
    ) : SonosError(message, errorCode, reason, cause)
    
    /**
     * API command execution errors (invalid commands, wrong parameters)
     */
    class ApiError(
        message: String,
        errorCode: String? = null,
        reason: String? = null,
        cause: Throwable? = null
    ) : SonosError(message, errorCode, reason, cause)
    
    /**
     * Authentication errors (invalid API key, etc.)
     */
    class AuthenticationError(
        message: String = "Authentication failed",
        errorCode: String? = null
    ) : SonosError(message, errorCode)
    
    /**
     * Device not found or unreachable
     */
    class DeviceNotFound(
        message: String = "Sonos device not found",
        val deviceId: String? = null
    ) : SonosError(message, "DEVICE_NOT_FOUND")
    
    /**
     * Session-related errors (session creation, invalid session ID)
     */
    class SessionError(
        message: String,
        errorCode: String? = null,
        val sessionId: String? = null,
        val groupId: String? = null,
        cause: Throwable? = null
    ) : SonosError(message, errorCode, cause = cause)
    
    /**
     * Group-related errors (group not found, coordinator changed)
     */
    class GroupError(
        message: String,
        errorCode: String? = null,
        val groupId: String? = null
    ) : SonosError(message, errorCode)
    
    /**
     * Playback errors
     */
    class PlaybackError(
        message: String,
        errorCode: String? = null,
        cause: Throwable? = null
    ) : SonosError(message, errorCode, cause = cause)
    
    /**
     * JSON parsing errors
     */
    class ParsingError(
        message: String = "Failed to parse Sonos response",
        val rawData: String? = null,
        cause: Throwable? = null
    ) : SonosError(message, "PARSE_ERROR", cause = cause)
    
    /**
     * Discovery errors (no devices found, mDNS failure)
     */
    class DiscoveryError(
        message: String = "Failed to discover Sonos devices",
        cause: Throwable? = null
    ) : SonosError(message, "DISCOVERY_ERROR", cause = cause)
    
    /**
     * Unknown/Generic error
     */
    class UnknownError(
        message: String = "Unknown Sonos error",
        cause: Throwable? = null
    ) : SonosError(message, "UNKNOWN", cause = cause)
}

/**
 * Helper function to convert a Throwable to a SonosError
 */
fun Throwable.toSonosError(defaultMessage: String = this.message ?: "Unknown error"): SonosError {
    return when (this) {
        is SonosError -> this
        is java.net.SocketTimeoutException -> SonosError.NetworkError("Connection timed out", this)
        is java.net.ConnectException -> SonosError.NetworkError("Connection refused", this)
        is java.net.UnknownHostException -> SonosError.NetworkError("Host not found", this)
        else -> SonosError.UnknownError(defaultMessage, this)
    }
}
