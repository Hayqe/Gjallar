package com.cappielloantonio.tempo.sonos.models

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Representation of a Sonos group (multiple speakers playing in sync).
 * Sonos players are always in groups, even if the group has only one player.
 */
@Parcelize
data class SonosGroup(
    val id: String,
    val name: String,
    val householdId: String,
    val coordinatorId: String,      // ID of the group coordinator (leader)
    val playerIds: List<String>,    // IDs of all players in this group
    val areaIds: List<String> = emptyList()
) : Parcelable {
    // Helper property to check if this is a multi-room group
    val isMultiRoom: Boolean get() = playerIds.size > 1
}

/**
 * Response from the Sonos groups API.
 * GET https://<ip>:1443/api/v1/households/{householdId}/groups
 */
@Parcelize
data class SonosGroupsResponse(
    val groups: List<SonosGroupData>,
    val players: List<SonosPlayerData>
) : Parcelable

@Parcelize
data class SonosGroupData(
    val id: String,
    val name: String,
    val householdId: String,
    val coordinatorId: String,
    val playerIds: List<String>,
    val areaIds: List<String> = emptyList(),
    val playbackState: String? = null
) : Parcelable

@Parcelize
data class SonosPlayerData(
    val id: String,
    val name: String,
    val householdId: String,
    val roomName: String? = null,
    val icon: String? = null,
    val model: String? = null,
    val modelNumber: String? = null,
    val serialNumber: String? = null,
    val softwareVersion: String? = null,
    val hardwareVersion: String? = null
) : Parcelable

/**
 * Event model for Sonos WebSocket events.
 */
/**
 * Event model for Sonos WebSocket events.
 * Not Parcelable as Map<String, Any> is not supported by @Parcelize
 */
data class SonosEvent(
    val type: String,           // e.g., "playbackStatus", "groupVolume", "groups"
    val data: Map<String, Any>
)
