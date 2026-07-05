package com.cappielloantonio.tempo.sonos.api

import android.util.Log
import com.cappielloantonio.tempo.sonos.api.SonosError

/**
 * PlaybackSession namespace for the Sonos API.
 * 
 * This is CRITICAL for streaming from Navidrome!
 * The playback session is used to load and control streams.
 * 
 * API Reference: https://docs.sonos.com/reference/playbacksession
 */
class PlaybackSessionNamespace(private val api: SonosWebSocketApi) {
    
    /**
     * Create a new playback session.
     * A session is required before loading any media.
     * 
     * IMPORTANT: Sonos API requires playerId, NOT groupId for createSession.
     * The session is created on a specific player (the coordinator).
     * 
     * @param playerId The ID of the PLAYER (not group) to create the session for
     * @param groupId The group ID to associate with the session
     * @param appId Application identifier (default: com.cappielloantonio.tempo)
     * @param appContext Application context (default: "1")
     * @param accountId Optional account identifier
     * @return Session info containing sessionId
     * @throws SonosError.SessionError if session creation fails
     */
    @Throws(SonosError.SessionError::class)
    suspend fun createSession(
        playerId: String,
        groupId: String,
        appId: String = "com.cappielloantonio.tempo",
        appContext: String = "1",
        accountId: String? = null
    ): Map<String, Any> {
        val options = mutableMapOf(
            "appId" to appId,
            "appContext" to appContext
        )
        accountId?.let { options["accountId"] = it }
        
        // For createSession, both playerId and groupId need to be in the header
        try {
            val response = api.sendCommand(
                namespace = SonosWebSocketApi.Namespace.PlaybackSession,
                command = "createSession",
                objectId = playerId,
                options = options,
                extraHeaders = mapOf("groupId" to groupId)
            )
            
            Log.d("PlaybackSessionNamespace", "createSession response type: ${response::class.simpleName}")
            
            // Try to extract sessionId from various response formats
            return extractSessionIdFromResponse(response, playerId, groupId)
        } catch (e: Exception) {
            Log.e("PlaybackSessionNamespace", "Failed to create session for player $playerId", e)
            throw SonosError.SessionError(
                "Failed to create session for player $playerId",
                "SESSION_CREATION_FAILED",
                sessionId = null,
                cause = e
            )
        }
    }
    
    /**
     * Extract session ID from various Sonos response formats.
     * Sonos can return session info in different ways depending on the device/firmware.
     * 
     * @param response The raw response from the API
     * @param playerId The player ID used for session creation
     * @param groupId The group ID used for session creation
     * @return Map containing session info, or throws if no sessionId found
     */
    @Throws(SonosError.SessionError::class)
    private fun extractSessionIdFromResponse(
        response: Any,
        playerId: String,
        groupId: String
    ): Map<String, Any> {
        // Check if response is a Map with sessionId
        if (response is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val responseMap = response as Map<String, Any>
            Log.d("PlaybackSessionNamespace", "Response is Map: $responseMap")
            
            if (responseMap.containsKey("sessionId")) {
                val sessionId = responseMap["sessionId"] as? String
                if (sessionId != null && sessionId.isNotEmpty()) {
                    Log.d("PlaybackSessionNamespace", "Found sessionId directly in response: $sessionId")
                    return responseMap
                }
            }
        }
        
        // Check if response is a List (event-style response)
        @Suppress("UNCHECKED_CAST")
        if (response is List<*>) {
            val responseList = response as List<Any>
            Log.d("PlaybackSessionNamespace", "Response is List with ${responseList.size} elements")
            
            if (responseList.size >= 2) {
                val body = responseList[1]
                if (body is Map<*, *>) {
                    @Suppress("UNCHECKED_CAST")
                    val bodyMap = body as Map<String, Any>
                    
                    // Check for sessionId directly in body
                    if (bodyMap.containsKey("sessionId")) {
                        val sessionId = bodyMap["sessionId"] as? String
                        if (sessionId != null && sessionId.isNotEmpty()) {
                            Log.d("PlaybackSessionNamespace", "Found sessionId in body: $sessionId")
                            return bodyMap
                        }
                    }
                    
                    // Check for _objectType == sessionStatus
                    if (bodyMap["_objectType"] as? String == "sessionStatus") {
                        val sessionId = bodyMap["sessionId"] as? String
                        if (sessionId != null && sessionId.isNotEmpty()) {
                            Log.d("PlaybackSessionNamespace", "Found sessionId in sessionStatus: $sessionId")
                            return mapOf(
                                "sessionId" to sessionId,
                                "sessionState" to (bodyMap["sessionState"] ?: ""),
                                "playerId" to playerId,
                                "groupId" to groupId
                            )
                        }
                    }
                }
            }
        }
        
        // If we get here, we couldn't find a sessionId
        Log.w("PlaybackSessionNamespace", "No sessionId found in response: $response")
        throw SonosError.SessionError(
            "Session creation succeeded but no sessionId was returned",
            "NO_SESSION_ID_IN_RESPONSE",
            sessionId = null,
            cause = null
        )
    }
    
