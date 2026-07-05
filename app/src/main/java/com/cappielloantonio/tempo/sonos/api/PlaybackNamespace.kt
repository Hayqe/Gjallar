package com.cappielloantonio.tempo.sonos.api

/**
 * Playback namespace for the Sonos API.
 * Controls playback state (play, pause, stop, seek, etc.)
 * 
 * API Reference: https://docs.sonos.com/reference/playback
 */
class PlaybackNamespace(private val api: SonosWebSocketApi) {
    
    /**
     * Get the current playback status for a group.
     * 
     * @param groupId The group ID
     * @return Playback status information
     */
    suspend fun getPlaybackStatus(groupId: String): Map<String, Any> {
        return api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.Playback,
            command = "getPlaybackStatus",
            objectId = groupId
        ) as Map<String, Any>
    }
    
    /**
     * Start playback.
     * 
     * @param groupId The group ID
     */
    suspend fun play(groupId: String) {
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.Playback,
            command = "play",
            objectId = groupId
        )
    }
    
    /**
     * Pause playback.
     * 
     * @param groupId The group ID
     */
    suspend fun pause(groupId: String) {
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.Playback,
            command = "pause",
            objectId = groupId
        )
    }
    
    /**
     * Stop playback.
     * 
     * @param groupId The group ID
     */
    suspend fun stop(groupId: String) {
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.Playback,
            command = "stop",
            objectId = groupId
        )
    }
    
    /**
     * Toggle between play and pause.
     * 
     * @param groupId The group ID
     */
    suspend fun togglePlayPause(groupId: String) {
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.Playback,
            command = "togglePlayPause",
            objectId = groupId
        )
    }
    
    /**
     * Seek to an absolute position.
     * 
     * @param groupId The group ID
     * @param positionMillis Position in milliseconds
     * @param itemId Optional item ID to seek within
     */
    suspend fun seek(
        groupId: String,
        positionMillis: Long,
        itemId: String? = null
    ) {
        val options = mutableMapOf<String, Any>("positionMillis" to positionMillis)
        itemId?.let { options["itemId"] = it }
        
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.Playback,
            command = "seek",
            objectId = groupId,
            options = options
        )
    }
    
    /**
     * Seek relative to current position.
     * 
     * @param groupId The group ID
     * @param deltaMillis Change in milliseconds (positive or negative)
     * @param itemId Optional item ID to seek within
     */
    suspend fun seekRelative(
        groupId: String,
        deltaMillis: Long,
        itemId: String? = null
    ) {
        val options = mutableMapOf<String, Any>("deltaMillis" to deltaMillis)
        itemId?.let { options["itemId"] = it }
        
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.Playback,
            command = "seekRelative",
            objectId = groupId,
            options = options
        )
    }
    
    /**
     * Skip to next track.
     * 
     * @param groupId The group ID
     */
    suspend fun skipToNextTrack(groupId: String) {
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.Playback,
            command = "skipToNextTrack",
            objectId = groupId
        )
    }
    
    /**
     * Skip to previous track.
     * 
     * @param groupId The group ID
     */
    suspend fun skipToPreviousTrack(groupId: String) {
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.Playback,
            command = "skipToPreviousTrack",
            objectId = groupId
        )
    }
    
    /**
     * Set playback modes (repeat, shuffle, crossfade).
     * 
     * @param groupId The group ID
     * @param repeat Enable/disable repeat
     * @param repeatOne Enable/disable repeat one
     * @param shuffle Enable/disable shuffle
     * @param crossfade Enable/disable crossfade
     */
    suspend fun setPlayModes(
        groupId: String,
        repeat: Boolean? = null,
        repeatOne: Boolean? = null,
        shuffle: Boolean? = null,
        crossfade: Boolean? = null
    ) {
        val playModes = mutableMapOf<String, Boolean>()
        repeat?.let { playModes["repeat"] = it }
        repeatOne?.let { playModes["repeatOne"] = it }
        shuffle?.let { playModes["shuffle"] = it }
        crossfade?.let { playModes["crossfade"] = it }
        
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.Playback,
            command = "setPlayModes",
            objectId = groupId,
            options = mapOf("playModes" to playModes)
        )
    }
    
    /**
     * Load line-in source.
     * 
     * @param groupId The group ID
     * @param deviceId Optional device ID for line-in source
     * @param playOnCompletion Whether to start playback immediately
     */
    suspend fun loadLineIn(
        groupId: String,
        deviceId: String? = null,
        playOnCompletion: Boolean = false
    ) {
        val options = mutableMapOf<String, Any>()
        deviceId?.let { options["deviceId"] = it }
        options["playOnCompletion"] = playOnCompletion
        
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.Playback,
            command = "loadLineIn",
            objectId = groupId,
            options = options
        )
    }
}
