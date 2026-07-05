package com.cappielloantonio.tempo.sonos.api

import android.util.Log
import com.cappielloantonio.tempo.sonos.SonosConstants
import com.cappielloantonio.tempo.sonos.models.SonosDevice
import com.cappielloantonio.tempo.sonos.models.SonosEvent
import com.cappielloantonio.tempo.sonos.network.SonosOkHttpClient
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * WebSocket API client for Sonos devices.
 * 
 * This follows the aiosonos (Python) implementation pattern.
 * Sonos uses WebSocket API on port 1443 for real-time control and events.
 * 
 * API Documentation: https://docs.sonos.com/reference
 */
class SonosWebSocketApi(
    private val device: SonosDevice,
    private val onEvent: (SonosEvent) -> Unit = {}
) {
    companion object {
        private const val TAG = "SonosWebSocketApi"
    }
    
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    private var webSocket: WebSocket? = null
    private val pendingCommands = ConcurrentHashMap<String, CompletableDeferred<Any>>()
    private val okHttpClient: OkHttpClient = SonosOkHttpClient.createWebSocketClient()
    private var connectionDeferred: CompletableDeferred<Unit>? = null
    
    // Reusable Moshi types for WebSocket message serialization/deserialization
    private val messageMapType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        Any::class.java
    )
    private val messageListType = Types.newParameterizedType(
        List::class.java,
        messageMapType
    )
    
    /**
     * Sonos API namespaces.
     */
    sealed class Namespace(val name: String) {
        object Playback : Namespace("playback")
        object PlaybackSession : Namespace("playbackSession")
        object GroupVolume : Namespace("groupVolume")
        object PlayerVolume : Namespace("playerVolume")
        object Groups : Namespace("groups")
        object PlaybackMetadata : Namespace("playbackMetadata")
        object AudioClip : Namespace("audioClip")
        object HomeTheater : Namespace("homeTheater")
    }
    
    /**
     * Connect to the Sonos WebSocket API.
     * Waits for the connection to be established.
     * 
     * @return true if connection was successful
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        if (webSocket != null) {
            Log.i(TAG, "SONOS_WS: Already connected to ${device.ipAddress}")
            return@withContext true
        }
        
        val url = device.websocketUrl.takeIf { it.isNotEmpty() } ?: run {
            Log.e(TAG, "SONOS_WS: No WebSocket URL for device ${device.id} (${device.id})")
            return@withContext false
        }
        
        Log.i(TAG, "SONOS_WS: Connecting to WebSocket: $url for device ${device.id}")
        
        val request = Request.Builder()
            .url(url)
            .addHeader("X-Sonos-Api-Key", SonosConstants.LOCAL_API_TOKEN)
            .addHeader("Sec-WebSocket-Protocol", SonosConstants.WEBSOCKET_PROTOCOL)
            .build()
        
        // Create new deferred for this connection attempt
        connectionDeferred = CompletableDeferred<Unit>()
        
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                Log.i(TAG, "SONOS_WS_OPEN: Connected to ${device.ipAddress}, response code=${response.code}")
                connectionDeferred?.complete(Unit)
            }
            
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "WebSocket raw message received: $text")
                handleIncomingMessage(text)
            }
            
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "SONOS_WS_CLOSED: code=$code, reason=$reason")
                this@SonosWebSocketApi.webSocket = null
                connectionDeferred = null
            }
            
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                Log.e(TAG, "SONOS_WS_FAILURE: device=${device.id}, ip=${device.ipAddress}, error=${t.message}", t)
                Log.i(TAG, "SONOS_WS_FAILURE_RESPONSE: code=${response?.code}, message=${response?.message}")
                this@SonosWebSocketApi.webSocket = null
                connectionDeferred?.completeExceptionally(t)
                connectionDeferred = null
            }
        })
        
        // Wait for connection to be established
        try {
            connectionDeferred?.await()
            return@withContext webSocket != null
        } catch (e: Throwable) {
            Log.e(TAG, "sonos: WebSocket connection failed", e)
            webSocket = null
            connectionDeferred = null
            return@withContext false
        }
    }
    
    /**
     * Disconnect from the WebSocket.
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting from ${device.ipAddress}")
        webSocket?.close(1000, "Normal closure")
        webSocket = null
        pendingCommands.clear()
        connectionDeferred = null
    }
    
    /**
     * Check if connected to the WebSocket.
     */
    fun isConnected(): Boolean = webSocket != null
    
    /**
     * Send a command to the Sonos API and wait for the response.
     * 
     * @param namespace The API namespace (e.g., playback, playbackSession)
     * @param command The command to execute
     * @param objectId The object ID (e.g., groupId, sessionId, playerId)
     * @param options Optional command options
     * @return The response from the API
     * @throws SonosError.ApiError if the command fails
     */
    suspend fun sendCommand(
        namespace: Namespace,
        command: String,
        objectId: String? = null,
        options: Map<String, Any>? = null,
        extraHeaders: Map<String, Any>? = null
    ): Any = withContext(Dispatchers.IO) {
        val cmdId = UUID.randomUUID().toString().replace("-", "")
        
        // Build command message
        val commandMessage: MutableMap<String, Any> = mutableMapOf(
            "namespace" to "${namespace.name}:${SonosConstants.API_VERSION}",
            "command" to command,
            "cmdId" to cmdId
        )
        
        // Add object ID based on namespace and command
        objectId?.let { 
            commandMessage[getObjectIdKey(namespace, command)] = it 
        }
        
        // Add extra headers if provided
        extraHeaders?.forEach { (key, value) ->
            commandMessage[key] = value
        }
        
        // Build message array [header, body]
        val message = listOf(commandMessage, options ?: emptyMap<String, Any>())
        
        // Serialize to JSON using proper parameterized type
        val json = moshi.adapter<List<Map<String, Any>>>(messageListType).toJson(message)
        Log.d(TAG, "Sending WebSocket command: $json")
        
        // Additional debug logging for seek commands to help troubleshooting
        if (command == "seek") {
            Log.i(TAG, "SONOS_SEEK_DEBUG: namespace=$namespace, command=$command, objectId=$objectId, options=$options, extraHeaders=$extraHeaders")
            Log.i(TAG, "SONOS_SEEK_DEBUG_JSON: $json")
        }
        
        // Create deferred for async response
        val deferred = CompletableDeferred<Any>()
        pendingCommands[cmdId] = deferred
        
        // Wait for connection if not yet established
        if (webSocket == null) {
            connectionDeferred?.await()
        }
        
        // Send message
        webSocket?.send(json) ?: run {
            pendingCommands.remove(cmdId)
            throw SonosError.ApiError("Not connected to WebSocket")
        }
        
        // Wait for response
        try {
            val response = deferred.await()
            Log.d(TAG, "Command $cmdId ($command) received response: $response")
            return@withContext response
        } catch (e: Throwable) {
            pendingCommands.remove(cmdId)
            Log.e(TAG, "sonos: Command $cmdId ($command) failed", e)
            // If it's already a SonosError.ApiError, rethrow it to preserve error details
            if (e is SonosError.ApiError) {
                throw e
            }
            // Otherwise wrap with timeout error
            throw SonosError.ApiError("Command failed: ${e.message}", errorCode = "TIMEOUT")
        }
    }
    
    /**
     * Get the object ID key for a namespace and command.
     * Some namespaces use different keys for different commands.
     */
    private fun getObjectIdKey(namespace: Namespace, command: String): String {
        return when (namespace) {
            Namespace.Playback -> "groupId"
            Namespace.PlaybackSession -> {
                when (command) {
                    "createSession" -> "playerId"
                    "loadStreamUrl" -> "groupId"
                    "loadTrackList" -> "sessionId"
                    "suspend" -> "sessionId"
                    "endSession" -> "sessionId"
                    else -> "groupId"  // seek, etc. use groupId as objectId
                }
            }
            Namespace.GroupVolume -> "groupId"
            Namespace.PlayerVolume -> "playerId"
            Namespace.Groups -> {
                when (command) {
                    "modifyGroupMembers" -> "groupId"
                    "setGroupMembers" -> "groupId"
                    else -> "householdId"  // getGroups, createGroup, etc.
                }
            }
            Namespace.PlaybackMetadata -> "groupId"
            Namespace.AudioClip -> "playerId"
            Namespace.HomeTheater -> "playerId"
        }
    }
    
    /**
     * Handle incoming WebSocket messages.
     * Sonos sends messages as JSON arrays with 2 objects:
     * [{"namespace": "...", "command": "...", "cmdId": "..."}, {...}]
     */
    private fun handleIncomingMessage(text: String) {
        try {
            // Parse as List<Map<String, Any>> using pre-created type adapter
            val messageList = moshi.adapter<List<Map<String, Any>>>(messageListType).fromJson(text) 
                ?: run {
                    Log.w(TAG, "Failed to parse message: $text")
                    return
                }
            
            if (messageList.size != 2) {
                Log.w(TAG, "Unexpected message format (expected 2 elements), got ${messageList.size}")
                return
            }
            
            val header = messageList[0]
            val body = messageList[1]
            
            // Extract cmdId from header for response matching
            val cmdId = header["cmdId"] as? String
            
            // Check if this is a command response (has "response" field in header)
            // Sonos sometimes puts response info in header instead of using success flag
            if (header.containsKey("response")) {
                val responseType = header["response"] as? String
                val success = header["success"] as? Boolean ?: false
                val errorType = header["type"] as? String
                
                Log.d(TAG, "Command response for cmdId=$cmdId: response=$responseType, success=$success, type=$errorType, body=$body")
                
                if (cmdId != null) {
                    val deferred = pendingCommands.remove(cmdId)
                    if (deferred != null) {
                        if (success) {
                            // For some Sonos devices, the actual response data is in the body
                            // even when response metadata is in header
                            deferred.complete(body)
                        } else {
                            // Extract error info from body or header
                            val errorCode = body["errorCode"] as? String ?: errorType
                            val reason = body["reason"] as? String ?: header["reason"] as? String
                            deferred.completeExceptionally(
                                SonosError.ApiError("Command failed: $errorCode", errorCode, reason)
                            )
                        }
                    }
                }
                return
            }
            
            // Check if this is an event message (has _objectType in body but no response in header)
            if (body.containsKey("_objectType")) {
                Log.d(TAG, "Received event message (not command response) for cmdId=$cmdId: $body")
                if (cmdId != null) {
                    // This might be a response to a command, treat as success with empty body
                    val deferred = pendingCommands.remove(cmdId)
                    if (deferred != null) {
                        Log.w(TAG, "Command $cmdId received event instead of response, completing with empty map")
                        deferred.complete(emptyMap<String, Any>())
                    }
                }
                return
            }
            
            // Handle error response
            if (body.containsKey("errorCode")) {
                val errorCode = body["errorCode"] as? String ?: "UNKNOWN_ERROR"
                val reason = body["reason"] as? String
                
                Log.w(TAG, "Error response for cmdId=$cmdId: $errorCode - $reason")
                
                if (cmdId != null) {
                    pendingCommands.remove(cmdId)?.completeExceptionally(
                        SonosError.ApiError("Command failed: $errorCode", errorCode, reason)
                    )
                }
                return
            }
            
            // Handle command response - cmdId already extracted above
            if (cmdId != null) {
                val deferred = pendingCommands.remove(cmdId)
                if (deferred != null) {
                    // Some Sonos responses don't include explicit success flag
                    // Treat any response with cmdId and no errorCode as successful
                    deferred.complete(body)
                }
                return
            }
            
            // Handle command response with explicit success flag in header (for backwards compatibility)
            // This path is for messages where cmdId was not in the standard location
            if (header.containsKey("success")) {
                val success = header["success"] as? Boolean ?: false
                val responseCmdId = header["cmdId"] as? String
                
                if (responseCmdId != null) {
                    val deferred = pendingCommands.remove(responseCmdId)
                    if (deferred != null) {
                        if (success) {
                            deferred.complete(body)
                        } else {
                            deferred.completeExceptionally(
                                SonosError.ApiError("Command failed", errorCode = "UNKNOWN")
                            )
                        }
                    }
                }
                return
            }
            
            // Handle event message
            val eventType = header["type"] as? String
            if (eventType != null) {
                Log.d(TAG, "Received event: $eventType")
                onEvent(SonosEvent(eventType, body))
                return
            }
            
            Log.d(TAG, "Unhandled message: $text")
        } catch (e: Throwable) {
            Log.e(TAG, "sonos: Error parsing message", e)
        }
    }
}
