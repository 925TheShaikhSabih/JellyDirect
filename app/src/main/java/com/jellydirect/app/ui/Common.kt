package com.jellydirect.app.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import com.jellydirect.app.data.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import java.io.File
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jellydirect.app.App
import com.jellydirect.app.data.Item
import com.jellydirect.app.data.JellyApi
import com.jellydirect.app.data.episodeCode
import com.jellydirect.app.data.isChannel
import com.jellydirect.app.data.progress
import com.jellydirect.app.player.PlayerActivity
import kotlinx.coroutines.launch

val LocalIsTv = staticCompositionLocalOf { false }

val Crimson = Color(0xFFA90635)
val Accent = Color(0xFFE04728)
val Gold = Color(0xFFFFC15E)
val BrandBrush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(Crimson, AccentHi))
val Surface1 = Color(0xFF151517)
val Surface2 = Color(0xFF1E1E21)
val Bg = Color(0xFF0B0B0D)

// ------------------------------------------------------------------------------------------------
// Loading helper with a tiny in-memory cache so going "back" shows the previous screen instantly.
// ------------------------------------------------------------------------------------------------

sealed interface Load<out T> {
    data object Busy : Load<Nothing>
    data class Ok<T>(val v: T) : Load<T>
    data class Err(val msg: String) : Load<Nothing>
}

object MemCache {
    val map = HashMap<String, Any>()
}

class Loader<T>(val state: State<Load<T>>, val reload: () -> Unit)

private fun cacheFile(ctx: Context, ck: String): File {
    val h = java.security.MessageDigest.getInstance("SHA-1").digest(ck.toByteArray()).joinToString("") { "%02x".format(it) }
    return File(ctx.cacheDir, "api/$h.json")
}

private fun <T> readDisk(ctx: Context, ck: String, ser: KSerializer<T>): T? = try {
    val f = cacheFile(ctx, ck)
    if (f.exists()) AppJson.decodeFromString(ser, f.readText()) else null
} catch (_: Exception) {
    null
}

private fun <T> writeDisk(ctx: Context, ck: String, ser: KSerializer<T>, v: T) {
    try {
        val f = cacheFile(ctx, ck)
        f.parentFile?.mkdirs()
        f.writeText(AppJson.encodeToString(ser, v))
    } catch (_: Exception) {
    }
}

/**
 * Loads data, showing the last result instantly (memory, or disk when [ser] is given) while a fresh copy
 * is fetched in the background, so screens open immediately even on a slow link.
 */
@Composable
fun <T : Any> rememberLoad(api: JellyApi, key: String, ser: KSerializer<T>? = null, block: suspend () -> T): Loader<T> {
    val ctx = LocalContext.current.applicationContext
    val ck = "${api.baseUrl}|${api.userId}|$key"
    var tick by remember(ck) { mutableIntStateOf(0) }
    val st = remember(ck) {
        @Suppress("UNCHECKED_CAST")
        val mem = MemCache.map[ck] as T?
        val disk = if (mem == null && ser != null) readDisk(ctx, ck, ser) else null
        mutableStateOf<Load<T>>((mem ?: disk)?.let { Load.Ok(it) } ?: Load.Busy)
    }
    LaunchedEffect(ck, tick) {
        try {
            val v = block()
            MemCache.map[ck] = v
            st.value = Load.Ok(v)
            if (ser != null) withContext(Dispatchers.IO) { writeDisk(ctx, ck, ser, v) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (st.value !is Load.Ok) st.value = Load.Err(e.message ?: e.javaClass.simpleName)
        }
    }
    return remember(ck) { Loader(st) { tick++ } }
}

/** Renders [content] once data is available; spinner/error otherwise. */
@Composable
fun <T : Any> Loader<T>.Show(content: @Composable (T) -> Unit) {
    when (val s = state.value) {
        is Load.Ok -> content(s.v)
        is Load.Busy -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = Accent) }
        is Load.Err -> Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Couldn't load", style = Type.titleMedium)
                Text(s.msg, color = Color.Gray, style = Type.bodySmall)
                TextButton(onClick = reload) { Text("Retry") }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Focus / click
// ------------------------------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.focusClick(
    shape: Shape = RoundedCornerShape(10.dp),
    onLong: (() -> Unit)? = null,
    onClick: () -> Unit,
): Modifier {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(
        if (pressed) 0.95f else if (focused) 1.06f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = 380f), label = "focusScale",
    )
    return this
        .scale(s)
        .then(if (focused) Modifier.border(BorderStroke(3.dp, Color.White), shape) else Modifier)
        .clip(shape)
        .combinedClickable(interactionSource = src, indication = null, onClick = onClick, onLongClick = onLong)
}

// ------------------------------------------------------------------------------------------------
// Cards + rows
// ------------------------------------------------------------------------------------------------

