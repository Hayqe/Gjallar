package com.cappielloantonio.tempo.sonos

/**
 * Shared constants for Sonos API communication.
 */
object SonosConstants {
    // Sonos API constants
    const val LOCAL_API_TOKEN = "123e4567-e89b-12d3-a456-426655440000"
    const val API_VERSION = 1
    const val DISCOVERY_PORT = 1443
    
    // Timeouts
    const val WEBSOCKET_TIMEOUT_MS = 5000L
    const val HTTP_TIMEOUT_MS = 500L
    const val DISCOVERY_TIMEOUT_MS = 3000L
    
    // WebSocket
    const val WEBSOCKET_PROTOCOL = "v1.api.smartspeaker.audio"
    const val WEBSOCKET_API_PATH = "/ws"
    
    // mDNS/NSD constants for Sonos device discovery
    const val SONOS_SERVICE_TYPE = "_sonos._tcp."
    const val SONOS_SERVICE_NAME = "Sonos"
    
    // API endpoints
    fun getDiscoveryUrl(ip: String): String = "https://$ip:$DISCOVERY_PORT/api/v1/players/local/info"
    fun getGroupsUrl(ip: String, householdId: String): String = "https://$ip:$DISCOVERY_PORT/api/v1/households/$householdId/groups"
    fun getWebSocketUrl(ip: String): String = "wss://$ip:$DISCOVERY_PORT$WEBSOCKET_API_PATH"
}
