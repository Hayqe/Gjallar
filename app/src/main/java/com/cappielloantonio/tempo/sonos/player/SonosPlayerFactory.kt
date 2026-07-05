package com.cappielloantonio.tempo.sonos.player

import android.content.Context
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import com.cappielloantonio.tempo.sonos.api.GroupVolumeNamespace
import com.cappielloantonio.tempo.sonos.api.PlaybackNamespace
import com.cappielloantonio.tempo.sonos.api.PlaybackSessionNamespace
import com.cappielloantonio.tempo.sonos.api.SonosWebSocketApi
import com.cappielloantonio.tempo.sonos.discovery.SonosDiscovery
import com.cappielloantonio.tempo.sonos.models.SonosDevice
import com.cappielloantonio.tempo.sonos.models.SonosGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Factory for creating SonosExoPlayerProxy instances.
 * Manages WebSocket connections and proxy lifecycles.
 * 
 * This factory ensures that:
 * - Each device has at most one WebSocket connection
 * - Each group has at most one proxy
 * - Resources are properly cleaned up when no longer needed
 */
class SonosPlayerFactory(private val context: Context) {
    
    // Shared discovery instance for all proxies
    private val discovery = SonosDiscovery(context)
    
    // Map of WebSocket APIs by device ID - each device gets one connection
    private val webSocketApis = mutableMapOf<String, SonosWebSocketApi>()
    
    // Map of proxies by group ID - each group gets one proxy
    private val proxies = mutableMapOf<String, SonosExoPlayerProxy>()
    
    /**
     * Create a SonosExoPlayerProxy for a group.
     * 
     * @param group The Sonos group
     * @param device The coordinator device for the group
     * @param exoPlayer Optional existing ExoPlayer to use (e.g., from MediaLibrarySession)
     * @return A new SonosExoPlayerProxy, or null if creation failed
     */
    suspend fun createProxy(
        group: SonosGroup, 
        device: SonosDevice,
        exoPlayer: ExoPlayer? = null
    ): SonosExoPlayerProxy? {
        Log.d("SonosPlayerFactory", "createProxy for group ${group.id}, device ${device.id}")
        // Get or create WebSocket API for the device (connect() is suspend and waits for connection)
        val wsApi = getOrCreateWebSocketApi(device)
        
        // Use provided ExoPlayer or create a new one on main thread
        // (ExoPlayer must be accessed only from main thread)
        val playerToUse = exoPlayer ?: withContext(Dispatchers.Main) {
            ExoPlayer.Builder(context).build()
        }
        
        // Create API wrappers
        val playbackApi = PlaybackNamespace(wsApi)
        val playbackSessionApi = PlaybackSessionNamespace(wsApi)
        val groupVolumeApi = GroupVolumeNamespace(wsApi)
        
        // Create proxy on main thread to ensure init block runs on main thread
        val proxy = withContext(Dispatchers.Main) {
            SonosExoPlayerProxy(
                exoPlayer = playerToUse,
                group = group,
                device = device,
                discovery = discovery,
                playbackApi = playbackApi,
                playbackSessionApi = playbackSessionApi,
                groupVolumeApi = groupVolumeApi,
                wsApi = wsApi
            )
        }
        
        proxies[group.id] = proxy
        return proxy
    }
    
    /**
     * Get or create a WebSocket API for a device.
     */
    private suspend fun getOrCreateWebSocketApi(device: SonosDevice): SonosWebSocketApi {
        Log.d("SonosPlayerFactory", "getOrCreateWebSocketApi for device ${device.id} at ${device.ipAddress}")
        return webSocketApis.getOrPut(device.id) {
            SonosWebSocketApi(device) { event ->
                // Handle Sonos events
                handleSonosEvent(device.id, event)
            }.also { api ->
                // Connect immediately
                Log.d("SonosPlayerFactory", "Connecting WebSocket for device ${device.id}")
                val connected = api.connect()
                Log.d("SonosPlayerFactory", "WebSocket connect result: $connected")
            }
        }
    }
    
    /**
     * Handle Sonos events from WebSocket.
     * Currently just logs events. Future enhancement: forward to appropriate proxy.
     */
    private fun handleSonosEvent(deviceId: String, event: com.cappielloantonio.tempo.sonos.models.SonosEvent) {
        Log.d("SonosPlayerFactory", "Received event from $deviceId: ${event.type}")
    }
    
    /**
     * Release a proxy for a group.
     * 
     * @param groupId The group ID
     */
    fun releaseProxy(groupId: String) {
        proxies[groupId]?.let { proxy ->
            try {
                proxy.release()
            } catch (e: Exception) {
                Log.e("SonosPlayerFactory", "Error releasing proxy for group $groupId", e)
            }
        }
        proxies.remove(groupId)
    }
    
    /**
     * Release all proxies and WebSocket connections.
     * Also stops any ongoing discovery operations.
     */
    fun releaseAll() {
        Log.d("SonosPlayerFactory", "Releasing all Sonos resources")
        
        // Release all proxies first
        proxies.values.forEach { proxy ->
            try {
                proxy.release()
            } catch (e: Exception) {
                Log.e("SonosPlayerFactory", "Error releasing proxy", e)
            }
        }
        proxies.clear()
        
        // Disconnect all WebSocket connections
        webSocketApis.values.forEach { api ->
            try {
                api.disconnect()
            } catch (e: Exception) {
                Log.e("SonosPlayerFactory", "Error disconnecting WebSocket API", e)
            }
        }
        webSocketApis.clear()
        
        // Stop discovery
        try {
            discovery.stopDiscovery()
        } catch (e: Exception) {
            Log.e("SonosPlayerFactory", "Error stopping discovery", e)
        }
    }
    
    /**
     * Get the shared discovery instance.
     * Useful for UI components that need to observe device discovery.
     */
    fun getDiscovery(): SonosDiscovery {
        return discovery
    }
}
