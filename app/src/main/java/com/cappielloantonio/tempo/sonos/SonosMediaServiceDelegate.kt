package com.cappielloantonio.tempo.sonos

import android.content.Context
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.cappielloantonio.tempo.sonos.models.SonosDevice
import com.cappielloantonio.tempo.sonos.models.SonosGroup
import com.cappielloantonio.tempo.sonos.player.SonosExoPlayerProxy
import com.cappielloantonio.tempo.sonos.player.SonosPlayerFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shared delegate for MediaService Sonos functionality.
 * Extracted to avoid duplicating ~90% identical code between
 * the 'degoogled' and 'tempus' build variants.
 */
class SonosMediaServiceDelegate(
    private val context: Context,
    private val exoPlayer: ExoPlayer
) {
    companion object {
        private const val TAG = "SonosDelegate"
    }

    private val sonosPlayerFactory by lazy { SonosPlayerFactory(context) }
    private var sonosProxy: SonosExoPlayerProxy? = null

    val hasSonosDevice: Boolean get() = sonosProxy != null
    val currentSonosDevice: SonosDevice? get() = sonosProxy?.getDevice()

    /**
     * Set the Sonos proxy to use for playback.
     */
    fun setSonosProxy(proxy: SonosExoPlayerProxy?) {
        Log.d(TAG, "setSonosProxy called: ${proxy != null}")
        if (sonosProxy == proxy) return

        sonosProxy?.release()
        sonosProxy = proxy

        // Mute local ExoPlayer when Sonos is active to prevent double audio
        exoPlayer.setVolume(if (proxy != null) 0f else 1f)
    }

    /**
     * Set the Sonos device to use for playback.
     * Creates a proxy, loads the current media item if any.
     *
     * @param device The Sonos device to connect to
     * @param currentMediaItem The currently playing media item, if any
     * @param onProxyReady Callback on main thread when proxy is ready
     */
    suspend fun connectToDevice(
        device: SonosDevice,
        currentMediaItem: MediaItem?,
        onProxyReady: (SonosExoPlayerProxy) -> Unit
    ) {
        Log.d(TAG, "setSonosDevice called: ${device.name} (${device.ipAddress})")
        val group = SonosGroup(
            device.id, device.name, device.householdId,
            device.id, listOf(device.id)
        )
        val proxy = sonosPlayerFactory.createProxy(group, device, null)

        withContext(Dispatchers.Main) {
            Log.d(TAG, "Setting Sonos proxy")
            setSonosProxy(proxy)
            proxy?.let { p ->
                onProxyReady(p)
                currentMediaItem?.let { p.loadMediaItem(it) }
            }
        }
    }

    /**
     * Disconnect/ungroup the current Sonos device.
     */
    suspend fun disconnectDevice(onDisconnected: () -> Unit) {
        Log.d(TAG, "ungroupSonosDevice called")
        sonosProxy?.let { proxy ->
            runCatching { proxy.stop() }
                .onFailure { Log.e(TAG, "Error stopping Sonos playback: ${it.message}") }

            runCatching { proxy.ungroupDevice() }
                .onFailure { Log.e(TAG, "Error ungrouping Sonos device: ${it.message}") }

            withContext(Dispatchers.Main) {
                setSonosProxy(null)
                onDisconnected()
            }
        } ?: Log.w(TAG, "No active Sonos proxy to ungroup")
    }

    /**
     * Forward play/pause/seek/volume/mediaItem commands to active Sonos proxy.
     */
    fun forwardCommand(command: String, data: Any?) {
        sonosProxy?.let { proxy ->
            when (command) {
                "play" -> proxy.play()
                "pause" -> proxy.pause()
                "stop" -> proxy.stop()
                "seek" -> {
                    val position = data as? Long ?: return
                    proxy.seekTo(position)
                }
                "mediaItem" -> {
                    val mediaItem = data as? MediaItem ?: return
                    proxy.loadMediaItem(mediaItem)
                }
                "volume" -> {
                    val volume = data as? Float ?: return
                    proxy.setVolume(volume)
                }
            }
        }
    }

    /**
     * Release all Sonos resources.
     */
    fun release() {
        sonosProxy?.release()
        sonosProxy = null
        runCatching { sonosPlayerFactory.releaseAll() }
            .onFailure { Log.e(TAG, "Error releasing Sonos player factory", it) }
    }
}
