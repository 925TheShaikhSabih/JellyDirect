package com.jellydirect.app.data

import android.os.Build
import com.jellydirect.app.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/** One shared client: one connection pool for the API *and* for image loading. */
val SharedHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

class ApiException(val code: Int, message: String) : IOException(message)

/** Everything the player needs to start (and report on) one playback. */
data class Playable(
    val item: Item,
    val source: MediaSource,
    val playSessionId: String?,
    /** What libVLC opens first. */
    val url: String,
    /** Tried if [url] fails to open (the Jellyfin static-stream proxy), or null. */
    val fallbackUrl: String?,
    val isDirectUrl: Boolean,
    val liveStreamId: String?,
    val isLive: Boolean,
)

/** Query for the /Items endpoint. */
data class ItemQuery(
    val parentId: String? = null,
    val types: String? = null,
    val recursive: Boolean = false,
    val sortBy: String = "SortName",
    val sortOrder: String = "Ascending",
    val genre: String? = null,
    val studioId: String? = null,
    val year: Int? = null,
    val filters: String? = null,
    val search: String? = null,
    val start: Int = 0,
    val limit: Int = 60,
)

private const val F_LIST = "Overview,Genres,ChildCount"
private const val F_DETAIL = "Overview,Genres,MediaSources,MediaStreams,Path,ChildCount"

