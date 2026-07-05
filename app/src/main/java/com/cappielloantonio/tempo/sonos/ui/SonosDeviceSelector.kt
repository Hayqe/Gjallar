package com.cappielloantonio.tempo.sonos.ui

import android.app.Activity
import android.content.Context
import android.util.Log
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.sonos.discovery.SonosDiscovery
import com.cappielloantonio.tempo.sonos.models.SonosDevice
import com.cappielloantonio.tempo.sonos.models.SonosGroup
import com.cappielloantonio.tempo.service.MediaService
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Handles Sonos device selection and connects selected device to MediaService.
 */
class SonosDeviceSelector(
    private val activity: Activity,
    private val discovery: SonosDiscovery,
    private var mediaService: MediaService? = null
) {
    
    private var isDiscovering = false
    private var retryCount = 0
    private val maxRetries = 10
    
    init {
        // Start discovery when selector is created
        startDiscovery()
    }
    
    /**
     * Start Sonos device discovery.
     * Uses a single discovery instance that's shared across the app.
     */
    private fun startDiscovery() {
        if (isDiscovering) return
        isDiscovering = true
        
        MainScope().launch {
            try {
                discovery.scanNetwork()
            } catch (e: Exception) {
                Log.e("SonosDeviceSelector", "Error during discovery", e)
            } finally {
                isDiscovering = false
            }
        }
    }
    
    /**
     * Show dialog with available Sonos devices and groups.
     * If discovery is in progress, shows a loading message.
     * If no devices found, shows an error.
     * Otherwise, shows the device chooser dialog.
     */
    fun showDeviceChooser() {
        Log.d("SonosDeviceSelector", "showDeviceChooser called, mediaService=${mediaService != null}")

        if (mediaService == null) {
            retryCount++
            if (retryCount > maxRetries) {
                Log.e("SonosDeviceSelector", "MediaService still not available after $maxRetries retries")
                Toast.makeText(activity, "Media service not available. Please restart the app.", Toast.LENGTH_LONG).show()
                retryCount = 0
                return
            }
            Log.w("SonosDeviceSelector", "MediaService not available yet (attempt $retryCount/$maxRetries), waiting...")
            Toast.makeText(activity, "Waiting for media service... ($retryCount/$maxRetries)", Toast.LENGTH_SHORT).show()
            MainScope().launch {
                kotlinx.coroutines.delay(1000)
                showDeviceChooser()
            }
            return
        }
        retryCount = 0

        Log.d("SonosDeviceSelector", "Devices: ${discovery.devices.value.size}, Groups: ${discovery.groups.value.size}")

        MainScope().launch {
            try {
                val devices = withTimeoutOrNull(15_000L) {
                    discovery.scanNetwork()
                }
                if (devices != null && devices.isNotEmpty()) {
                    activity.runOnUiThread { showDeviceListDialog(devices, discovery.groups.value) }
                } else {
                    activity.runOnUiThread {
                        Toast.makeText(activity, activity.getString(R.string.sonos_no_devices), Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                Log.e("SonosDeviceSelector", "Error in showDeviceChooser", e)
                activity.runOnUiThread {
                    Toast.makeText(activity, activity.getString(R.string.sonos_no_devices), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    /**
     * Show the device chooser dialog with the given devices and groups.
     */
    private fun showDeviceListDialog(devices: List<SonosDevice>, groups: List<SonosGroup>) {
        // Combine devices and groups into a single list
        val deviceChoices = mutableListOf<DeviceChoice>()
        
        // Get current active device if any
        val currentDevice = mediaService?.getSonosDevice()
        val hasActiveDevice = mediaService?.hasSonosDevice() == true
        
        // Add current device as disconnect option if active
        if (hasActiveDevice && currentDevice != null) {
            deviceChoices.add(
                DeviceChoice(
                    device = currentDevice,
                    group = null,
                    name = "${currentDevice.name} (${activity.getString(R.string.sonos_ungroup)})",
                    iconRes = R.drawable.ic_sonos,
                    isGroup = false,
                    isDisconnectOption = true
                )
            )
        }
        
        // Add groups (exclude group containing current device if active)
        for (group in groups) {
            // Filter out groups with empty names or "Unknown" name
            if (group.name.isNotEmpty() && group.name != "Unknown") {
                // Skip if this is the group of the current device (to avoid duplicate)
                if (hasActiveDevice && currentDevice != null && group.coordinatorId == currentDevice.id) {
                    continue
                }
                deviceChoices.add(
                    DeviceChoice(
                        device = null,
                        group = group,
                        name = group.name,
                        iconRes = R.drawable.ic_sonos_group,
                        isGroup = true
                    )
                )
            }
        }
        
        // Add individual devices that are NOT in any group (standalone devices)
        val deviceIdsInGroups = groups.flatMap { it.playerIds }.toSet()
        for (device in devices) {
            // Filter out devices with "Unknown" name or empty name
            if (device.name != "Unknown" && device.name.isNotEmpty()) {
                // Skip current device as it's already shown as disconnect option
                if (hasActiveDevice && currentDevice != null && device.id == currentDevice.id) {
                    continue
                }
                // Skip devices that are already in groups
                if (device.id in deviceIdsInGroups) {
                    continue
                }
                deviceChoices.add(
                    DeviceChoice(
                        device = device,
                        group = null,
                        name = device.name,
                        iconRes = R.drawable.ic_sonos,
                        isGroup = false
                    )
                )
            }
        }
        
        if (deviceChoices.isEmpty()) {
            Toast.makeText(
                activity,
                activity.getString(R.string.sonos_no_devices),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        
        showDeviceDialog(deviceChoices)
    }
    
    /**
     * Set the MediaService reference.
     */
    fun setMediaService(service: MediaService?) {
        Log.d("SonosDeviceSelector", "MediaService set: ${service != null}")
        this.mediaService = service
    }
    
    private fun showDeviceDialog(choices: List<DeviceChoice>) {
        // Create a custom adapter that handles separators and styling
        val adapter = object : ArrayAdapter<DeviceChoice>(activity, android.R.layout.simple_list_item_1, choices) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context).inflate(android.R.layout.simple_list_item_1, parent, false)
                val textView = view.findViewById<TextView>(android.R.id.text1)
                val choice = getItem(position)
                
                if (choice != null) {
                    if (choice.isSeparator) {
                        // Style as a divider line
                        textView.text = ""
                        textView.height = 1
                        textView.setBackgroundColor(ContextCompat.getColor(context, R.color.dividerColor))
                        textView.setPadding(0, 8, 0, 8)
                    } else if (choice.isDisconnectOption) {
                        // Style disconnect option in red
                        textView.text = choice.name
                        textView.setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
                    } else {
                        // Normal device/group item — use theme-aware text color for dark mode
                        textView.text = choice.name
                        val tv = TypedValue()
                        context.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
                        textView.setTextColor(tv.data)
                    }
                }
                return view
            }
            
            override fun isEnabled(position: Int): Boolean {
                return getItem(position)?.isSeparator != true
            }
        }
        
        AlertDialog.Builder(activity)
            .setTitle(R.string.speakers)
            .setAdapter(adapter) { _, which ->
                val choice = choices[which]
                // Skip separator items (should not happen due to isEnabled, but just in case)
                if (choice.isSeparator) {
                    return@setAdapter
                }
                if (choice.isDisconnectOption) {
                    // Handle disconnect action
                    mediaService?.ungroupSonosDevice()
                } else if (choice.isGroup) {
                    // For groups, we need to find the coordinator device
                    choice.group?.let { group ->
                        val coordinator = discovery.devices.value.find { 
                            it.id == group.coordinatorId 
                        }
                        if (coordinator != null) {
                            mediaService?.setSonosDevice(coordinator)
                        }
                    }
                } else {
                    choice.device?.let { device ->
                        mediaService?.setSonosDevice(device)
                    }
                }
            }
            .show()
    }
    
    /**
     * Data class to hold device/group choice information.
     */
    private data class DeviceChoice(
        val device: SonosDevice?,
        val group: SonosGroup?,
        val name: String,
        val iconRes: Int,
        val isGroup: Boolean,
        val isDisconnectOption: Boolean = false,
        val isSeparator: Boolean = false
    )
}
