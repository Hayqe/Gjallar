package com.cappielloantonio.tempo.service

import android.content.Intent
import android.os.IBinder
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.sonos.SonosMediaServiceDelegate
import com.cappielloantonio.tempo.sonos.models.SonosDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

@UnstableApi
class MediaService : BaseMediaService(), CoroutineScope by MainScope() {
    companion object {
        const val TAG = "MediaService"
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    private val sonosDelegate by lazy { SonosMediaServiceDelegate(this, exoplayer) }
    private var currentRemotePlayer: Player? = null
    private var sonosPlayerListener: Player.Listener? = null

    override fun playerInitHook() {
        super.playerInitHook()
        updateRemotePlayer()
        attachSonosCommandForwarder()
    }

    private fun attachSonosCommandForwarder() {
        sonosPlayerListener?.let { exoplayer.removeListener(it) }
        sonosPlayerListener = object : Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (sonosDelegate.hasSonosDevice) {
                    if (playWhenReady) sonosDelegate.forwardCommand("play", null)
                    else sonosDelegate.forwardCommand("pause", null)
                }
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                if (sonosDelegate.hasSonosDevice && mediaItem != null) {
                    sonosDelegate.forwardCommand("mediaItem", mediaItem)
                }
            }
            override fun onVolumeChanged(volume: Float) {
                if (sonosDelegate.hasSonosDevice) {
                    sonosDelegate.forwardCommand("volume", volume)
                }
            }
        }
        sonosPlayerListener?.let { exoplayer.addListener(it) }
    }

    fun setSonosProxy(proxy: com.cappielloantonio.tempo.sonos.player.SonosExoPlayerProxy?) {
        sonosDelegate.setSonosProxy(proxy)
        updateRemotePlayer()
    }

    fun setSonosDevice(device: SonosDevice) {
        val currentMediaItem = mediaLibrarySession.player.currentMediaItem
        launch(Dispatchers.IO) {
            sonosDelegate.connectToDevice(
                device = device,
                currentMediaItem = currentMediaItem
            ) { _ ->
                updateRemotePlayer()
            }
        }
    }

    fun ungroupSonosDevice() {
        launch(Dispatchers.IO) {
            sonosDelegate.disconnectDevice {
                updateRemotePlayer()
            }
        }
    }

    fun hasSonosDevice(): Boolean = sonosDelegate.hasSonosDevice
    fun getSonosDevice(): SonosDevice? = sonosDelegate.currentSonosDevice

    private fun updateRemotePlayer() {
        val newRemotePlayer: Player? = null
        if (currentRemotePlayer != newRemotePlayer) {
            currentRemotePlayer = newRemotePlayer
            setPlayer(mediaLibrarySession.player, exoplayer)
        }
    }

    override fun releasePlayers() {
        sonosPlayerListener?.let { exoplayer.removeListener(it) }
        sonosDelegate.release()
        currentRemotePlayer = null
        super.releasePlayers()
    }
}
