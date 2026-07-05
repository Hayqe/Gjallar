package com.cappielloantonio.tempo.sonos.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.cappielloantonio.tempo.sonos.api.GroupVolumeNamespace
import com.cappielloantonio.tempo.sonos.api.PlaybackNamespace
import com.cappielloantonio.tempo.sonos.api.PlaybackSessionNamespace
import com.cappielloantonio.tempo.sonos.api.SonosError.ApiError
import com.cappielloantonio.tempo.sonos.api.SonosError
import com.cappielloantonio.tempo.sonos.api.SonosWebSocketApi
import com.cappielloantonio.tempo.sonos.discovery.SonosDiscovery
import com.cappielloantonio.tempo.sonos.models.SonosDevice
import com.cappielloantonio.tempo.sonos.models.SonosGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Proxy class that wraps an ExoPlayer and intercepts playback events
 * to forward them to Sonos speakers via WebSocket API.
 * 
 * The ExoPlayer itself does NOT play audio - it's only used for event handling.
 * The actual audio streaming happens on the Sonos speakers themselves.
 * 
 * This follows the same pattern as Media3's CastPlayer, which intercepts
 * ExoPlayer events and forwards them to Chromecast devices.
 */
class SonosExoPlayerProxy(
    private val exoPlayer: ExoPlayer,
    private val group: SonosGroup,
    private val device: SonosDevice,
    private val discovery: SonosDiscovery,
    private val playbackApi: PlaybackNamespace,
    private val playbackSessionApi: PlaybackSessionNamespace,
    private val groupVolumeApi: GroupVolumeNamespace,
    private val wsApi: SonosWebSocketApi
) {
    
    companion object {
        private const val TAG = "SonosExoPlayerProxy"
    }
    
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Main + job)
    private val mutex = Mutex()
    private var sessionId: String? = null
    private var currentMediaItem: MediaItem? = null
    private var currentItemId: String? = null
    private var isLoadingMedia = false
    private var queueLoaded = false
    private var isSyncingState = false
    private var isMediaLoaded = false
    
    /**
     * Current state of the Sonos proxy.
     */
    enum class State {
        IDLE,
        CONNECTING,
        CONNECTED,
        LOADING_MEDIA,
        PLAYING,
        PAUSED,
        STOPPED,
        ERROR
    }
    
    private var currentState: State = State.IDLE
    
    init {
        // Disable audio playback - audio should only play on Sonos speakers
        // Must be done on main thread as ExoPlayer requires main thread access
        exoPlayer.setVolume(0f)
        // Start with playWhenReady = false to prevent premature play commands to Sonos
        exoPlayer.playWhenReady = false
        // DO NOT setup Player.Listener - we'll handle commands explicitly
        // to avoid interference from Sonos app's own MediaSession
    }
    
    /**
     * Load a media item into Sonos.
     * This is the CRITICAL method for Navidrome streaming!
     * 
     * @param mediaItem The media item to load
     */
    fun loadMediaItem(mediaItem: MediaItem) {
        currentMediaItem = mediaItem
        updateState(State.LOADING_MEDIA)
        
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    loadMediaItemInternal(mediaItem)
                } catch (e: SonosError) {
                    Log.e(TAG, "sonos: error loading media item: ${e.message}", e)
                    updateState(State.ERROR)
                    // Reset queueLoaded flag so we can try again
                    queueLoaded = false
                } catch (e: Exception) {
                    Log.e(TAG, "sonos: Unexpected error loading media item", e)
                    updateState(State.ERROR)
                    queueLoaded = false
                } finally {
                    isLoadingMedia = false
                    isSyncingState = false
                }
            }
        }
    }
    
    /**
     * Internal method to load a media item into Sonos.
     * Handles both queue loading and individual track loading.
     * 
     * @param mediaItem The media item to load
     */
    @Throws(SonosError::class)
    private suspend fun loadMediaItemInternal(mediaItem: MediaItem) {
        // Get the current active group and coordinator
        val (activeGroupId, activeCoordinatorId) = discovery.getActiveGroupAndCoordinator(group, device)

        // Get queue info on main thread
        val queueInfo = withContext(Dispatchers.Main) { getQueueInfo(exoPlayer) }

        // Try to load entire queue first if not already loaded
        if (!queueLoaded && queueInfo.trackDescriptors.isNotEmpty()) {
            Log.d(TAG, "Attempting to load entire queue to Sonos")
            try {
                loadQueue(queueInfo, activeGroupId, activeCoordinatorId)
                Log.d(TAG, "Queue loaded successfully, skipping individual track load")
                isMediaLoaded = true
                updateState(State.CONNECTED)
                return
            } catch (e: SonosError) {
                Log.w(TAG, "Queue loading failed: ${e.message}, will load individual track")
            } catch (e: Exception) {
                Log.w(TAG, "Queue loading failed: ${e.message}, will load individual track")
            }
        }

        // Load individual track
        loadIndividualTrack(mediaItem, activeGroupId, activeCoordinatorId)
    }
    
    /**
     * Load an individual track into Sonos.
     * Creates a new session for the track to avoid ERROR_INVALID_OBJECT_ID errors.
     * 
     * @param mediaItem The media item to load
     * @param activeGroupId The active group ID
     * @param activeCoordinatorId The active coordinator ID
     */
    @Throws(SonosError::class)
    private suspend fun loadIndividualTrack(
        mediaItem: MediaItem,
        activeGroupId: String,
        activeCoordinatorId: String
    ) {
        Log.i(TAG, "SONOS_LOAD: Starting loadIndividualTrack for ${mediaItem.mediaId}")
        
        // Suspend old session if it exists
        suspendOldSession()
        
        // Create a new session for this track
        Log.i(TAG, "SONOS_LOAD: Creating session for coordinator=$activeCoordinatorId, group=$activeGroupId")
        val sessionInfo = createNewSession(activeCoordinatorId, activeGroupId, mediaItem.mediaId)
        sessionId = sessionInfo["sessionId"] as? String
        
        if (sessionId == null) {
            throw SonosError.SessionError(
                "Session creation succeeded but no sessionId was returned",
                "NO_SESSION_ID_IN_RESPONSE",
                sessionId = null,
                cause = null
            )
        }
        
        Log.i(TAG, "SONOS_LOAD: Created session: $sessionId for track ${mediaItem.mediaId}")
        Log.d(TAG, "Created session: $sessionId for track ${mediaItem.mediaId}")
        
        // Extract stream URL from media item
        val streamUrl = getStreamUrl(mediaItem)
            ?: throw SonosError.PlaybackError("Cannot extract stream URL from media item: ${mediaItem.mediaId}")
        
        Log.i(TAG, "SONOS_LOAD: Stream URL: $streamUrl")
        Log.d(TAG, "Loading stream URL: $streamUrl for track ${mediaItem.mediaId}")
        
        // Create metadata for the stream
        val metadata = createMetadata(mediaItem, forStreamUrl = true)
        
        // Track current item ID for seek operations
        currentItemId = mediaItem.mediaId
        
        // Load the stream into Sonos
        Log.i(TAG, "SONOS_LOAD: Calling loadStreamUrl with sessionId=$sessionId, groupId=$activeGroupId")
        Log.d(TAG, "Calling loadStreamUrl with sessionId=$sessionId, groupId=$activeGroupId")
        playbackSessionApi.loadStreamUrl(
            groupId = activeGroupId,
            sessionId = sessionId,
            streamUrl = streamUrl,
            playOnCompletion = true,
            stationMetadata = metadata,
            itemId = "1"
        )
        
        Log.i(TAG, "SONOS_LOAD: loadStreamUrl completed successfully for track ${mediaItem.mediaId}")
        Log.d(TAG, "loadStreamUrl completed successfully for track ${mediaItem.mediaId}")
        
        // Explicit play command after loadStreamUrl to ensure playback starts
        // Some Sonos devices don't respect playOnCompletion flag
        kotlinx.coroutines.delay(2000)
        try {
            playbackApi.play(activeGroupId)
            Log.d(TAG, "Explicit play after loadStreamUrl succeeded")
        } catch (e: Exception) {
            Log.w(TAG, "Explicit play after loadStreamUrl failed: ${e.message}")
        }
        
        // Mark that media has been loaded
        isMediaLoaded = true
        updateState(State.CONNECTED)
        
        // Sync ExoPlayer state
        syncExoPlayerState(mediaItem)
    }
    
    /**
     * Suspend the old session if one exists.
     */
    private suspend fun suspendOldSession() {
        sessionId?.let { oldSessionId ->
            try {
                Log.d(TAG, "Suspending old session: $oldSessionId")
                playbackSessionApi.suspend(oldSessionId)
            } catch (e: SonosError.ApiError) {
                Log.w(TAG, "sonos: Error suspending old session: ${e.message}")
                // Non-critical error, continue
            } catch (e: SonosError) {
                Log.w(TAG, "sonos: Error suspending old session: ${e.message}")
                // Non-critical error, continue
            } catch (e: Exception) {
                Log.w(TAG, "sonos: Unexpected error suspending old session: ${e.message}")
                // Non-critical error, continue
            }
        }
    }
    
    /**
     * Create a new session, trying multiple players if the coordinator fails.
     * 
     * @param coordinatorId The coordinator ID to try first
     * @param groupId The group ID
     * @param trackId The track ID for logging
     * @return Session info map
     */
    @Throws(SonosError.SessionError::class)
    private suspend fun createNewSession(
        coordinatorId: String,
        groupId: String,
        trackId: String
    ): Map<String, Any> {
        Log.d(TAG, "Creating session for track $trackId for group $groupId on coordinator $coordinatorId")
        
        return try {
            playbackSessionApi.createSession(coordinatorId, groupId)
        } catch (e: SonosError.SessionError) {
            Log.w(TAG, "SonosError creating session on coordinator $coordinatorId: ${e.message}")
            // Try other players in the group
            tryOtherPlayersInGroup(e, groupId, coordinatorId)
        } catch (e: SonosError.ApiError) {
            Log.w(TAG, "SonosError.ApiError creating session on coordinator $coordinatorId: ${e.message}")
            // Convert to SonosError and try other players
            tryOtherPlayersInGroup(e, groupId, coordinatorId)
        }
    }
    
    /**
     * Sync the ExoPlayer state to reflect the Sonos playback state.
     */
    private fun syncExoPlayerState(mediaItem: MediaItem) {
        scope.launch(Dispatchers.Main) {
            try {
                val minimalMediaItem = MediaItem.Builder()
                    .setMediaId(mediaItem.mediaId)
                    .setMediaMetadata(mediaItem.mediaMetadata)
                    .setUri(mediaItem.requestMetadata.mediaUri)
                    .build()
                exoPlayer.setMediaItem(minimalMediaItem)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                Log.d(TAG, "Synced ExoPlayer state for track ${mediaItem.mediaId}")
            } catch (e: Exception) {
                Log.e(TAG, "sonos: Error syncing ExoPlayer state", e)
            }
        }
    }
    
    /**
     * Update the current state of the proxy.
     */
    private fun updateState(newState: State) {
        Log.d(TAG, "State transition: ${currentState} -> $newState")
        currentState = newState
    }
    
    /**
     * Try other players in the group when coordinator fails.
     * This handles GROUP_STATUS_GONE errors by trying each player until one works.
     * 
     * @param error The error from trying the coordinator
     * @param activeGroupId The active group ID to create the session for
     * @param triedCoordinatorId The coordinator ID we already tried
     * @return Session info map with playerId
     * @throws SonosError.SessionError if all players fail
     */
    @Throws(SonosError.SessionError::class)
    private suspend fun tryOtherPlayersInGroup(
        error: SonosError,
        activeGroupId: String,
        triedCoordinatorId: String
    ): Map<String, Any> {
        // Check if this is a coordinator-related error
        val isCoordinatorError = error.errorCode == "GROUP_STATUS_GONE" ||
            error.errorCode == "groupCoordinatorChanged" ||
            error.message.contains("GROUP_STATUS_GONE") ||
            error.message.contains("groupCoordinatorChanged")
        
        if (!isCoordinatorError) {
            // Not a coordinator error, re-throw
            if (error is SonosError.SessionError) throw error
            throw SonosError.SessionError(
                "Failed to create session: ${error.message}",
                error.errorCode,
                cause = error
            )
        }
        
        Log.w(TAG, "Coordinator is gone/changed, trying other players in group ${activeGroupId}")
        
        // Refresh group info to get the latest player list for the active group
        val refreshedGroups = discovery.refreshGroups(device)
        val refreshedGroup = refreshedGroups.find { it.id == activeGroupId }
        
        // FIRST: Try the updated coordinator from refreshed group info
        if (refreshedGroup != null && refreshedGroup.coordinatorId.isNotEmpty()) {
            val updatedCoordinatorId = refreshedGroup.coordinatorId
            if (updatedCoordinatorId != triedCoordinatorId) {
                Log.d(TAG, "sonos: Retrying session creation with updated coordinator: $updatedCoordinatorId")
                try {
                    val sessionInfo = playbackSessionApi.createSession(updatedCoordinatorId, activeGroupId)
                    Log.d(TAG, "Successfully created session on updated coordinator $updatedCoordinatorId")
                    return sessionInfo.plus("playerId" to updatedCoordinatorId)
                } catch (e: SonosError.ApiError) {
                    Log.w(TAG, "sonos: Failed to create session on updated coordinator $updatedCoordinatorId: ${e.message}")
                    // Continue to try other players
                } catch (e: SonosError.SessionError) {
                    Log.w(TAG, "sonos: SonosError creating session on updated coordinator $updatedCoordinatorId: ${e.message}")
                    // Continue to try other players
                } catch (e: Exception) {
                    Log.w(TAG, "sonos: Unexpected error creating session on updated coordinator $updatedCoordinatorId: ${e.message}")
                    // Continue to try other players
                }
            }
        }
        
        // Use refreshed player list, or fall back to cached group
        val playerIds = refreshedGroup?.playerIds ?: group.playerIds
        
        // SECOND: Try each player in the group (excluding the one we already tried)
        for (playerId in playerIds) {
            if (playerId == triedCoordinatorId) continue
            
            Log.d(TAG, "Trying player $playerId as fallback for session creation")
            try {
                val sessionInfo = playbackSessionApi.createSession(playerId, activeGroupId)
                Log.d(TAG, "Successfully created session on player $playerId")
                return sessionInfo.plus("playerId" to playerId)
            } catch (e: SonosError.ApiError) {
                Log.w(TAG, "Failed to create session on player $playerId: ${e.message}")
                continue
            } catch (e: SonosError.SessionError) {
                Log.w(TAG, "SonosError creating session on player $playerId: ${e.message}")
                continue
            } catch (e: Exception) {
                Log.w(TAG, "Unexpected error creating session on player $playerId: ${e.message}")
                continue
            }
        }
        
        // If all attempts failed, throw an error
        throw SonosError.SessionError(
            "Failed to create session on any player in group $activeGroupId",
            "NO_PLAYERS_AVAILABLE",
            groupId = activeGroupId
        )
    }
    
    /**
     * Extract the stream URL from a MediaItem.
     * For Sonos compatibility, ensures Navidrome streams are transcoded to MP3.
     * 
     * @param mediaItem The media item
     * @return The stream URL, or null if not supported
     */
    private fun getStreamUrl(mediaItem: MediaItem): String? {
        val mediaUri = mediaItem.requestMetadata.mediaUri
        
        if (mediaUri == null) {
            Log.w(TAG, "sonos: mediaUri is null")
            return null
        }
        
        // Navidrome (and Subsonic-compatible servers) provide direct HTTP URLs
        if (mediaUri.scheme == "http" || mediaUri.scheme == "https") {
            return ensureSonosCompatibleUrl(mediaUri.toString())
        }
        
        Log.w(TAG, "Unsupported URI scheme: ${mediaUri.scheme}")
        return null
    }

    /**
     * Ensure the stream URL is compatible with Sonos speakers.
     * Sonos has limited support for FLAC over HTTP streaming, so we force MP3 transcoding.
     * 
     * @param urlString The original URL
     * @return URL with transcoding parameters added for Sonos compatibility
     */
    private fun ensureSonosCompatibleUrl(urlString: String): String {
        Log.d(TAG, "sonos: Processing URL for Sonos compatibility: $urlString")

        val originalUri = android.net.Uri.parse(urlString)
        val builder = originalUri.buildUpon().clearQuery()

        for (key in originalUri.queryParameterNames) {
            if (key == "format" || key == "maxBitRate") continue
            for (value in originalUri.getQueryParameters(key)) {
                builder.appendQueryParameter(key, value)
            }
        }

        builder.appendQueryParameter("format", "mp3")
        builder.appendQueryParameter("maxBitRate", "320")

        val result = builder.build().toString()
        Log.d(TAG, "sonos: Sonos-compatible URL: $result")
        return result
    }
    
    /**
     * Create metadata for Sonos from a MediaItem.
     * 
     * For loadStreamUrl, Sonos expects stationMetadata which is a simpler format.
     * For loadTrackList, each track's metadata should be a Track object.
     * 
     * According to Sonos API documentation, stationMetadata can be a simpler object
     * with title, artist, album, albumArtUri, durationMillis fields.
     * 
     * @param mediaItem The media item
     * @param forStreamUrl If true, create simple format for stationMetadata; if false, create Track format
     * @return Metadata map for Sonos in proper format
     */
    private fun createMetadata(mediaItem: MediaItem, forStreamUrl: Boolean = false): Map<String, Any> {
        val metadata = mediaItem.mediaMetadata
        val mediaId = mediaItem.mediaId
        val title = metadata.title?.toString() ?: "Unknown"
        val artist = metadata.artist?.toString() ?: "Unknown"
        val album = metadata.albumTitle?.toString() ?: "Unknown"
        val albumArtUri = metadata.artworkUri?.toString()
        val durationMillis = metadata.durationMs ?: 0L
        
        if (forStreamUrl) {
            // Use structured Track format for better Sonos compatibility
            // Some Sonos devices expect _objectType fields even for stationMetadata
            // This format matches what loadTrackList expects and works across more devices
            val structuredMetadata = mutableMapOf<String, Any>(
                "_objectType" to "track",
                "name" to title,
                "artist" to mapOf(
                    "_objectType" to "artist",
                    "name" to artist
                ),
                "album" to mapOf(
                    "_objectType" to "album",
                    "name" to album
                ),
                "durationMillis" to durationMillis
            )
            
            // Skip images for stream URL — Sonos can't resolve content:// URIs
            if (!albumArtUri.isNullOrEmpty() && !forStreamUrl) {
                structuredMetadata["images"] = listOf(
                    mapOf(
                        "_objectType" to "image",
                        "url" to albumArtUri
                    )
                )
            }
            
            return structuredMetadata
        } else {
            // For loadTrackList, create Track format with proper nested structure
            val trackMetadata = mutableMapOf<String, Any>(
                "_objectType" to "track",
                "name" to title,
                "artist" to mapOf(
                    "_objectType" to "artist",
                    "name" to artist
                ),
                "album" to mapOf(
                    "_objectType" to "album",
                    "name" to album
                ),
                "durationMillis" to durationMillis,
                "id" to mapOf(
                    "_objectType" to "id",
                    "objectId" to mediaId,
                    "serviceId" to "com.cappielloantonio.tempo"
                )
            )
            
            // Add images if available
            if (!albumArtUri.isNullOrEmpty()) {
                trackMetadata["images"] = listOf(
                    mapOf(
                        "_objectType" to "image",
                        "url" to albumArtUri
                    )
                )
            }
            
            return trackMetadata
        }
    }

    /**
     * Create a track descriptor for Sonos loadTrackList API.
     * 
     * @param mediaItem The media item to convert
     * @param index The index of this track in the queue
     * @return Track descriptor map for Sonos
     */
    private fun createTrackDescriptor(mediaItem: MediaItem, index: Int): Map<String, Any> {
        val streamUrl = getStreamUrl(mediaItem) ?: ""
        
        return mapOf(
            "index" to index,
            "trackUri" to streamUrl,
            // For loadTrackList, metadata should be Track format (not Container)
            "metadata" to createMetadata(mediaItem, forStreamUrl = false)
        )
    }

    /**
     * Queue information extracted from ExoPlayer on main thread.
     */
    private data class QueueInfo(
        val trackDescriptors: List<Map<String, Any>>,
        val startIndex: Int?
    )
    
    /**
     * Get queue information from ExoPlayer.
     * Must be called from main thread.
     * 
     * @param player The ExoPlayer containing the queue
     * @return QueueInfo with track descriptors and start index
     */
    private fun getQueueInfo(player: Player): QueueInfo {
        val descriptors = mutableListOf<Map<String, Any>>()
        val itemCount = player.mediaItemCount
        
        for (i in 0 until itemCount) {
            val mediaItem = player.getMediaItemAt(i)
            descriptors.add(createTrackDescriptor(mediaItem, i))
        }
        
        val startIndex = player.currentMediaItemIndex.takeIf { it != C.INDEX_UNSET }
        
        return QueueInfo(descriptors, startIndex)
    }
    
    /**
     * Create track descriptors for all media items in the player's queue.
     * 
     * @param player The ExoPlayer containing the queue
     * @return List of track descriptors for Sonos loadTrackList
     */
    private fun createTrackDescriptors(player: Player): List<Map<String, Any>> {
        val descriptors = mutableListOf<Map<String, Any>>()
        val itemCount = player.mediaItemCount
        
        for (i in 0 until itemCount) {
            val mediaItem = player.getMediaItemAt(i)
            descriptors.add(createTrackDescriptor(mediaItem, i))
        }
        
        return descriptors
    }

    /**
     * Load the entire queue into Sonos using loadTrackList API.
     * Falls back to individual track loading if loadTrackList fails.
     * 
     * @param queueInfo Queue information extracted from ExoPlayer
     * @param activeGroupId The active group ID
     * @param activeCoordinatorId The active coordinator ID
     */
    private suspend fun loadQueue(queueInfo: QueueInfo, activeGroupId: String, activeCoordinatorId: String) {
        if (queueLoaded) {
            Log.d(TAG, "Queue already loaded, skipping")
            return
        }
        
        val trackDescriptors = queueInfo.trackDescriptors
        
        if (trackDescriptors.isEmpty()) {
            Log.w(TAG, "No tracks in queue to load")
            return
        }
        
        Log.d(TAG, "Loading queue with ${trackDescriptors.size} tracks to Sonos group: $activeGroupId")
        
        // Ensure we have a session
        if (sessionId == null) {
            val sessionInfo = try {
                playbackSessionApi.createSession(activeCoordinatorId, activeGroupId)
            } catch (e: SonosError.ApiError) {
                tryOtherPlayersInGroup(e, activeGroupId, activeCoordinatorId)
            } catch (e: SonosError.SessionError) {
                tryOtherPlayersInGroup(e, activeGroupId, activeCoordinatorId)
            }
            sessionId = sessionInfo["sessionId"] as? String
            Log.d(TAG, "Created session for queue: $sessionId")
        }
        
        // loadTrackList requires a valid sessionId
        if (sessionId == null) {
            Log.e(TAG, "sonos: Cannot load queue: no sessionId available")
            queueLoaded = false
            return
        }
        
        // Try loadTrackList first
        playbackSessionApi.loadTrackList(
            groupId = activeGroupId,
            sessionId = sessionId,
            trackList = trackDescriptors,
            playOnCompletion = true,
            startIndex = queueInfo.startIndex
        )
        
        // Explicit play command after loadTrackList
        kotlinx.coroutines.delay(2000)
        try {
            playbackApi.play(activeGroupId)
        } catch (e: Exception) {
            Log.w(TAG, "Explicit play after loadTrackList failed: ${e.message}")
        }
        
        // Sync ExoPlayer state to reflect playback
        // This prevents pause between tracks
        
        // Mark that media has been loaded
        isMediaLoaded = true
        queueLoaded = true
        Log.d(TAG, "Successfully loaded queue of ${trackDescriptors.size} tracks to Sonos")
    }

    /**
     * Send play command to Sonos.
     * Only sends command if media has been loaded to prevent errors.
     */
    fun play() {
        if (!isMediaLoaded) {
            Log.d(TAG, "Skipping play command - no media loaded to Sonos yet")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                // Get the current active group ID to handle group changes
                val activeGroupId = discovery.getActiveGroupAndCoordinator(group, device).first
                Log.d(TAG, "Sending play command to Sonos group: ${activeGroupId}")
                playbackApi.play(activeGroupId)
            } catch (e: Exception) {
                Log.e(TAG, "sonos: Error sending play command", e)
            }
        }
    }
    
    /**
     * Send pause command to Sonos.
     * Only sends command if media has been loaded to prevent errors.
     */
    fun pause() {
        if (!isMediaLoaded) {
            Log.d(TAG, "Skipping pause command - no media loaded to Sonos yet")
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                // Get the current active group ID to handle group changes
                val activeGroupId = discovery.getActiveGroupAndCoordinator(group, device).first
                Log.d(TAG, "Sending pause command to Sonos group: ${activeGroupId}")
                playbackApi.pause(activeGroupId)
            } catch (e: Exception) {
                Log.e(TAG, "sonos: Error sending pause command", e)
            }
        }
    }
    
    /**
     * Send stop command to Sonos.
     */
    fun stop() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                try {
                    // Get the current active group ID to handle group changes
                    val activeGroupId = discovery.getActiveGroupAndCoordinator(group, device).first
                    Log.d(TAG, "Sending stop command to Sonos group: ${activeGroupId}")
                    playbackApi.stop(activeGroupId)
                    sessionId?.let { sessionId ->
                        playbackSessionApi.suspend(sessionId)
                        this@SonosExoPlayerProxy.sessionId = null
                    }
                    // Reset state when stopped
                    queueLoaded = false
                    isMediaLoaded = false
                } catch (e: Exception) {
                    Log.e(TAG, "sonos: Error sending stop command", e)
                }
            }
        }
    }
    
    /**
     * Send seek command to Sonos.
     * Uses playbackSession API for session-based playback (streaming).
     * 
     * @param positionMs Position in milliseconds
     */
    fun seekTo(positionMs: Long) {
        scope.launch(Dispatchers.IO) {
            try {
                // Get the current active group ID to handle group changes
                val activeGroupId = discovery.getActiveGroupAndCoordinator(group, device).first
                // Use local variables to avoid smart cast issues with mutable properties
                val currentSessionId = sessionId
                val currentItemId = this@SonosExoPlayerProxy.currentItemId
                Log.i(TAG, "SONOS_SEEK_START: positionMs=$positionMs, groupId=$activeGroupId, sessionId=$currentSessionId, itemId=$currentItemId")
                Log.d(TAG, "Sending seek command to position: $positionMs, groupId: $activeGroupId, sessionId: $currentSessionId, itemId: $currentItemId")
                
                // Use session-based seek for streaming playback
                // For streams loaded via loadStreamUrl, use currentItemId if available
                // If currentItemId is null/empty, don't pass it to avoid ERROR_INVALID_PARAMETER
                if (currentSessionId != null) {
                    Log.i(TAG, "SONOS_SEEK: Using playbackSessionApi.seek (session-based)")
                    playbackSessionApi.seek(
                        sessionId = currentSessionId,
                        positionMillis = positionMs,
                        itemId = "1"
                    )
                } else {
                    // Fallback to group-based seek if no session
                    Log.i(TAG, "SONOS_SEEK: Using playbackApi.seek (group-based fallback)")
                    playbackApi.seek(
                        groupId = activeGroupId,
                        positionMillis = positionMs,
                        itemId = currentItemId
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "sonos: Error sending seek command", e)
            }
        }
    }
    
    /**
     * Set volume on Sonos.
     * 
     * @param volume Volume (0.0 to 1.0)
     */
    fun setVolume(volume: Float) {
        if (volume <= 0f) return
        // Sonos volume is 0-100, convert from 0.0-1.0
        val sonosVolume = (volume * 100).toInt().coerceIn(0, 100)
        scope.launch(Dispatchers.IO) {
            try {
                // Get the current active group ID to handle group changes
                val activeGroupId = discovery.getActiveGroupAndCoordinator(group, device).first
                Log.d(TAG, "Setting volume to $sonosVolume")
                groupVolumeApi.setVolume(activeGroupId, sonosVolume)
            } catch (e: Exception) {
                Log.e(TAG, "sonos: Error setting volume", e)
            }
        }
    }
    
    /**
     * Set shuffle mode.
     * 
     * @param shuffle Whether to enable shuffle
     */
    fun setShuffle(shuffle: Boolean) {
        scope.launch(Dispatchers.IO) {
            try {
                // Get the current active group ID to handle group changes
                val activeGroupId = discovery.getActiveGroupAndCoordinator(group, device).first
                Log.d(TAG, "Setting shuffle to $shuffle")
                playbackApi.setPlayModes(activeGroupId, shuffle = shuffle)
            } catch (e: Exception) {
                Log.e(TAG, "sonos: Error setting shuffle", e)
            }
        }
    }
    
    /**
     * Set repeat mode.
     * 
     * @param repeatMode Media3 repeat mode constant
     */
    fun setRepeatMode(repeatMode: Int) {
        val repeat = repeatMode == Player.REPEAT_MODE_ONE || repeatMode == Player.REPEAT_MODE_ALL
        val repeatOne = repeatMode == Player.REPEAT_MODE_ONE
        
        scope.launch(Dispatchers.IO) {
            try {
                // Get the current active group ID to handle group changes
                val activeGroupId = discovery.getActiveGroupAndCoordinator(group, device).first
                Log.d(TAG, "Setting repeat mode: repeat=$repeat, repeatOne=$repeatOne")
                playbackApi.setPlayModes(activeGroupId, repeat = repeat, repeatOne = repeatOne)
            } catch (e: Exception) {
                Log.e(TAG, "sonos: Error setting repeat mode", e)
            }
        }
    }
    
    /**
     * Skip to next track.
     */
    fun skipToNextTrack() {
        scope.launch(Dispatchers.IO) {
            try {
                // Get the current active group ID to handle group changes
                val activeGroupId = discovery.getActiveGroupAndCoordinator(group, device).first
                Log.d(TAG, "Skipping to next track")
                playbackApi.skipToNextTrack(activeGroupId)
            } catch (e: Exception) {
                Log.e(TAG, "sonos: Error skipping to next track", e)
            }
        }
    }
    
    /**
     * Skip to previous track.
     */
    fun skipToPreviousTrack() {
        scope.launch(Dispatchers.IO) {
            try {
                // Get the current active group ID to handle group changes
                val activeGroupId = discovery.getActiveGroupAndCoordinator(group, device).first
                Log.d(TAG, "Skipping to previous track")
                playbackApi.skipToPreviousTrack(activeGroupId)
            } catch (e: Exception) {
                Log.e(TAG, "sonos: Error skipping to previous track", e)
            }
        }
    }
    
    /**
     * Release resources.
     */
    fun release() {
        // Launch stop on IO thread first, without holding the mutex
        // This ensures stop commands are sent before we cancel everything
        scope.launch(Dispatchers.IO) {
            try {
                stop()
            } catch (e: Exception) {
                Log.w(TAG, "Error during stop in release", e)
            }
        }
        
        // Then cancel the job and reset state
        job.cancel()
        queueLoaded = false
        isMediaLoaded = false
        sessionId = null
        currentMediaItem = null
        currentItemId = null
    }
    
    /**
     * Get the wrapped ExoPlayer.
     * This is used by Media3 to integrate with the session.
     * 
     * @return The underlying ExoPlayer instance
     */
    fun getWrappedPlayer(): Player = exoPlayer
    
    /**
     * Get the Sonos group this proxy is controlling.
     */
    fun getGroup(): SonosGroup = group
    
    /**
     * Get the Sonos device this proxy is using.
     */
    fun getDevice(): SonosDevice = device
    
    /**
     * Remove this device from its current group (ontkoppelen/ungroup).
     * This stops the device from being grouped with other Sonos speakers.
     * 
     * @return Result from the API
     */
    suspend fun ungroupDevice(): Map<String, Any> {
        val cachedGroups = discovery.groups.value
        val groupsWithDevice = cachedGroups.filter { it.playerIds.contains(device.id) }
        var groupsModified = 0

        for (group in groupsWithDevice) {
            try {
                wsApi.sendCommand(
                    namespace = SonosWebSocketApi.Namespace.Groups,
                    command = "modifyGroupMembers",
                    objectId = group.id,
                    options = mapOf("playerIdsToRemove" to listOf(device.id))
                )
                groupsModified++
            } catch (e: Exception) {
                Log.e(TAG, "Error removing player ${device.id} from group ${group.id}", e)
            }
        }

        return mapOf("ungroupedPlayer" to device.id, "groupsModified" to groupsModified)
    }
}
