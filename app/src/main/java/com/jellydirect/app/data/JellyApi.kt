package com.jellydirect.app.data

import android.os.Build
import com.jellydirect.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
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
        // Default is 5 parallel requests per host, which makes posters queue up behind each other on a long-latency link.
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
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
    /** Further routes to try, in order, after [fallbackUrl]. */
    val moreFallbacks: List<String> = emptyList(),
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

    // Callers run on the main thread; the body is read from the socket, so all of it happens on IO.
    private suspend fun exec(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).await().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ApiException(resp.code, "HTTP ${resp.code} ${resp.message}".trim())
            }
            text
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
            "enableImageTypes" to "Primary,Backdrop,Thumb",
            "imageTypeLimit" to "1",
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
    suspend fun resolvePlayback(item: Item, mode: StreamMode, startTicks: Long, trace: ((String) -> Unit)? = null): Playable {
        val live = item.isChannel || item.type == "Program"
        val itemId = if (item.type == "Program") (item.channelId ?: item.id) else item.id
        if (!live && mode == StreamMode.DIRECT_URL) {
            val s = item.mediaSources.firstOrNull()
            val d = s?.path?.takeIf { isUsableRemoteUrl(it) }
            if (s != null && d != null) {
                // Skips the PlaybackInfo call, which can make a weak server probe the remote file first.
                return Playable(item, s, null, d, staticStreamUrl(item.id, s, null), true, null, false)
            }
        }
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
        trace?.invoke("     PlaybackInfo: error=${info?.errorCode} sources=${info?.mediaSources?.size} -> id=${source.id} protocol=${source.protocol} remote=${source.isRemote} container=${source.container} liveStreamId=${source.liveStreamId} path=${maskUrl(source.path)}")

        val staticUrl = (if (live) liveRelayUrl(source) else null) ?: staticStreamUrl(itemId, source, sessionId)
        // A path into the server's own live relay needs our api key, so it is never a "direct" link.
        val direct = source.path?.takeIf { isUsableRemoteUrl(it) && !it.contains("/LiveTv/LiveStreamFiles/") }

        val directOk = mode == StreamMode.DIRECT_URL
        val hls = live && (source.container.equals("hls", true) || source.path?.contains(".m3u8", true) == true)
        // Routes in order: straight from the source first, then through the server.
        val routes = buildList<Pair<String, Boolean>> {
            if (directOk && direct != null) add(direct to true)
            // The server's own channel proxy is reported as 127.0.0.1; the phone can try it on the server's public address.
            if (directOk && live) proxyOnServerHost(source.path)?.let { add(it to true) }
            // HLS channels: Jellyfin's raw relay only returns the playlist text, so its HLS remux comes before it.
            if (hls) add(hlsRemuxUrl(itemId, source, sessionId) to false)
            add(staticUrl to false)
        }
        trace?.invoke("     routes: " + routes.joinToString(" | ") { (u, d) -> (if (d) "direct " else "server ") + maskUrl(u) })
        return Playable(
            item = item,
            source = source,
            playSessionId = sessionId,
            url = routes[0].first,
            fallbackUrl = routes.getOrNull(1)?.first,
            isDirectUrl = routes[0].second,
            liveStreamId = source.liveStreamId,
            isLive = live,
            moreFallbacks = routes.drop(2).map { it.first },
        )
    }

    private fun proxyOnServerHost(path: String?): String? {
        val p = try { path?.trim()?.toHttpUrl() } catch (_: Exception) { null } ?: return null
        val loop = p.host == "localhost" || p.host.startsWith("127.") || p.host == "0.0.0.0" || p.host == "::1"
        if (!loop) return null
        val base = try { baseUrl.toHttpUrl() } catch (_: Exception) { return null }
        return p.newBuilder().host(base.host).build().toString()
    }

    /** Jellyfin's own HLS endpoint: it copies the stream when it can, so the CPU cost on the server stays small. */
    fun hlsRemuxUrl(itemId: String, source: MediaSource, playSessionId: String?): String {
        val b = url("/Videos/$itemId/master.m3u8").newBuilder()
        source.id?.let { b.addQueryParameter("MediaSourceId", it) }
        source.liveStreamId?.let { b.addQueryParameter("LiveStreamId", it) }
        playSessionId?.let { b.addQueryParameter("PlaySessionId", it) }
        b.addQueryParameter("DeviceId", deviceId)
        b.addQueryParameter("VideoCodec", "h264,hevc,mpeg2video")
        b.addQueryParameter("AudioCodec", "aac,mp3,ac3,eac3,mp2")
        b.addQueryParameter("SegmentContainer", "ts")
        b.addQueryParameter("MinSegments", "1")
        b.addQueryParameter("BreakOnNonKeyFrames", "true")
        b.addQueryParameter("TranscodingMaxAudioChannels", "6")
        token?.let { b.addQueryParameter("api_key", it) }
        return b.build().toString()
    }

    /** Partly masked address for diagnostics: keeps host/shape, hides path segments and query values. */
    fun maskUrl(u: String?): String {
        if (u.isNullOrBlank()) return "(none)"
        return try {
            val h = u.trim().toHttpUrl()
            val segs = h.pathSegments
            val first = segs.firstOrNull().orEmpty().let { if (it.length <= 14 && it.all { c -> c.isLetter() }) it else "..." }
            val ext = segs.lastOrNull().orEmpty().substringAfterLast('.', "").take(5)
            "${h.scheme}://${h.host}:${h.port}/$first" + (if (segs.size > 1) "/...(${segs.size} parts)" else "") +
                (if (ext.isNotEmpty()) " ext=$ext" else "") +
                (if (h.querySize > 0) " query=${h.queryParameterNames.joinToString(",")}" else "")
        } catch (_: Exception) {
            "(unparseable, ${u.length} chars)"
        }
    }

    private suspend fun probe(u: String): String = withContext(Dispatchers.IO) {
        val c = SharedHttp.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()
        val req = Request.Builder().url(u).header("User-Agent", "VLC/3.0.20 LibVLC/3.0.20").get().build()
        c.newCall(req).await().use { r ->
            val buf = ByteArray(4096)
            val n = try { r.body?.byteStream()?.read(buf) ?: -1 } catch (e: IOException) { -2 }
            val head = "HTTP ${r.code} type=${r.header("Content-Type")} bytes=$n" + (if (n > 0) " firstByte=0x" + "%02x".format(buf[0]) else "")
            if (n > 0 && buf[0] == 0x23.toByte()) {
                val lines = String(buf, 0, n).lines().filter { it.isNotBlank() }.take(14).joinToString("\n          ") { l ->
                    when {
                        l.startsWith("#") -> l.take(70)
                        l.startsWith("http", true) -> "URL " + maskUrl(l.trim())
                        else -> "relative " + (if (l.startsWith("/")) "(absolute path) " else "") + "ext=" + l.substringBefore('?').substringAfterLast('.', "none").take(5) + " len=${l.length}"
                    }
                }
                head + "\n     playlist:\n          " + lines
            } else head
        }
    }

    /** Walks one live channel through every step the player uses and reports each result. */
    suspend fun diagnoseLive(filter: String): String {
        val sb = StringBuilder()
        fun line(s: String) { sb.appendLine(s) }
        suspend fun <T> step(name: String, block: suspend () -> T): T? {
            val t0 = System.currentTimeMillis()
            return try {
                block().also { line("OK   $name (${System.currentTimeMillis() - t0} ms)") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                line("FAIL $name: ${e.message ?: e.javaClass.simpleName} (${System.currentTimeMillis() - t0} ms)")
                null
            }
        }
        val info = step("Server info") { publicInfo() }
        info?.let { line("     Jellyfin ${it.version}") }
        val chans = step("List channels") { channels(limit = 100).items }
        if (chans.isNullOrEmpty()) { line("No channels to test."); return sb.toString() }
        val ch = chans.firstOrNull { filter.isNotBlank() && it.name.contains(filter, true) } ?: chans.first()
        line("     ${chans.size} channels; testing: ${ch.channelNumber.orEmpty()} ${ch.name}")
        val item = step("Fetch channel item") { item(ch.id) }
        item?.mediaSources?.forEachIndexed { i, s ->
            line("     item source $i: protocol=${s.protocol} remote=${s.isRemote} requiresOpening=${s.requiresOpening} path=${maskUrl(s.path)}")
        }
        val p = step("Resolve playback") { resolvePlayback(item ?: ch, StreamMode.DIRECT_URL, 0L, trace = { line(it) }) }
        if (p == null) return sb.toString()
        val urls = linkedMapOf<String, String>()
        (listOfNotNull(p.url, p.fallbackUrl) + p.moreFallbacks).forEachIndexed { i, u -> urls["route ${i + 1}"] = u }
        urls["server static stream"] = staticStreamUrl(p.item.id, p.source, p.playSessionId)
        val seen = HashSet<String>()
        for ((label, u) in urls) {
            if (!seen.add(u)) continue
            step("Probe $label: ${maskUrl(u)}") { probe(u).also { line("     $it") } }
        }
        p.liveStreamId?.let { closeLiveStream(it) }
        return sb.toString()
    }

    /** Jellyfin reports its live relay as a loopback address; swap in the address we actually reached it on. */
    private fun liveRelayUrl(source: MediaSource): String? {
        val u = try { source.path?.trim()?.toHttpUrl() } catch (_: Exception) { null } ?: return null
        if (!u.encodedPath.startsWith("/LiveTv/LiveStreamFiles/")) return null
        return url(u.encodedPath).newBuilder().addQueryParameter("api_key", token.orEmpty()).build().toString()
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
