package com.cappielloantonio.tempo.service

import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.media3.cast.CastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.cappielloantonio.tempo.repository.AutomotiveRepository
import com.cappielloantonio.tempo.sonos.SonosMediaServiceDelegate
import com.cappielloantonio.tempo.sonos.models.SonosDevice
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

@UnstableApi
class MediaService : BaseMediaService(), SessionAvailabilityListener, CoroutineScope by MainScope() {
    companion object {
        const val TAG = "MediaService"
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    private val automotiveRepository = AutomotiveRepository()
    private lateinit var castPlayer: CastPlayer
    private val sonosDelegate by lazy { SonosMediaServiceDelegate(this, exoplayer) }
    private var currentRemotePlayer: Player? = null
    private var sonosPlayerListener: Player.Listener? = null

    @Suppress("DEPRECATION")
    private fun initializeCastPlayer() {
        if (GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        ) {
            CastContext.getSharedInstance(this, ContextCompat.getMainExecutor(this))
                .addOnSuccessListener { castContext ->
                    castPlayer = CastPlayer(castContext)
                    castPlayer.setSessionAvailabilityListener(this@MediaService)
                    initializePlayerListener(castPlayer)
                    updateRemotePlayer()
                }
        }
    }

    override fun getMediaLibrarySessionCallback(): MediaLibrarySession.Callback {
        if (sessionCallback == null) {
            sessionCallback = MediaLibrarySessionCallback(baseContext, this, automotiveRepository)
        }
        return sessionCallback!!
    }

    override fun playerInitHook() {
        super.playerInitHook()
        initializeCastPlayer()
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

    // --- Sonos integration (called from toolbar menu) ---

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
        val newRemotePlayer = when {
            this::castPlayer.isInitialized && castPlayer.isCastSessionAvailable -> castPlayer.also {
                sonosDelegate.setSonosProxy(null)
            }
            else -> null
        }

        if (currentRemotePlayer != newRemotePlayer) {
            currentRemotePlayer = newRemotePlayer
            if (newRemotePlayer != null) {
                setPlayer(mediaLibrarySession.player, newRemotePlayer)
            } else {
                setPlayer(null, exoplayer)
            }
        }
    }

    override fun releasePlayers() {
        sonosPlayerListener?.let { exoplayer.removeListener(it) }
        if (this::castPlayer.isInitialized) {
            castPlayer.setSessionAvailabilityListener(null)
            castPlayer.release()
        }
        sonosDelegate.release()
        currentRemotePlayer = null
        automotiveRepository.deleteMetadata()
        super.releasePlayers()
    }

    override fun onCastSessionAvailable() {
        updateRemotePlayer()
    }

    override fun onCastSessionUnavailable() {
        updateRemotePlayer()
    }
}
