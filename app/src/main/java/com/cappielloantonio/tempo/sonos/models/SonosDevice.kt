package com.cappielloantonio.tempo.sonos.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Representation of a Sonos speaker/device.
 * Based on aiosonos models.
 */
@Parcelize
data class SonosDevice(
    val id: String,              // RINCON_XXXXXXXXXXXXXX (playerId)
    val householdId: String,
    val name: String,
    val ipAddress: String,
    val model: String,
    val modelNumber: String,
    val serialNumber: String,
    val softwareVersion: String,
    val hardwareVersion: String,
    val isZoneBridge: Boolean,
    val isZonePlayer: Boolean,
    val websocketUrl: String
) : Parcelable

/**
 * Response from the Sonos local API discovery endpoint.
 * GET https://<ip>:1443/api/v1/players/local/info
 */
@Parcelize
data class SonosDiscoveryInfo(
    val playerId: String,
    val householdId: String,
    val websocketUrl: String,
    val apiVersion: String? = null
) : Parcelable
