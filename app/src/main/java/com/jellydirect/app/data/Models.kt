package com.jellydirect.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class UserData(
    @SerialName("PlaybackPositionTicks") val positionTicks: Long = 0,
    @SerialName("PlayCount") val playCount: Int = 0,
    @SerialName("IsFavorite") val isFavorite: Boolean = false,
    @SerialName("Played") val played: Boolean = false,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("UnplayedItemCount") val unplayedCount: Int? = null,
)

@Serializable
data class MediaStream(
    @SerialName("Index") val index: Int = 0,
    @SerialName("Type") val type: String = "",
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("IsExternal") val isExternal: Boolean = false,
    @SerialName("IsDefault") val isDefault: Boolean = false,
    @SerialName("IsTextSubtitleStream") val isTextSubtitle: Boolean = false,
    @SerialName("DeliveryUrl") val deliveryUrl: String? = null,
)

@Serializable
data class MediaSource(
    @SerialName("Id") val id: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("Protocol") val protocol: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("IsRemote") val isRemote: Boolean = false,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("LiveStreamId") val liveStreamId: String? = null,
    @SerialName("RequiresClosing") val requiresClosing: Boolean = false,
    @SerialName("RequiresOpening") val requiresOpening: Boolean = false,
    @SerialName("MediaStreams") val streams: List<MediaStream> = emptyList(),
)

@Serializable
data class Item(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
    @SerialName("Type") val type: String = "",
    @SerialName("MediaType") val mediaType: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("IsFolder") val isFolder: Boolean = false,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("CommunityRating") val rating: Double? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Genres") val genres: List<String> = emptyList(),
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("SeasonName") val seasonName: String? = null,
    @SerialName("ParentIndexNumber") val parentIndex: Int? = null,
    @SerialName("IndexNumber") val index: Int? = null,
    @SerialName("ChildCount") val childCount: Int? = null,
    @SerialName("ChannelNumber") val channelNumber: String? = null,
    @SerialName("ChannelId") val channelId: String? = null,
    @SerialName("StartDate") val startDate: String? = null,
    @SerialName("EndDate") val endDate: String? = null,
    @SerialName("CurrentProgram") val currentProgram: Item? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String> = emptyMap(),
    @SerialName("BackdropImageTags") val backdropTags: List<String> = emptyList(),
    @SerialName("ParentBackdropItemId") val parentBackdropItemId: String? = null,
    @SerialName("ParentBackdropImageTags") val parentBackdropTags: List<String> = emptyList(),
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryTag: String? = null,
    @SerialName("UserData") val userData: UserData? = null,
    @SerialName("MediaSources") val mediaSources: List<MediaSource> = emptyList(),
    @SerialName("Path") val path: String? = null,
)

@Serializable
data class ItemsResult(
    @SerialName("Items") val items: List<Item> = emptyList(),
    @SerialName("TotalRecordCount") val total: Int = 0,
)

@Serializable
data class AuthUser(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
)

@Serializable
data class AuthResult(
    @SerialName("User") val user: AuthUser = AuthUser(),
    @SerialName("AccessToken") val token: String = "",
    @SerialName("ServerId") val serverId: String? = null,
)

@Serializable
data class PublicInfo(
    @SerialName("ServerName") val serverName: String = "",
    @SerialName("Version") val version: String = "",
    @SerialName("Id") val id: String = "",
)

@Serializable
data class PublicUser(
    @SerialName("Id") val id: String = "",
    @SerialName("Name") val name: String = "",
)

@Serializable
data class QuickConnectState(
    @SerialName("Secret") val secret: String = "",
    @SerialName("Code") val code: String = "",
    @SerialName("Authenticated") val authenticated: Boolean = false,
)

@Serializable
data class PlaybackInfoResponse(
    @SerialName("MediaSources") val mediaSources: List<MediaSource> = emptyList(),
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
)

/** A saved sign-in: one user on one server. Tokens are stored in app-private storage. */
@Serializable
data class Account(
    val serverUrl: String,
    val serverName: String,
    val serverId: String,
    val userId: String,
    val userName: String,
    val token: String,
) {
    val key: String get() = "$serverUrl|$userId"
}

enum class StreamMode { DIRECT_URL, SERVER }

enum class HwMode { AUTO, OFF }

// ---------------------------------------------------------------------------------------------
// Item helpers
// ---------------------------------------------------------------------------------------------

private val PLAYABLE = setOf("Movie", "Episode", "Video", "MusicVideo", "Trailer", "TvChannel", "Program", "Recording")

val Item.isPlayable: Boolean get() = type in PLAYABLE
val Item.isEpisode: Boolean get() = type == "Episode"
val Item.isChannel: Boolean get() = type == "TvChannel"

/** Season/episode label such as "S1:E4", or null when the numbers are missing. */
val Item.episodeCode: String?
    get() = if (parentIndex != null && index != null) "S$parentIndex:E$index" else index?.let { "E$it" }

val Item.runtimeMs: Long get() = (runTimeTicks ?: 0L) / 10_000L

val Item.positionMs: Long get() = (userData?.positionTicks ?: 0L) / 10_000L

/** 0f..1f resume progress, or 0f if not started / finished. */
val Item.progress: Float
    get() {
        val total = runTimeTicks ?: return 0f
        val pos = userData?.positionTicks ?: return 0f
        if (total <= 0 || pos <= 0) return 0f
        return (pos.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    }

fun Item.parsedStart(): Instant? = parseInstant(startDate)
fun Item.parsedEnd(): Instant? = parseInstant(endDate)

fun parseInstant(s: String?): Instant? = try {
    if (s.isNullOrBlank()) null else Instant.parse(s)
} catch (_: Exception) {
    null
}

fun formatDuration(ms: Long): String {
    val totalMin = ms / 60_000L
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}

/** 1:02:03 / 02:03 clock formatting for the player. */
fun formatClock(ms: Long): String {
    val s = (ms.coerceAtLeast(0L)) / 1000L
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}
