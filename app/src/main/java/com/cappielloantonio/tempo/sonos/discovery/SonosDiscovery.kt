package com.cappielloantonio.tempo.sonos.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.cappielloantonio.tempo.sonos.SonosConstants
import com.cappielloantonio.tempo.sonos.api.SonosError
import com.cappielloantonio.tempo.sonos.models.SonosDevice
import com.cappielloantonio.tempo.sonos.models.SonosDiscoveryInfo
import com.cappielloantonio.tempo.sonos.models.SonosGroup
import com.cappielloantonio.tempo.sonos.models.SonosGroupData
import com.cappielloantonio.tempo.sonos.models.SonosGroupsResponse
import com.cappielloantonio.tempo.sonos.models.SonosPlayerData
import com.cappielloantonio.tempo.sonos.network.SonosOkHttpClient
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.Inet4Address
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Discovers Sonos devices on the local network using mDNS (Multicast DNS).
 * 
 * Sonos devices advertise themselves via mDNS with service type "_sonos._tcp."
 * Each Sonos speaker has an HTTP API endpoint at port 1443 for discovery and control.
 * 
 * @param context Android context for accessing NsdManager
 */
class SonosDiscovery(private val context: Context) {
    companion object {
        private const val TAG = "SonosDiscovery"
        private const val NSD_DISCOVERY_TIMEOUT_MS = 5000L
    }
    
    // Shared OkHttpClient for all HTTP requests
    private val okHttpClient: OkHttpClient = SonosOkHttpClient.createUnsafeClient()
    
    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
    
    // Type for parsing generic JSON maps
    private val mapType = Types.newParameterizedType(
        Map::class.java,
        String::class.java,
        Any::class.java
    )
    
    private val _discoveredDevices = MutableStateFlow<List<SonosDevice>>(emptyList())
    private val _discoveredGroups = MutableStateFlow<List<SonosGroup>>(emptyList())
    private val _discoveryErrors = MutableStateFlow<List<SonosError>>(emptyList())
    
    val devices: StateFlow<List<SonosDevice>> = _discoveredDevices
    val groups: StateFlow<List<SonosGroup>> = _discoveredGroups
    val discoveryErrors: StateFlow<List<SonosError>> = _discoveryErrors
    
    // Mutex to prevent concurrent scan operations
    private val scanMutex = Mutex()
    
    private var nsdManager: NsdManager? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var nsdResolveListeners = mutableMapOf<String, NsdManager.ResolveListener>()
    private var isDiscoveringViaNsd = false
    private var currentNsdJob: Job? = null
    
    // Cache for already resolved devices to avoid duplicate work
    private val resolvedDevices = mutableSetOf<String>()
    
    init {
        nsdManager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        Log.d(TAG, "SonosDiscovery initialized, NsdManager available: ${nsdManager != null}")
    }
    
    /**
     * Scan the local network for Sonos devices using mDNS.
     * 
     * @return List of discovered Sonos devices
     */
    suspend fun scanNetwork(): List<SonosDevice> = withContext(Dispatchers.IO) {
        // Use mutex to prevent concurrent scan operations
        scanMutex.withLock {
            Log.d(TAG, "Starting Sonos device discovery")
            
            val discoveredDevices = mutableListOf<SonosDevice>()
            val errors = mutableListOf<SonosError>()
            
            // Clear previous state
            resolvedDevices.clear()
            _discoveryErrors.value = emptyList()
            
            // Use mDNS discovery as the primary (and only) method
            if (nsdManager != null) {
                Log.d(TAG, "Attempting mDNS discovery")
                try {
                    discoveredDevices.addAll(discoverViaNsd())
                    Log.d(TAG, "mDNS discovery found ${discoveredDevices.size} devices")
                } catch (e: Exception) {
                    Log.w(TAG, "sonos: mDNS discovery failed", e)
                    errors.add(SonosError.DiscoveryError("mDNS discovery failed", e))
                }
            } else {
                Log.w(TAG, "sonos: NsdManager not available on this device")
                errors.add(SonosError.DiscoveryError("mDNS not available on this device"))
            }
            
            // Update state flows
            _discoveredDevices.value = discoveredDevices
            if (discoveredDevices.isNotEmpty()) {
                fetchAndUpdateGroups(discoveredDevices[0])
            }
            _discoveryErrors.value = errors
            
            Log.d(TAG, "Discovery complete: ${discoveredDevices.size} devices, ${errors.size} errors")
            return@withContext discoveredDevices
        }
    }
    
