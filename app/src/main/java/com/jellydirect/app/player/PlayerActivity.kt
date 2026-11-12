package com.jellydirect.app.player

import android.app.PictureInPictureParams
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AspectRatio
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.jellydirect.app.App
import com.jellydirect.app.data.HwMode
import com.jellydirect.app.data.Item
import com.jellydirect.app.data.JellyApi
import com.jellydirect.app.data.Playable
import com.jellydirect.app.data.Settings
import com.jellydirect.app.data.SubMode
import com.jellydirect.app.data.episodeCode
import com.jellydirect.app.data.formatClock
import com.jellydirect.app.data.progress
import com.jellydirect.app.ui.Accent
import com.jellydirect.app.ui.AlertDialog
import com.jellydirect.app.ui.Button
import com.jellydirect.app.ui.CircularProgressIndicator
import com.jellydirect.app.ui.Icon
import com.jellydirect.app.ui.IconButton
import com.jellydirect.app.ui.Slider
import com.jellydirect.app.ui.SliderDefaults
import com.jellydirect.app.ui.Text
import com.jellydirect.app.ui.TextButton
import com.jellydirect.app.ui.Type
import com.jellydirect.app.ui.JellyTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

/**
 * Full-screen player. libVLC decodes everything on the device (hardware first, software fallback),
 * so the Jellyfin server never transcodes.
 */
class PlayerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_FROM_START = "fromStart"
    }

    private lateinit var api: JellyApi
    private lateinit var vlc: LibVLC
    private lateinit var mp: MediaPlayer
    private var ready = false
    private val viewReady = CompletableDeferred<Unit>()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings: Settings get() = (application as App).store.settings.value
    private var isTv = false

    // UI state
    private var title by mutableStateOf("")
    private var subtitle by mutableStateOf("")
    private var posMs by mutableLongStateOf(0L)
    private var lenMs by mutableLongStateOf(0L)
    private var playing by mutableStateOf(false)
    private var buffering by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var controls by mutableStateOf(true)
    private var poke by mutableIntStateOf(0)
    private var isLive by mutableStateOf(false)
    private var inPip by mutableStateOf(false)
    private var showTracks by mutableStateOf(false)
    private var nextEp by mutableStateOf<Item?>(null)
    private var nextDismissed by mutableStateOf(false)
    private var aspectIdx by mutableIntStateOf(0)
    private var note by mutableStateOf<String?>(null)
    private var hud by mutableStateOf<String?>(null)
    private var trackTick by mutableIntStateOf(0)

    // Playback state
    private var cur: Playable? = null
    private var triedFallback = false
    private var startedReport = false
    private var channels: List<Item> = emptyList()
    private var loadJob: Job? = null
    private var fetchingNext = false
    private var wasPlaying = false

    private val aspects = listOf(
        "Fit" to MediaPlayer.ScaleType.SURFACE_BEST_FIT,
        "Fill screen" to MediaPlayer.ScaleType.SURFACE_FIT_SCREEN,
        "Stretch" to MediaPlayer.ScaleType.SURFACE_FILL,
        "16:9" to MediaPlayer.ScaleType.SURFACE_16_9,
        "4:3" to MediaPlayer.ScaleType.SURFACE_4_3,
        "Original" to MediaPlayer.ScaleType.SURFACE_ORIGINAL,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App
        val a = app.api.value
        val id = intent.getStringExtra(EXTRA_ID)
        if (a == null || id == null) {
            finish(); return
        }
        api = a
        isTv = (getSystemService(UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        vlc = LibVLC(this, arrayListOf("--audio-time-stretch", "--http-reconnect"))
        mp = MediaPlayer(vlc)
        ready = true
        mp.setEventListener { ev ->
            when (ev.type) {
                MediaPlayer.Event.Playing -> { playing = true; buffering = false; onPlaying() }
                MediaPlayer.Event.Paused -> playing = false
                MediaPlayer.Event.Stopped -> playing = false
                MediaPlayer.Event.Buffering -> buffering = ev.buffering < 100f
                MediaPlayer.Event.EndReached -> runOnUiThread { onEnded() }
                MediaPlayer.Event.EncounteredError -> runOnUiThread { onError() }
            }
        }

        setContent { JellyTheme { PlayerUi() } }

        // Poll position, drive "next episode" and progress reporting.
        lifecycleScope.launch {
            var ticks = 0
            while (isActive) {
                delay(500)
                posMs = mp.getTime().coerceAtLeast(0L)
                lenMs = mp.getLength().coerceAtLeast(0L)
                checkNextEpisode()
                if (playing && ++ticks % 20 == 0) report(paused = false)
            }
        }

        play(id, intent.getBooleanExtra(EXTRA_FROM_START, false))
    }

    // ----------------------------------------------------------------------------------------
    // Loading + playback
    // ----------------------------------------------------------------------------------------

    private var lastId: String? = null

    private fun play(id: String, fromStart: Boolean, forceServer: Boolean = false) {
        lastId = id
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            error = null; buffering = true; nextEp = null; nextDismissed = false; triedFallback = false
            finishReport()
            try {
                val item = api.item(id)
                val ticks = if (fromStart || item.progress >= 0.95f) 0L else (item.userData?.positionTicks ?: 0L)
                val p = api.resolvePlayback(item, if (forceServer) com.jellydirect.app.data.StreamMode.SERVER else settings.streamMode, ticks)
                cur = p
                isLive = p.isLive
                title = if (item.type == "Episode") (item.seriesName ?: item.name) else item.name
                subtitle = if (item.type == "Episode") listOfNotNull(item.episodeCode, item.name).joinToString(" · ") else ""
                if (p.isLive && channels.isEmpty()) {
                    lifecycleScope.launch { channels = runCatching { api.channels(limit = 500).items }.getOrDefault(emptyList()) }
                }
                viewReady.await()
                startedReport = false
                open(p.url, ticks / 10_000L)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = "Can't start playback: " + (e.message ?: e.javaClass.simpleName)
                buffering = false
            }
        }
    }

    private fun open(url: String, startMs: Long) {
        val s = settings
        val media = Media(vlc, Uri.parse(url))
        media.setHWDecoderEnabled(s.hw == HwMode.AUTO, false)
        media.addOption(":network-caching=${s.bufferMs}")
        if (startMs > 5000) media.addOption(":start-time=${startMs / 1000}")
        mp.setMedia(media)
        media.release()
        mp.play()
    }

    private fun onPlaying() {
        if (startedReport) return
        startedReport = true
        val p = cur ?: return
        bg.launch { runCatching { api.reportStart(p, posMs * 10_000L) } }
        runOnUiThread {
            p.source.streams.filter { it.type == "Subtitle" && it.isExternal }.forEach { st ->
                api.externalSubtitleUrl(p.item.id, p.source, st)?.let { mp.addSlave(0, Uri.parse(it), false) }
            }
            applyTrackPrefs()
        }
    }

    private fun onError() {
        val p = cur ?: return
        val queue = listOfNotNull(p.fallbackUrl) + p.moreFallbacks
        val fb = queue.firstOrNull()
        if (fb != null) {
            note = "That route failed, trying another"
            cur = p.copy(url = fb, isDirectUrl = false, fallbackUrl = queue.getOrNull(1), moreFallbacks = queue.drop(2))
            open(fb, posMs)
        } else {
            buffering = false
            val host = runCatching { Uri.parse(p.url).host }.getOrNull().orEmpty()
            error = "Couldn't open the stream (" + (if (p.isDirectUrl) "direct link on $host" else "server stream from $host") +
                "). The source may be offline, blocked, or its link may have expired."
        }
    }

    private fun onEnded() {
        val p = cur
        if (p == null || p.isLive) { finish(); return }
        posMs = lenMs
        val item = p.item
        if (settings.autoNext && item.type == "Episode" && item.seriesId != null && !nextDismissed) {
            lifecycleScope.launch {
                val n = nextEp ?: runCatching { api.episodeAfter(item.seriesId, item.id) }.getOrNull()
                if (n != null) play(n.id, true) else finish()
            }
        } else finish()
    }

    private fun checkNextEpisode() {
        val p = cur ?: return
        val item = p.item
        if (!settings.autoNext || item.type != "Episode" || item.seriesId == null || lenMs <= 0 || fetchingNext || nextEp != null) return
        if (lenMs - posMs < 60_000L) {
            fetchingNext = true
            lifecycleScope.launch {
                nextEp = runCatching { api.episodeAfter(item.seriesId, item.id) }.getOrNull()
                fetchingNext = false
            }
        }
    }

    private fun report(paused: Boolean) {
        val p = cur ?: return
        val pos = posMs * 10_000L
        bg.launch { runCatching { api.reportProgress(p, pos, paused) } }
    }

    private fun finishReport() {
        val p = cur ?: return
        cur = null
        val pos = posMs * 10_000L
        bg.launch {
            runCatching { api.reportStopped(p, pos) }
            p.liveStreamId?.let { runCatching { api.closeLiveStream(it) } }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Tracks
    // ----------------------------------------------------------------------------------------

    private fun names(code: String): List<String> {
        if (code.isBlank()) return emptyList()
        val n = Locale(code).getDisplayLanguage(Locale.ENGLISH)
        return listOfNotNull(code, n.takeIf { it.isNotBlank() && !it.equals(code, true) })
    }

    private fun matches(name: String, code: String) = names(code).any { name.contains(it, ignoreCase = true) }

    private fun applyTrackPrefs() {
        val s = settings
        lifecycleScope.launch {
            repeat(6) {
                delay(1000)
                val audio = mp.getAudioTracks()
                if (audio != null && audio.size > 1) {
                    val real = audio.filter { it.id != -1 }
                    var audioMatchesSubLang = false
                    if (s.audioLang.isNotBlank()) {
                        real.firstOrNull { matches(it.name.orEmpty(), s.audioLang) }?.let { mp.setAudioTrack(it.id) }
                    }
                    val chosen = mp.getAudioTrack()
                    audioMatchesSubLang = s.subLang.isNotBlank() &&
                        real.any { it.id == chosen && matches(it.name.orEmpty(), s.subLang) }
                    val subs = mp.getSpuTracks()?.toList().orEmpty().filter { it.id != -1 }
                    when (s.subMode) {
                        SubMode.OFF -> mp.setSpuTrack(-1)
                        SubMode.AUTO -> if (!audioMatchesSubLang) {
                            val pick = subs.firstOrNull { s.subLang.isNotBlank() && matches(it.name.orEmpty(), s.subLang) }
                                ?: subs.firstOrNull { it.name.orEmpty().contains("forced", true) }
                            if (pick != null && s.subLang.isNotBlank()) mp.setSpuTrack(pick.id)
                        }
                        SubMode.ALWAYS -> (subs.firstOrNull { s.subLang.isNotBlank() && matches(it.name.orEmpty(), s.subLang) }
                            ?: subs.firstOrNull())?.let { mp.setSpuTrack(it.id) }
                    }
                    trackTick++
                    return@launch
                }
            }
        }
    }

    // ----------------------------------------------------------------------------------------
    // Actions
    // ----------------------------------------------------------------------------------------

    private fun touch() { poke++ }

    private fun togglePlay() {
        if (mp.isPlaying) mp.pause() else mp.play()
        touch()
    }

    private fun seekBy(deltaMs: Long) {
        if (isLive || lenMs <= 0) return
        val t = (mp.getTime() + deltaMs).coerceIn(0L, lenMs)
        mp.setTime(t)
        posMs = t
        hud = (if (deltaMs < 0) "⏪ " else "⏩ ") + formatClock(t)
        touch()
    }

    private fun seekTo(ms: Long) {
        if (isLive || lenMs <= 0) return
        mp.setTime(ms.coerceIn(0L, lenMs)); posMs = ms; touch()
    }

    private fun channelStep(delta: Int) {
        val c = cur?.item ?: return
        if (channels.isEmpty()) return
        val i = channels.indexOfFirst { it.id == c.id || it.id == c.channelId }
        val n = channels[(if (i < 0) 0 else (i + delta + channels.size) % channels.size)]
        play(n.id, true)
        hud = "${n.channelNumber.orEmpty()} ${n.name}".trim()
    }

    private fun cycleAspect() {
        aspectIdx = (aspectIdx + 1) % aspects.size
        mp.setVideoScale(aspects[aspectIdx].second)
        hud = aspects[aspectIdx].first
        touch()
    }

    private fun enterPip() {
        if (isTv) return
        runCatching {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    private fun hideBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ----------------------------------------------------------------------------------------
    // Activity plumbing
    // ----------------------------------------------------------------------------------------

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideBars()
    }

    override fun onUserLeaveHint() {
        if (playing && !isTv && error == null) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) controls = false
    }

    override fun onStop() {
        super.onStop()
        if (ready && !isInPictureInPictureMode) {
            wasPlaying = mp.isPlaying
            mp.pause()
            report(paused = true)
        }
    }

    override fun onStart() {
        super.onStart()
        if (ready && wasPlaying) { mp.play(); wasPlaying = false }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (!ready) return
        finishReport()
        val player = mp
        mp.setEventListener(null)
        // Releasing libVLC can block for a moment, so it is done off the UI thread.
        Thread {
            try {
                player.stop()
                player.detachViews()
                player.release()
                vlc.release()
            } catch (_: Throwable) {
            }
        }.start()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.action == KeyEvent.ACTION_DOWN && ready) {
            val s = settings
            when (e.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { if (!mp.isPlaying) mp.play(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (mp.isPlaying) mp.pause(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(s.skipFwdSec * 1000L); return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-s.skipBackSec * 1000L); return true }
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> {
                    if (isLive) channelStep(1) else nextEp?.let { play(it.id, true) }
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    if (isLive) channelStep(-1)
                    return true
                }
            }
            if (!controls && !showTracks && error == null) {
                when (e.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { togglePlay(); controls = true; return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { seekBy(-s.skipBackSec * 1000L); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { seekBy(s.skipFwdSec * 1000L); return true }
                    KeyEvent.KEYCODE_DPAD_UP -> { if (isLive) channelStep(1) else controls = true; return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (isLive) channelStep(-1) else controls = true; return true }
                }
            } else touch()
        }
        return super.dispatchKeyEvent(e)
    }

    // ----------------------------------------------------------------------------------------
    // UI
    // ----------------------------------------------------------------------------------------

    @Composable
    private fun PlayerUi() {
        val s = settings
        val fr = remember { FocusRequester() }
        val am = remember { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

        LaunchedEffect(controls, inPip) { if (controls && !inPip) runCatching { fr.requestFocus() } }
        LaunchedEffect(controls, playing, showTracks, poke) {
            if (controls && playing && !showTracks) { delay(4500); controls = false }
        }
        LaunchedEffect(hud) { if (hud != null) { delay(1200); hud = null } }
        LaunchedEffect(note) { if (note != null) { delay(3500); note = null } }
        BackHandler(enabled = showTracks || (controls && isTv)) {
            if (showTracks) showTracks = false else controls = false
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx ->
                    VLCVideoLayout(ctx).also { layout ->
                        layout.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                            override fun onViewAttachedToWindow(v: View) {
                                mp.attachViews(layout, null, true, false)
                                if (!viewReady.isCompleted) viewReady.complete(Unit)
                            }

                            override fun onViewDetachedFromWindow(v: View) {}
                        })
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            // Touch layer: tap = controls, double-tap = skip, vertical drag = brightness / volume.
            if (!inPip) {
                var rightSide by remember { mutableStateOf(false) }
                var acc by remember { mutableStateOf(0f) }
                Box(
                    Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { controls = !controls },
                                onDoubleTap = { o ->
                                    if (o.x < size.width / 2) seekBy(-s.skipBackSec * 1000L) else seekBy(s.skipFwdSec * 1000L)
                                },
                            )
                        }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = { o -> rightSide = o.x > size.width / 2; acc = 0f },
                                onDragEnd = { hud = null },
                                onVerticalDrag = { _, dy ->
                                    acc += -dy / size.height
                                    if (rightSide) {
                                        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                        if (kotlin.math.abs(acc) * max >= 1f) {
                                            val v = (am.getStreamVolume(AudioManager.STREAM_MUSIC) + (acc * max).toInt()).coerceIn(0, max)
                                            am.setStreamVolume(AudioManager.STREAM_MUSIC, v, 0)
                                            hud = "Volume ${v * 100 / max}%"
                                            acc = 0f
                                        }
                                    } else {
                                        val lp = window.attributes
                                        val now = if (lp.screenBrightness < 0) 0.5f else lp.screenBrightness
                                        lp.screenBrightness = (now + acc).coerceIn(0.02f, 1f)
                                        window.attributes = lp
                                        hud = "Brightness ${(lp.screenBrightness * 100).toInt()}%"
                                        acc = 0f
                                    }
                                },
                            )
                        },
                )
            }

            if (buffering && error == null && !inPip) {
                CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
            }
            hud?.let {
                Text(it, Modifier.align(Alignment.TopCenter).padding(top = 48.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(12.dp, 6.dp))
            }
            note?.let {
                Text(it, Modifier.align(Alignment.TopCenter).padding(top = 96.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(8.dp)).padding(12.dp, 6.dp),
                    style = Type.bodySmall)
            }

            if (error != null && !inPip) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(error!!, color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { lastId?.let { play(it, false) } }) { Text("Retry") }
                        TextButton(onClick = { lastId?.let { play(it, false, forceServer = true) } }) { Text("Try via server") }
                        TextButton(onClick = { finish() }) { Text("Close") }
                    }
                }
            }

            // Up-next card for episodes.
            val n = nextEp
            if (n != null && !nextDismissed && !isLive && lenMs > 0 && lenMs - posMs < 20_000L && !inPip && !controls) {
                Column(
                    Modifier.align(Alignment.BottomEnd).padding(24.dp).background(Color(0xDD171419), RoundedCornerShape(12.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("Up next", color = Accent, style = Type.labelLarge)
                    Text(listOfNotNull(n.episodeCode, n.name).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { play(n.id, true) }) { Text("Play now") }
                        TextButton(onClick = { nextDismissed = true }) { Text("Cancel") }
                    }
                }
            }

            if (controls && error == null && !inPip) Controls(fr)
            if (showTracks) TracksDialog()
        }
    }

    @Composable
    private fun Controls(fr: FocusRequester) {
        val s = settings
        var drag by remember { mutableStateOf<Float?>(null) }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xAA000000), Color.Transparent, Color(0xCC000000))))) {
            Row(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = Type.titleMedium)
                    if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFFB0A8AE), style = Type.bodySmall)
                }
                if (isLive) Text("● LIVE", color = Color(0xFFFF6B6B), modifier = Modifier.padding(end = 12.dp))
            }

            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                if (isLive) {
                    IconButton(onClick = { channelStep(-1) }) { Icon(Icons.Filled.SkipPrevious, "Previous channel", tint = Color.White) }
                } else {
                    IconButton(onClick = { seekBy(-s.skipBackSec * 1000L) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.FastRewind, "Back ${s.skipBackSec}s", tint = Color.White)
                            Text("${s.skipBackSec}", style = Type.labelSmall)
                        }
                    }
                }
                IconButton(onClick = { togglePlay() }, modifier = Modifier.focusRequester(fr)) {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play", tint = Color.White)
                }
                if (isLive) {
                    IconButton(onClick = { channelStep(1) }) { Icon(Icons.Filled.SkipNext, "Next channel", tint = Color.White) }
                } else {
                    IconButton(onClick = { seekBy(s.skipFwdSec * 1000L) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${s.skipFwdSec}", style = Type.labelSmall)
                            Icon(Icons.Filled.FastForward, "Forward ${s.skipFwdSec}s", tint = Color.White)
                        }
                    }
                }
            }

            Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (!isLive && lenMs > 0) {
                    Slider(
                        value = drag ?: posMs.toFloat(),
                        onValueChange = { drag = it; touch() },
                        onValueChangeFinished = { drag?.let { seekTo(it.toLong()) }; drag = null },
                        valueRange = 0f..lenMs.toFloat(),
                        colors = SliderDefaults.colors(thumbColor = Accent, activeTrackColor = Accent),
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(formatClock((drag ?: posMs.toFloat()).toLong()), style = Type.bodySmall)
                        Box(Modifier.weight(1f))
                        Text(formatClock(lenMs), style = Type.bodySmall)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = { showTracks = true }) { Icon(Icons.Filled.Subtitles, "Audio and subtitles", tint = Color.White) }
                    IconButton(onClick = { cycleAspect() }) { Icon(Icons.Filled.AspectRatio, "Aspect ratio", tint = Color.White) }
                    if (!isTv) IconButton(onClick = { enterPip() }) { Icon(Icons.Filled.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
                }
            }
        }
    }

    @Composable
    private fun TracksDialog() {
        var tick by remember { mutableIntStateOf(0) }
        @Suppress("UNUSED_VARIABLE") val refresh = trackTick + tick // re-read the track lists when either changes
        val audio = mp.getAudioTracks()?.toList().orEmpty()
        val subs = mp.getSpuTracks()?.toList().orEmpty()
        val curA = mp.getAudioTrack()
        val curS = mp.getSpuTrack()
        AlertDialog(
            onDismissRequest = { showTracks = false },
            containerColor = Color(0xFF231F26),
            title = { Text("Audio & subtitles") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Audio", color = Accent, style = Type.labelLarge)
                    if (audio.isEmpty()) Text("No tracks", color = Color.Gray)
                    audio.forEach { t ->
                        TextButton(onClick = { mp.setAudioTrack(t.id); tick++ }) {
                            Text((if (t.id == curA) "● " else "○ ") + t.name.orEmpty())
                        }
                    }
                    Text("Subtitles", color = Accent, style = Type.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    subs.forEach { t ->
                        TextButton(onClick = { mp.setSpuTrack(t.id); tick++ }) {
                            Text((if (t.id == curS) "● " else "○ ") + t.name.orEmpty())
                        }
                    }
                    Text("Sync (±100 ms)", color = Accent, style = Type.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Audio ${mp.getAudioDelay() / 1000} ms", Modifier.weight(1f), style = Type.bodySmall)
                        TextButton(onClick = { mp.setAudioDelay(mp.getAudioDelay() - 100_000L); tick++ }) { Text("−") }
                        TextButton(onClick = { mp.setAudioDelay(mp.getAudioDelay() + 100_000L); tick++ }) { Text("+") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Subs ${mp.getSpuDelay() / 1000} ms", Modifier.weight(1f), style = Type.bodySmall)
                        TextButton(onClick = { mp.setSpuDelay(mp.getSpuDelay() - 100_000L); tick++ }) { Text("−") }
                        TextButton(onClick = { mp.setSpuDelay(mp.getSpuDelay() + 100_000L); tick++ }) { Text("+") }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTracks = false }) { Text("Done") } },
        )
    }
}