class JellyApi(
    val baseUrl: String,
    val deviceId: String,
    var token: String? = null,
    var userId: String? = null,
    private val http: OkHttpClient = SharedHttp,
) {
    // ---------------------------------------------------------------------------------------
    // Plumbing
    // ---------------------------------------------------------------------------------------

    private fun authHeader(): String {
        val device = Build.MODEL.orEmpty().filter { it.code in 32..126 && it != '"' }.ifBlank { "Android" }
        val sb = StringBuilder()
        sb.append("MediaBrowser Client=\"JellyDirect\", Device=\"").append(device)
        sb.append("\", DeviceId=\"").append(deviceId)
        sb.append("\", Version=\"").append(BuildConfig.VERSION_NAME).append("\"")
        token?.let { sb.append(", Token=\"").append(it).append("\"") }
        return sb.toString()
    }

    private fun url(path: String, params: Map<String, String?> = emptyMap()): HttpUrl {
        val b = (baseUrl + path).toHttpUrl().newBuilder()
        for ((k, v) in params) if (v != null) b.addQueryParameter(k, v)
        return b.build()
    }

    private fun builder(u: HttpUrl): Request.Builder =
        Request.Builder().url(u).header("Authorization", authHeader()).header("Accept", "application/json")

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (cont.isActive) cont.resume(response) else response.close()
            }
        })
        cont.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {
            }
        }
    }

    private suspend fun exec(req: Request): String {
        http.newCall(req).await().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, "HTTP ${resp.code} ${resp.message}".trim())
            }
            return text
        }
    }

    private suspend fun getText(path: String, params: Map<String, String?> = emptyMap()): String =
        exec(builder(url(path, params)).get().build())

    private suspend inline fun <reified T> get(path: String, params: Map<String, String?> = emptyMap()): T =
        AppJson.decodeFromString<T>(getText(path, params))

    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private suspend fun postText(
        path: String,
        params: Map<String, String?> = emptyMap(),
        body: String = "{}",
    ): String = exec(builder(url(path, params)).post(body.toRequestBody(jsonType)).build())

    private suspend inline fun <reified T> post(
        path: String,
        params: Map<String, String?> = emptyMap(),
        body: String = "{}",
    ): T = AppJson.decodeFromString<T>(postText(path, params, body))

    private suspend fun deleteText(path: String): String =
        exec(builder(url(path)).delete().build())

    private fun uid(): String = userId ?: throw IllegalStateException("Not signed in")

    // ---------------------------------------------------------------------------------------
    // Server + sign-in
    // ---------------------------------------------------------------------------------------

    suspend fun publicInfo(): PublicInfo = get("/System/Info/Public")

    suspend fun publicUsers(): List<PublicUser> = get("/Users/Public")

    suspend fun login(name: String, password: String): AuthResult =
        post(
            "/Users/AuthenticateByName",
            body = buildJsonObject {
                put("Username", name)
                put("Pw", password)
            }.toString(),
        )

    suspend fun quickConnectEnabled(): Boolean =
        try {
            getText("/QuickConnect/Enabled").trim().equals("true", ignoreCase = true)
        } catch (_: Exception) {
            false
        }

    suspend fun quickConnectInitiate(): QuickConnectState = post("/QuickConnect/Initiate")

    suspend fun quickConnectCheck(secret: String): QuickConnectState =
        get("/QuickConnect/Connect", mapOf("secret" to secret))

    suspend fun quickConnectLogin(secret: String): AuthResult =
        post(
            "/Users/AuthenticateWithQuickConnect",
            body = buildJsonObject { put("Secret", secret) }.toString(),
        )

    /** Cheap call that proves the saved token still works. */
    suspend fun validateSession() {
        getText("/Users/${uid()}")
    }

    suspend fun logout() {
        try {
            postText("/Sessions/Logout")
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------------------------------
    // Browsing
    // ---------------------------------------------------------------------------------------

    suspend fun views(): List<Item> = get<ItemsResult>("/Users/${uid()}/Views").items

    suspend fun resume(limit: Int = 20): List<Item> = get<ItemsResult>(
        "/Users/${uid()}/Items/Resume",
        mapOf(
            "limit" to "$limit",
            "mediaTypes" to "Video",
            "fields" to F_LIST,
            "enableTotalRecordCount" to "false",
        ),
    ).items

    suspend fun nextUp(limit: Int = 20, seriesId: String? = null): List<Item> = get<ItemsResult>(
        "/Shows/NextUp",
        mapOf(
            "userId" to uid(),
            "limit" to "$limit",
            "seriesId" to seriesId,
            "fields" to F_LIST,
            "enableResumable" to "false",
            "enableTotalRecordCount" to "false",
        ),
    ).items

    suspend fun latest(parentId: String, limit: Int = 20): List<Item> = get<List<Item>>(
        "/Users/${uid()}/Items/Latest",
        mapOf(
            "parentId" to parentId,
            "limit" to "$limit",
            "fields" to F_LIST,
            "groupItems" to "true",
        ),
    )

    suspend fun item(id: String): Item =
        get("/Users/${uid()}/Items/$id", mapOf("fields" to F_DETAIL))

    suspend fun items(q: ItemQuery): ItemsResult = get(
        "/Items",
        mapOf(
            "userId" to uid(),
            "parentId" to q.parentId,
            "includeItemTypes" to q.types,
            "recursive" to q.recursive.toString(),
            "sortBy" to q.sortBy,
            "sortOrder" to q.sortOrder,
            "genres" to q.genre,
            "studioIds" to q.studioId,
            "years" to q.year?.toString(),
            "filters" to q.filters,
            "searchTerm" to q.search,
            "startIndex" to "${q.start}",
            "limit" to "${q.limit}",
            "fields" to F_LIST,
        ),
    )

    suspend fun seasons(seriesId: String): List<Item> = get<ItemsResult>(
        "/Shows/$seriesId/Seasons",
        mapOf("userId" to uid(), "fields" to "ChildCount"),
    ).items

    suspend fun episodes(seriesId: String, seasonId: String?): List<Item> = get<ItemsResult>(
        "/Shows/$seriesId/Episodes",
        mapOf("userId" to uid(), "seasonId" to seasonId, "fields" to "Overview"),
    ).items

    /** The episode that follows [currentEpisodeId], or null if it is the last one. */
    suspend fun episodeAfter(seriesId: String, currentEpisodeId: String): Item? = get<ItemsResult>(
        "/Shows/$seriesId/Episodes",
        mapOf(
            "userId" to uid(),
            "startItemId" to currentEpisodeId,
            "limit" to "2",
            "fields" to "Overview",
        ),
    ).items.firstOrNull { it.id != currentEpisodeId }

    suspend fun genres(parentId: String?, types: String?): List<Item> = get<ItemsResult>(
        "/Genres",
        mapOf(
            "userId" to uid(),
            "parentId" to parentId,
            "includeItemTypes" to types,
            "sortBy" to "SortName",
        ),
    ).items

    suspend fun studios(parentId: String?, types: String?): List<Item> = get<ItemsResult>(
        "/Studios",
        mapOf(
            "userId" to uid(),
            "parentId" to parentId,
            "includeItemTypes" to types,
            "sortBy" to "SortName",
        ),
    ).items

    suspend fun setFavorite(id: String, favorite: Boolean) {
        val path = "/Users/${uid()}/FavoriteItems/$id"
        if (favorite) postText(path) else deleteText(path)
    }

    suspend fun setPlayed(id: String, played: Boolean) {
        val path = "/Users/${uid()}/PlayedItems/$id"
        if (played) postText(path) else deleteText(path)
    }

    // ---------------------------------------------------------------------------------------
    // Live TV
    // ---------------------------------------------------------------------------------------

    suspend fun channels(start: Int = 0, limit: Int = 200, favoritesOnly: Boolean = false): ItemsResult = get(
        "/LiveTv/Channels",
        mapOf(
            "userId" to uid(),
            "addCurrentProgram" to "true",
            "startIndex" to "$start",
            "limit" to "$limit",
            "sortBy" to "SortName",
            "isFavorite" to if (favoritesOnly) "true" else null,
        ),
    )

    suspend fun programs(channelIds: List<String>, from: Instant, to: Instant): List<Item> {
        if (channelIds.isEmpty()) return emptyList()
        return get<ItemsResult>(
            "/LiveTv/Programs",
            mapOf(
                "userId" to uid(),
                "channelIds" to channelIds.joinToString(","),
                "minEndDate" to from.toString(),
                "maxStartDate" to to.toString(),
                "sortBy" to "StartDate",
                "enableTotalRecordCount" to "false",
            ),
        ).items
    }

    suspend fun closeLiveStream(liveStreamId: String) {
        try {
            postText("/LiveStreams/Close", mapOf("liveStreamId" to liveStreamId))
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------------------------------
    // Playback
    // ---------------------------------------------------------------------------------------

    /**
     * Resolves how to play [item].
     *
     * In [StreamMode.DIRECT_URL] (the default) the URL stored in the .strm file is handed straight to the
     * player, so the Jellyfin server never carries the video bytes. The server's own raw "static" stream
     * (no transcoding, ever) is the fallback, and also what is used in [StreamMode.SERVER].
     */
    suspend fun resolvePlayback(item: Item, mode: StreamMode, startTicks: Long): Playable {
        val live = item.isChannel || item.type == "Program"
        val itemId = if (item.type == "Program") (item.channelId ?: item.id) else item.id
        val info: PlaybackInfoResponse? = try { post(
            "/Items/$itemId/PlaybackInfo",
            mapOf(
                "userId" to uid(),
                "startTimeTicks" to if (startTicks > 0) "$startTicks" else null,
                "autoOpenLiveStream" to if (live) "true" else null,
                "isPlayback" to "true",
                "enableDirectPlay" to "true",
                "enableDirectStream" to "true",
                "enableTranscoding" to "false",
            ),
        ) } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (item.mediaSources.isEmpty()) throw e
            null
        }
        // An error code here usually means "the server would have to transcode". We never ask for that,
        // so fall back to the item's own source and play it raw.
        val source = info?.mediaSources?.firstOrNull() ?: item.mediaSources.firstOrNull()
            ?: throw IOException(info?.errorCode?.let { "Server refused playback ($it)" } ?: "No playable source for this item")
        val sessionId = info?.playSessionId

        val staticUrl = staticStreamUrl(itemId, source, sessionId)
        val direct = source.path?.takeIf { isUsableRemoteUrl(it) }

        val useDirect = mode == StreamMode.DIRECT_URL && direct != null
        return Playable(
            item = item,
            source = source,
            playSessionId = sessionId,
            url = if (useDirect) direct!! else staticUrl,
            fallbackUrl = if (useDirect) staticUrl else null,
            isDirectUrl = useDirect,
            liveStreamId = source.liveStreamId,
            isLive = live,
        )
    }

    private fun isUsableRemoteUrl(path: String): Boolean {
        val p = path.trim()
        if (!p.startsWith("http://", true) && !p.startsWith("https://", true)) return false
        val host = p.toHttpUrlOrNullHost() ?: return false
        // A "remote" path that points back at the server's own machine is not reachable from the phone.
        return host != "localhost" && !host.startsWith("127.") && host != "0.0.0.0" && host != "::1"
    }

    private fun String.toHttpUrlOrNullHost(): String? = try {
        trim().toHttpUrl().host
    } catch (_: Exception) {
        null
    }

    fun staticStreamUrl(itemId: String, source: MediaSource, playSessionId: String?): String {
        val b = url("/Videos/$itemId/stream").newBuilder()
        b.addQueryParameter("static", "true")
        source.id?.let { b.addQueryParameter("mediaSourceId", it) }
        source.liveStreamId?.let { b.addQueryParameter("liveStreamId", it) }
        playSessionId?.let { b.addQueryParameter("playSessionId", it) }
        b.addQueryParameter("deviceId", deviceId)
        token?.let { b.addQueryParameter("api_key", it) }
        return b.build().toString()
    }

    /** URL for a sidecar/external subtitle track, or null for tracks that are inside the container. */
    fun externalSubtitleUrl(itemId: String, source: MediaSource, s: MediaStream): String? {
        if (!s.isExternal && s.deliveryUrl == null) return null
        s.deliveryUrl?.let { rel ->
            val abs = if (rel.startsWith("http", true)) rel else baseUrl + (if (rel.startsWith("/")) rel else "/$rel")
            return if (abs.contains("api_key=")) abs else abs + (if (abs.contains("?")) "&" else "?") + "api_key=${token.orEmpty()}"
        }
        val sid = source.id ?: return null
        val ext = when (val c = s.codec?.lowercase()) {
            null, "" -> "srt"
            "subrip" -> "srt"
            "webvtt" -> "vtt"
            else -> c
        }
        return url("/Videos/$itemId/$sid/Subtitles/${s.index}/0/Stream.$ext")
            .newBuilder().addQueryParameter("api_key", token.orEmpty()).build().toString()
    }

    private fun JsonObjectBuilder.putCommon(p: Playable, positionTicks: Long) {
        put("ItemId", p.item.id)
        p.source.id?.let { put("MediaSourceId", it) }
        p.playSessionId?.let { put("PlaySessionId", it) }
        p.liveStreamId?.let { put("LiveStreamId", it) }
        put("PositionTicks", positionTicks)
        put("CanSeek", !p.isLive)
        put("PlayMethod", if (p.isDirectUrl) "DirectPlay" else "DirectStream")
    }

    suspend fun reportStart(p: Playable, positionTicks: Long) {
        postText("/Sessions/Playing", body = buildJsonObject {
            putCommon(p, positionTicks)
            put("IsPaused", false)
        }.toString())
    }

    suspend fun reportProgress(p: Playable, positionTicks: Long, paused: Boolean) {
        postText("/Sessions/Playing/Progress", body = buildJsonObject {
            putCommon(p, positionTicks)
            put("IsPaused", paused)
        }.toString())
    }

    suspend fun reportStopped(p: Playable, positionTicks: Long) {
        postText("/Sessions/Playing/Stopped", body = buildJsonObject {
            putCommon(p, positionTicks)
        }.toString())
    }

    // ---------------------------------------------------------------------------------------
    // Images
    // ---------------------------------------------------------------------------------------

    fun imageUrl(itemId: String, type: String, tag: String?, maxWidth: Int, quality: Int = 88): String {
        val b = url("/Items/$itemId/Images/$type").newBuilder()
        b.addQueryParameter("maxWidth", "$maxWidth")
        b.addQueryParameter("quality", "$quality")
        tag?.let { b.addQueryParameter("tag", it) }
        return b.build().toString()
    }

    /** Portrait poster (2:3). Episodes fall back to their series poster. */
    fun poster(item: Item, maxWidth: Int = 360): String? {
        item.imageTags["Primary"]?.let { return imageUrl(item.id, "Primary", it, maxWidth) }
        val sid = item.seriesId
        val st = item.seriesPrimaryTag
        if (sid != null && st != null) return imageUrl(sid, "Primary", st, maxWidth)
        return null
    }

    /** Landscape art (16:9) for rows such as Continue Watching. */
    fun landscape(item: Item, maxWidth: Int = 560): String? {
        item.imageTags["Thumb"]?.let { return imageUrl(item.id, "Thumb", it, maxWidth) }
        if (item.type == "Episode" || item.type == "Program" || item.type == "Video") {
            item.imageTags["Primary"]?.let { return imageUrl(item.id, "Primary", it, maxWidth) }
        }
        return backdrop(item, maxWidth) ?: poster(item, maxWidth)
    }

    /** Wide hero/backdrop art. */
    fun backdrop(item: Item, maxWidth: Int = 1280): String? {
        item.backdropTags.firstOrNull()?.let { return imageUrl(item.id, "Backdrop/0", it, maxWidth) }
        val pid = item.parentBackdropItemId
        item.parentBackdropTags.firstOrNull()?.let { tag ->
            if (pid != null) return imageUrl(pid, "Backdrop/0", tag, maxWidth)
        }
        return null
    }

    fun channelLogo(item: Item, maxWidth: Int = 200): String? =
        item.imageTags["Primary"]?.let { imageUrl(item.id, "Primary", it, maxWidth) }
}