    /**
     * Discover Sonos devices using mDNS (Multicast DNS).
     * This is the preferred method as it doesn't require scanning the entire subnet.
     * 
     * @return List of discovered Sonos devices
     */
    private suspend fun discoverViaNsd(): List<SonosDevice> = suspendCancellableCoroutine { continuation ->
        val discoveredDevices = mutableListOf<SonosDevice>()
        val serviceNameSet = mutableSetOf<String>()
        var isResumed = false
        
        if (nsdManager == null) {
            safeResumeContinuation(continuation, emptyList())
            return@suspendCancellableCoroutine
        }
        
        isDiscoveringViaNsd = true
        
        // Create a job for this discovery operation
        val discoveryJob = SupervisorJob()
        currentNsdJob = discoveryJob
        
        // Set up discovery listener
        nsdDiscoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "mDNS discovery started for service type: $serviceType")
            }
            
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val serviceName = serviceInfo.serviceName
                Log.d(TAG, "mDNS service found: $serviceName (type: ${serviceInfo.serviceType})")
                
                // Avoid duplicates
                if (serviceName in serviceNameSet) {
                    Log.d(TAG, "Skipping duplicate service: $serviceName")
                    return
                }
                serviceNameSet.add(serviceName)
                
                // Filter for Sonos services
                if (serviceInfo.serviceType != SonosConstants.SONOS_SERVICE_TYPE) {
                    Log.d(TAG, "Skipping non-Sonos service: ${serviceInfo.serviceType}")
                    return
                }
                
                // Resolve the service to get IP and port
                val resolveListener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "sonos: Failed to resolve service $serviceName, error: $errorCode")
                        nsdResolveListeners.remove(serviceName)
                    }
                    
                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        Log.d(TAG, "Resolved service: $serviceName")
                        
                        // Extract IP address (Sonos uses IPv4)
                        val host = serviceInfo.host
                        val ipAddress = if (host is Inet4Address) {
                            host.hostAddress
                        } else {
                            Log.w(TAG, "sonos: Service $serviceName has non-IPv4 address: ${host?.hostAddress}")
                            nsdResolveListeners.remove(serviceName)
                            return
                        }
                        
                        val port = serviceInfo.port
                        
                        // Create discovery URL
                        val discoveryUrl = SonosConstants.getDiscoveryUrl(ipAddress)
                        
                        Log.d(TAG, "Resolved Sonos device at $ipAddress:$port")
                        
                        // Store that we've resolved this device
                        resolvedDevices.add(serviceName)
                        
                        // Fetch device info in background
                        CoroutineScope(Dispatchers.IO + discoveryJob).launch {
                            try {
                                val device = fetchDeviceInfo(ipAddress, discoveryUrl)
                                if (device != null) {
                                    discoveredDevices.add(device)
                                    Log.d(TAG, "Added mDNS-discovered device: ${device.id} (${device.name})")
                                }
                            } catch (e: Exception) {
                                Log.w(TAG, "sonos: Failed to fetch info for device at $ipAddress", e)
                            } finally {
                                nsdResolveListeners.remove(serviceName)
                                
                                // Check if we've discovered enough - just stop discovery
                                // Don't resume here to avoid race condition with onDiscoveryStopped
                                if (serviceNameSet.size >= 20 || discoveredDevices.size >= 10) {
                                    // We've found enough devices, stop discovery
                                    stopNsdDiscovery()
                                }
                            }
                        }
                    }
                }
                
                nsdResolveListeners[serviceName] = resolveListener
                nsdManager?.takeIf { isDiscoveringViaNsd }?.resolveService(serviceInfo, resolveListener)
            }
            
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "Service lost: ${serviceInfo.serviceName}")
            }
            
            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "mDNS discovery stopped for service type: $serviceType")
                isDiscoveringViaNsd = false
                // Only resume once, and only if not already resumed
                if (!isResumed) {
                    isResumed = true
                    safeResumeContinuation(continuation, discoveredDevices)
                    // Cancel the discovery job to clean up any pending coroutines
                    discoveryJob.cancel()
                    currentNsdJob = null
                }
            }
            
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "sonos: Failed to start mDNS discovery, error: $errorCode")
                isDiscoveringViaNsd = false
                discoveryJob.cancel()
                currentNsdJob = null
                if (!isResumed) {
                    isResumed = true
                    safeResumeWithException(continuation, 
                        SonosError.DiscoveryError("Failed to start mDNS discovery", null))
                }
            }
            
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "sonos: Failed to stop mDNS discovery, error: $errorCode")
                isDiscoveringViaNsd = false
            }
        }
        
        // Start discovery with timeout
        nsdManager?.discoverServices(
            SonosConstants.SONOS_SERVICE_TYPE,
            NsdManager.PROTOCOL_DNS_SD,
            nsdDiscoveryListener
        )
        
        // Set timeout to stop discovery after a while
        Handler(Looper.getMainLooper()).postDelayed({
            if (isDiscoveringViaNsd) {
                Log.d(TAG, "mDNS discovery timeout reached")
                stopNsdDiscovery()
                // Don't resume here - onDiscoveryStopped will handle it
            }
        }, NSD_DISCOVERY_TIMEOUT_MS)
        
        // Allow cancellation
        continuation.invokeOnCancellation {
            stopNsdDiscovery()
            discoveryJob.cancel()
            currentNsdJob = null
            if (!isResumed) {
                isResumed = true
                safeResumeWithException(continuation,
                    SonosError.DiscoveryError("mDNS discovery cancelled", null))
            }
        }
    }
    
    /**
     * Safely resume a continuation, checking if it's still active.
     * This prevents crashes when the continuation has already been completed or cancelled.
     */
    private fun safeResumeContinuation(continuation: Continuation<List<SonosDevice>>, value: List<SonosDevice>) {
        try {
            continuation.resume(value)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "sonos: Continuation already completed or cancelled", e)
        } catch (e: Exception) {
            Log.e(TAG, "sonos: Error resuming continuation", e)
        }
    }
    
    /**
     * Safely resume a continuation with an exception.
     */
    private fun safeResumeWithException(continuation: Continuation<List<SonosDevice>>, exception: SonosError) {
        try {
            continuation.resumeWithException(exception)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "sonos: Continuation already completed or cancelled", e)
        } catch (e: Exception) {
            Log.e(TAG, "sonos: Error resuming continuation with exception", e)
        }
    }
    
    /**
     * Stop mDNS discovery.
     */
    private fun stopNsdDiscovery() {
        if (!isDiscoveringViaNsd) return
        
        isDiscoveringViaNsd = false
        
        // Stop the main discovery listener
        try {
            nsdDiscoveryListener?.let { listener ->
                nsdManager?.stopServiceDiscovery(listener)
            }
        } catch (e: Exception) {
            Log.w(TAG, "sonos: Error stopping mDNS discovery", e)
        } finally {
            nsdDiscoveryListener = null
            // Note: Resolve listeners are automatically cleaned up when services are resolved
            // or when discovery stops. We don't need to explicitly stop them.
            nsdResolveListeners.clear()
        }
    }
    
    /**
     * Create a SonosDevice from discovery info.
     */
    private fun createDeviceFromDiscoveryInfo(info: SonosDiscoveryInfo, ip: String): SonosDevice {
        return SonosDevice(
            id = info.playerId,
            householdId = info.householdId,
            name = "Unknown",
            ipAddress = ip,
            model = "Unknown",
            modelNumber = "",
            serialNumber = "",
            softwareVersion = "",
            hardwareVersion = "",
            isZoneBridge = false,
            isZonePlayer = true,
            websocketUrl = info.websocketUrl
        )
    }
    
    /**
     * Fetch device info using pre-built URL (for mDNS resolution).
     */
    private suspend fun fetchDeviceInfo(ip: String, discoveryUrl: String): SonosDevice? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(discoveryUrl)
            .addHeader("X-Sonos-Api-Key", SonosConstants.LOCAL_API_TOKEN)
            .build()
        
        try {
            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val json = response.body?.string() ?: return@withContext null
                val discoveryInfo = moshi.adapter(SonosDiscoveryInfo::class.java).fromJson(json)
                
                return@withContext discoveryInfo?.let { info ->
                    createDeviceFromDiscoveryInfo(info, ip)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "sonos: Failed to fetch device info from $discoveryUrl", e)
        }
        
        return@withContext null
    }
    
    /**
     * Fetch groups information from the Sonos API.
     */
    private suspend fun fetchGroups(householdId: String, device: SonosDevice): SonosGroupsResponse {
        val maxRetries = 3
        var lastError: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                val url = SonosConstants.getGroupsUrl(device.ipAddress, householdId)
                
                val request = Request.Builder()
                    .url(url)
                    .addHeader("X-Sonos-Api-Key", SonosConstants.LOCAL_API_TOKEN)
                    .build()
                
                val response = okHttpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = response.body?.string() ?: return SonosGroupsResponse(emptyList(), emptyList())
                    
                    // Parse the response
                    val map = moshi.adapter<Map<String, Any>>(mapType).fromJson(json) 
                        ?: return SonosGroupsResponse(emptyList(), emptyList())
                    
                    val groups = parseGroupsList(map["groups"] as? List<Map<String, Any>> ?: emptyList())
                    val players = parsePlayersList(map["players"] as? List<Map<String, Any>> ?: emptyList())
                    
                    return SonosGroupsResponse(groups, players)
                }
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries - 1) {
                    val delay = 500L * (attempt + 1)
                    Log.w(TAG, "sonos: Group fetch failed attempt ${attempt + 1}/$maxRetries, retrying in ${delay}ms")
                    kotlinx.coroutines.delay(delay)
                }
            }
        }
        
        Log.e(TAG, "sonos: Error fetching groups for household $householdId after $maxRetries attempts", lastError)
        return SonosGroupsResponse(emptyList(), emptyList())
    }
    
    /**
     * Parse groups list from JSON.
     */
    private fun parseGroupsList(list: List<Map<String, Any>>): List<SonosGroupData> {
        return list.map { map ->
            SonosGroupData(
                id = map["id"] as? String ?: "",
                name = map["name"] as? String ?: "",
                householdId = map["householdId"] as? String ?: "",
                coordinatorId = map["coordinatorId"] as? String ?: "",
                playerIds = (map["playerIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                areaIds = (map["areaIds"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
                playbackState = map["playbackState"] as? String
            )
        }
    }
    
    /**
     * Parse players list from JSON.
     */
    private fun parsePlayersList(list: List<Map<String, Any>>): List<SonosPlayerData> {
        return list.map { map ->
            SonosPlayerData(
                id = map["id"] as? String ?: "",
                name = map["name"] as? String ?: "",
                householdId = map["householdId"] as? String ?: "",
                roomName = map["roomName"] as? String,
                icon = map["icon"] as? String,
                model = map["model"] as? String,
                modelNumber = map["modelNumber"] as? String,
                serialNumber = map["serialNumber"] as? String,
                softwareVersion = map["softwareVersion"] as? String,
                hardwareVersion = map["hardwareVersion"] as? String
            )
        }
    }
    
    /**
     * Fetch groups and update device names from the first device.
     * Updates the StateFlows for devices and groups.
     * Must be called within scanMutex lock to prevent concurrent updates.
     */
    private suspend fun fetchAndUpdateGroups(device: SonosDevice): List<SonosGroup> {
        val householdId = device.householdId
        
        try {
            val groupsResponse = fetchGroups(householdId, device)
            
            // Update device names from players data
            val playersById = groupsResponse.players.associateBy { it.id }
            val updatedDevices = _discoveredDevices.value.map { dev ->
                val playerData = playersById[dev.id]
                if (playerData != null) {
                    dev.copy(
                        name = playerData.name.ifEmpty { dev.name },
                        model = playerData.model ?: dev.model,
                        modelNumber = playerData.modelNumber ?: dev.modelNumber,
                        serialNumber = playerData.serialNumber ?: dev.serialNumber,
                        softwareVersion = playerData.softwareVersion ?: dev.softwareVersion,
                        hardwareVersion = playerData.hardwareVersion ?: dev.hardwareVersion
                    )
                } else {
                    dev
                }
            }
            
            _discoveredDevices.value = updatedDevices
            
            // Convert groups response to our SonosGroup model
            val groups = groupsResponse.groups.map { groupData ->
                SonosGroup(
                    id = groupData.id,
                    name = groupData.name,
                    householdId = groupData.householdId,
                    coordinatorId = groupData.coordinatorId,
                    playerIds = groupData.playerIds,
                    areaIds = groupData.areaIds
                )
            }
            
            _discoveredGroups.value = groups
            
            Log.d(TAG, "Updated ${updatedDevices.size} devices and ${groups.size} groups")
            return groups
        } catch (e: Exception) {
            Log.e(TAG, "sonos: Error refreshing groups", e)
            return _discoveredGroups.value
        }
    }
    
    /**
     * Start scanning for devices on the network (non-suspend version for Java interop).
     */
    fun startScan() {
        MainScope().launch {
            try {
                scanNetwork()
            } catch (e: Exception) {
                Log.e(TAG, "sonos: Error during discovery", e)
                _discoveryErrors.value = listOf(SonosError.DiscoveryError("Discovery failed", e))
            }
        }
    }
    
    /**
     * Stop all ongoing discovery operations.
     */
    fun stopDiscovery() {
        stopNsdDiscovery()
        currentNsdJob?.cancel()
        currentNsdJob = null
    }
    
    /**
     * Get a device by ID.
     */
    fun getDevice(deviceId: String): SonosDevice? {
        return _discoveredDevices.value.find { it.id == deviceId }
    }
    
    /**
     * Get a group by ID.
     */
    fun getGroup(groupId: String): SonosGroup? {
        return _discoveredGroups.value.find { it.id == groupId }
    }
    
    /**
     * Get the coordinator device for a group.
     */
    fun getCoordinator(group: SonosGroup): SonosDevice? {
        return _discoveredDevices.value.find { it.id == group.coordinatorId }
    }
    
    /**
     * Refresh group information from the Sonos API.
     * 
     * @param device Any device in the household
     * @return Updated list of groups
     */
    suspend fun refreshGroups(device: SonosDevice): List<SonosGroup> = withContext(Dispatchers.IO) {
        scanMutex.withLock {
            return@withContext fetchAndUpdateGroups(device)
        }
    }
    
    /**
     * Get the current active group and coordinator for a group.
     * 
     * @param group The group to check
     * @param device Any device in the same household
     * @return Pair of (activeGroupId, activeCoordinatorId)
     */
    suspend fun getActiveGroupAndCoordinator(group: SonosGroup, device: SonosDevice): Pair<String, String> {
        val refreshedGroups = refreshGroups(device)
        val refreshedGroup = refreshedGroups.find { it.id == group.id }
        
        if (refreshedGroup != null && refreshedGroup.coordinatorId.isNotEmpty()) {
            Log.d(TAG, "Active coordinator for group ${group.id} is ${refreshedGroup.coordinatorId}")
            return Pair(refreshedGroup.id, refreshedGroup.coordinatorId)
        }
        
        val groupWithDevice = refreshedGroups.find { it.playerIds.contains(device.id) }
        if (groupWithDevice != null && groupWithDevice.coordinatorId.isNotEmpty()) {
            Log.d(TAG, "Original group ${group.id} not found, device ${device.id} is in group ${groupWithDevice.id}")
            return Pair(groupWithDevice.id, groupWithDevice.coordinatorId)
        }
        
        val groupByCoordinator = refreshedGroups.find { it.coordinatorId == group.coordinatorId }
        if (groupByCoordinator != null && groupByCoordinator.coordinatorId.isNotEmpty()) {
            Log.d(TAG, "Original group ${group.id} not found, coordinator ${group.coordinatorId} leads to group ${groupByCoordinator.id}")
            return Pair(groupByCoordinator.id, groupByCoordinator.coordinatorId)
        }
        
        for (originalPlayerId in group.playerIds) {
            val groupWithOriginalPlayer = refreshedGroups.find { it.playerIds.contains(originalPlayerId) }
            if (groupWithOriginalPlayer != null && groupWithOriginalPlayer.coordinatorId.isNotEmpty()) {
                Log.d(TAG, "Found original player $originalPlayerId in group ${groupWithOriginalPlayer.id}")
                return Pair(groupWithOriginalPlayer.id, groupWithOriginalPlayer.coordinatorId)
            }
        }
        
        Log.w(TAG, "sonos: Could not refresh group info, using cached: ${group.id}, ${group.coordinatorId}")
        return Pair(group.id, group.coordinatorId)
    }
    
    /**
     * Check if discovery is currently in progress.
     */
    fun isDiscovering(): Boolean {
        return isDiscoveringViaNsd
    }
    
    /**
     * Clean up resources when this instance is no longer needed.
     */
    fun cleanup() {
        stopDiscovery()
        nsdManager = null
    }
}
