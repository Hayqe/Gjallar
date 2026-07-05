package com.cappielloantonio.tempo.sonos.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageButton
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.cappielloantonio.tempo.R
import com.cappielloantonio.tempo.sonos.discovery.SonosDiscovery
import com.cappielloantonio.tempo.service.MediaService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Custom button for selecting Sonos devices.
 * Appears next to the Cast button in the toolbar.
 * Only visible when Sonos devices are available on the network.
 */
class SonosButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageButton(context, attrs, defStyleAttr) {
    
    private var deviceSelector: SonosDeviceSelector? = null
    private var discovery: SonosDiscovery? = null
    
    init {
        setImageResource(R.drawable.ic_sonos)
        contentDescription = context.getString(R.string.speakers)
        setOnClickListener { showDeviceChooser() }
        
        // Button is initially invisible, will become visible when devices are discovered
        visibility = View.GONE
    }
    
    /**
     * Set the MediaService reference.
     * This also initializes the discovery if not already done.
     */
    fun setMediaService(service: MediaService?) {
        if (service != null && discovery == null) {
            // Initialize discovery with context
            discovery = SonosDiscovery(context)
            
            // Start discovery
            startDiscovery()
            
            // Create device selector
            deviceSelector = SonosDeviceSelector(
                activity = context as? android.app.Activity ?: return,
                discovery = discovery!!
            )
            deviceSelector?.setMediaService(service)
        } else if (service == null) {
            deviceSelector?.setMediaService(null)
        }
    }
    
    /**
     * Start Sonos device discovery and update button visibility.
     */
    private fun startDiscovery() {
        discovery?.let { disc ->
            val view = this
            val lifecycleOwner = view.findViewTreeLifecycleOwner()
            lifecycleOwner?.lifecycleScope?.let { scope ->
                // Observe devices and update visibility
                disc.devices
                    .onEach { devices ->
                        // Button is visible if there are devices or groups
                        val hasDevices = devices.isNotEmpty()
                        val hasGroups = disc.groups.value.isNotEmpty()
                        view.visibility = if (hasDevices || hasGroups) View.VISIBLE else View.GONE
                    }
                    .launchIn(scope)
            }
        }
    }
    
    /**
     * Show the device chooser dialog.
     */
    private fun showDeviceChooser() {
        if (discovery?.devices?.value?.isEmpty() == true && 
            discovery?.groups?.value?.isEmpty() == true) {
            Toast.makeText(
                context,
                context.getString(R.string.sonos_no_devices),
                Toast.LENGTH_SHORT
            ).show()
            return
        }
        deviceSelector?.showDeviceChooser()
    }
    
    /**
     * Clean up resources.
     */
    fun cleanup() {
        deviceSelector = null
    }
}
