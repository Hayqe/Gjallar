package com.cappielloantonio.tempo.sonos.api

/**
 * GroupVolume namespace for the Sonos API.
 * Controls volume for a group of speakers.
 * 
 * API Reference: https://docs.sonos.com/reference/groupvolume
 */
class GroupVolumeNamespace(private val api: SonosWebSocketApi) {
    
    /**
     * Get the current volume for a group.
     * 
     * @param groupId The group ID
     * @return Volume information including volume level and mute state
     */
    suspend fun getVolume(groupId: String): Map<String, Any> {
        return api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.GroupVolume,
            command = "getVolume",
            objectId = groupId
        ) as Map<String, Any>
    }
    
    /**
     * Set the volume for a group.
     * 
     * @param groupId The group ID
     * @param volume Volume level (0-100)
     */
    suspend fun setVolume(groupId: String, volume: Int) {
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.GroupVolume,
            command = "setVolume",
            objectId = groupId,
            options = mapOf("volume" to volume)
        )
    }
    
    /**
     * Set the mute state for a group.
     * 
     * @param groupId The group ID
     * @param muted Whether to mute the group
     */
    suspend fun setMuted(groupId: String, muted: Boolean) {
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.GroupVolume,
            command = "setMuted",
            objectId = groupId,
            options = mapOf("muted" to muted)
        )
    }
    
    /**
     * Subscribe to group volume events.
     * 
     * @param groupId The group ID
     * @param callback Callback for volume events
     */
    suspend fun subscribe(groupId: String, callback: (Map<String, Any>) -> Unit) {
        // Note: Subscription handling would need to be implemented in SonosWebSocketApi
        // For now, this just sends the subscribe command
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.GroupVolume,
            command = "subscribe",
            objectId = groupId
        )
    }
    
    /**
     * Unsubscribe from group volume events.
     * 
     * @param groupId The group ID
     */
    suspend fun unsubscribe(groupId: String) {
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.GroupVolume,
            command = "unsubscribe",
            objectId = groupId
        )
    }
}