@Composable
fun PosterCard(
    item: Item,
    api: JellyApi,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    fill: Boolean = false,
    onLong: ((Item) -> Unit)? = null,
    onClick: (Item) -> Unit,
) {
    val tv = LocalIsTv.current
    val w = if (wide) (if (tv) 300.dp else 220.dp) else (if (tv) 170.dp else 128.dp)
    val ratio = if (wide || item.isChannel) 16f / 9f else 2f / 3f
    val url = if (wide) api.landscape(item) else api.poster(item) ?: api.landscape(item)
    Column(if (fill) modifier.fillMaxWidth() else modifier.width(w)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(ratio)
                .focusClick(RoundedCornerShape(14.dp), onLong = onLong?.let { f -> { f(item) } }) { onClick(item) }
                .background(Surface1),
        ) {
            AsyncImage(
                model = url,
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (item.userData?.played == true && !item.isFolder) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Watched",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Accent)
                        .padding(2.dp)
                        .width(16.dp)
                        .height(16.dp),
                )
            } else if ((item.userData?.unplayedCount ?: 0) > 0) {
                Text(
                    "${item.userData?.unplayedCount}",
                    style = Type.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(Accent)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (item.userData?.isFavorite == true) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = "Favorite",
                    tint = Color(0xFFFFC15E),
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp).width(16.dp).height(16.dp),
                )
            }
            val p = item.progress
            if (p > 0f && p < 1f) {
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = Accent,
                    trackColor = Color(0x66000000),
                )
            }
        }
        Text(
            if (item.type == "Episode") (item.seriesName ?: item.name) else item.name,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = Type.bodySmall,
            modifier = Modifier.padding(top = 7.dp),
        )
        val sub = when {
            item.type == "Episode" -> listOfNotNull(item.episodeCode, item.name).joinToString(" · ")
            item.isChannel -> item.currentProgram?.name ?: item.channelNumber.orEmpty()
            else -> item.year?.toString().orEmpty()
        }
        if (sub.isNotBlank()) {
            Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis, color = InkDim, style = Type.labelSmall)
        }
    }
}

@Composable
fun ItemRow(
    title: String,
    items: List<Item>,
    api: JellyApi,
    wide: Boolean = false,
    onLong: ((Item) -> Unit)? = null,
    index: Int = -1,
    onClick: (Item) -> Unit,
) {
    if (items.isEmpty()) return
    Column(Modifier.then(if (index >= 0) Modifier.enter(index) else Modifier).padding(vertical = 8.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Type.titleMedium)
            Icon(Icons.Filled.ChevronRight, null, Modifier.size(20.dp), tint = InkDim)
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.id }) { PosterCard(it, api, modifier = Modifier.animateItem(), wide = wide, onLong = onLong, onClick = onClick) }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Item context menu (long-press / D-pad hold): watched + favorite toggles
// ------------------------------------------------------------------------------------------------

@Composable
fun ItemMenu(target: MutableState<Item?>, api: JellyApi, onOpen: (Item) -> Unit, onChanged: () -> Unit) {
    val item = target.value ?: return
    val scope = rememberCoroutineScope()
    val played = item.userData?.played == true
    val fav = item.userData?.isFavorite == true
    fun act(block: suspend () -> Unit) {
        target.value = null
        scope.launch {
            try {
                block()
            } catch (_: Exception) {
            }
            onChanged()
        }
    }
    AlertDialog(
        onDismissRequest = { target.value = null },
        title = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                TextButton(onClick = { target.value = null; onOpen(item) }) { Text("Details") }
                if (!item.isChannel) {
                    TextButton(onClick = { act { api.setPlayed(item.id, !played) } }) {
                        Text(if (played) "Mark as unwatched" else "Mark as watched")
                    }
                }
                TextButton(onClick = { act { api.setFavorite(item.id, !fav) } }) {
                    Text(if (fav) "Remove from favorites" else "Add to favorites")
                }
            }
        },
        confirmButton = { TextButton(onClick = { target.value = null }) { Text("Close") } },
        containerColor = Surface2,
    )
}

// ------------------------------------------------------------------------------------------------
// Misc
// ------------------------------------------------------------------------------------------------

@Composable
fun currentApp(): App = LocalContext.current.applicationContext as App

fun playItem(ctx: Context, id: String, fromStart: Boolean = false) {
    ctx.startActivity(
        Intent(ctx, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_ID, id)
            .putExtra(PlayerActivity.EXTRA_FROM_START, fromStart),
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = Type.titleLarge, modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    val fill by animateColorAsState(if (selected) Accent else Color(0xFF1C1C20), tween(200), label = "chip")
    Text(
        text,
        style = Type.labelLarge,
        color = if (selected) Color.White else Ink,
        modifier = Modifier
            .focusClick(shape, onClick = onClick)
            .background(fill, shape)
            .then(if (selected) Modifier else Modifier.rim(shape))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Fades and lifts a block in once, staggered by [index]. */
@Composable
fun Modifier.enter(index: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 50L)
        a.animateTo(1f, tween(420))
    }
    return graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 40f }
}

fun userImage(baseUrl: String, userId: String) = "${baseUrl.trimEnd('/')}/Users/$userId/Images/Primary?maxWidth=300&quality=80"

/** Round profile picture; shows the initial until (or unless) the server image loads. */
@Composable
fun Avatar(url: String?, name: String, modifier: Modifier = Modifier, ring: Boolean = false, letterSp: Int = 22) {
    Box(
        modifier.clip(CircleShape).background(BrandBrush).then(if (ring) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(name.take(1).uppercase(), style = Type.titleLarge.copy(fontSize = letterSp.sp), color = Color.White)
        if (url != null) AsyncImage(url, name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** White pill with a red play icon, like the concept's main action. */
@Composable
fun PlayPill(label: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier.focusClick(shape, onClick = onClick).background(Color.White, shape).padding(horizontal = 22.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, null, tint = Accent)
        Text(label, style = Type.labelLarge, color = Accent)
    }
}