    /**
     * Load a stream URL into the playback session.
     * This is what we use for Navidrome streaming!
     * 
     * @param groupId The group ID to load the stream for
     * @param sessionId The session ID from createSession
     * @param streamUrl The URL to stream (from Navidrome)
     * @param playOnCompletion Whether to start playback immediately
     * @param stationMetadata Optional metadata for the stream
     */
    suspend fun loadStreamUrl(
        groupId: String,
        sessionId: String?,
        streamUrl: String,
        playOnCompletion: Boolean = true,
        stationMetadata: Map<String, Any>? = null,
        itemId: String? = null
    ) {
        val options = mutableMapOf<String, Any>(
            "streamUrl" to streamUrl,
            "playOnCompletion" to playOnCompletion
        )
        stationMetadata?.let { options["stationMetadata"] = it }
        itemId?.let { options["itemId"] = it }
        
        Log.d("PlaybackSessionNamespace", "loadStreamUrl with groupId=$groupId, sessionId=$sessionId, options=$options")
        
        // For loadStreamUrl, both groupId and sessionId go in the header
        // sessionId is passed as extraHeaders, not in options
        val extraHeaders = mutableMapOf<String, Any>()
        sessionId?.let { extraHeaders["sessionId"] = it }
        
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.PlaybackSession,
            command = "loadStreamUrl",
            objectId = groupId,  // groupId in header
            options = options,
            extraHeaders = extraHeaders  // sessionId in header
        )
    }
    
    /**
     * Suspend (stop) a playback session.
     * 
     * @param sessionId The session ID to suspend
     */
    suspend fun suspend(sessionId: String) {
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.PlaybackSession,
            command = "suspend",
            objectId = sessionId
        )
    }
    
    /**
     * End/destroy a playback session.
     * This completely terminates the session and releases all resources.
     * Use this when ungrouping or disconnecting to ensure clean cleanup.
     * 
     * @param sessionId The session ID to end
     */
    suspend fun endSession(sessionId: String) {
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.PlaybackSession,
            command = "endSession",
            objectId = sessionId
        )
    }

    /**
     * Change to a different playback session.
     * 
     * @param sessionId The current session ID
     * @param newSessionId The new session ID to switch to
     */
    suspend fun changeSession(sessionId: String, newSessionId: String) {
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.PlaybackSession,
            command = "changeSession",
            objectId = sessionId,
            options = mapOf("newSessionId" to newSessionId)
        )
    }
    
    /**
     * Load a cloud queue (for services like Spotify, Apple Music).
     * Not needed for Navidrome, but included for completeness.
     */
    suspend fun loadCloudQueue(
        sessionId: String,
        queueBaseUrl: String,
        httpAuthorization: String? = null,
        useHttpAuthorizationForMedia: Boolean? = null,
        itemId: String? = null,
        queueVersion: String? = null,
        positionMillis: Long? = null,
        playOnCompletion: Boolean = true,
        trackMetadata: Map<String, Any>? = null
    ) {
        val options = mutableMapOf<String, Any>(
            "queueBaseUrl" to queueBaseUrl,
            "playOnCompletion" to playOnCompletion
        )
        httpAuthorization?.let { options["httpAuthorization"] = it }
        useHttpAuthorizationForMedia?.let { options["useHttpAuthorizationForMedia"] = it }
        itemId?.let { options["itemId"] = it }
        queueVersion?.let { options["queueVersion"] = it }
        positionMillis?.let { options["positionMillis"] = it }
        trackMetadata?.let { options["trackMetadata"] = it }
        
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.PlaybackSession,
            command = "loadCloudQueue",
            objectId = sessionId,
            options = options
        )
    }

    /**
     * Load a list of tracks into the playback session.
     * This is used for loading entire playlists/queues in one call.
     * Each track is represented by a track descriptor with URI and metadata.
     *
     * @param groupId The group ID to load the tracks for
     * @param sessionId The session ID from createSession
     * @param trackList List of track descriptors (maps with index, trackUri, metadata)
     * @param playOnCompletion Whether to start playback immediately
     * @param startIndex Optional index to start playback from
     */
    suspend fun loadTrackList(
        groupId: String,
        sessionId: String?,
        trackList: List<Map<String, Any>>,
        playOnCompletion: Boolean = true,
        startIndex: Int? = null
    ) {
        val options = mutableMapOf<String, Any>(
            "trackList" to trackList,
            "playOnCompletion" to playOnCompletion
        )
        startIndex?.let { options["startIndex"] = it }
        
        Log.d("PlaybackSessionNamespace", "loadTrackList with groupId=$groupId, sessionId=$sessionId, trackCount=${trackList.size}")
        
        // For loadTrackList, we need both groupId and sessionId in the header
        // The getObjectIdKey for PlaybackSession.loadTrackList returns "sessionId" as key
        // So we pass sessionId as objectId, and add groupId as extra header
        val extraHeaders = mutableMapOf<String, Any>()
        extraHeaders["groupId"] = groupId
        
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.PlaybackSession,
            command = "loadTrackList",
            objectId = sessionId,  // This will be sent as "sessionId" in header
            options = options,
            extraHeaders = extraHeaders  // groupId will be added here
        )
    }

    /**
     * Seek to a position within a playback session.
     * This is used for seeking within streams loaded via loadStreamUrl or loadTrackList.
     * 
     * @param sessionId The session ID
     * @param positionMillis Position in milliseconds
     * @param itemId Optional item ID to seek within
     */
    suspend fun seek(
        sessionId: String,
        positionMillis: Long,
        itemId: String? = null
    ) {
        val options = mutableMapOf<String, Any>("positionMillis" to positionMillis)
        itemId?.let { options["itemId"] = it }
        
        Log.d("PlaybackSessionNamespace", "seek to position $positionMillis for session $sessionId")
        
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.PlaybackSession,
            command = "seek",
            objectId = sessionId,
            options = options
        )
    }

    /**
     * Seek relative to current position within a playback session.
     * 
     * @param sessionId The session ID
     * @param deltaMillis Change in milliseconds (positive or negative)
     * @param itemId Optional item ID to seek within
     */
    suspend fun seekRelative(
        sessionId: String,
        deltaMillis: Long,
        itemId: String? = null
    ) {
        val options = mutableMapOf<String, Any>("deltaMillis" to deltaMillis)
        itemId?.let { options["itemId"] = it }
        
        api.sendCommand(
            namespace = SonosWebSocketApi.Namespace.PlaybackSession,
            command = "seekRelative",
            objectId = sessionId,
            options = options
        )
    }
}
