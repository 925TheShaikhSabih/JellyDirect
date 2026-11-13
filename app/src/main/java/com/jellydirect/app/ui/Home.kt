package com.jellydirect.app.ui

import com.jellydirect.app.BuildConfig
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil.compose.AsyncImage
import com.jellydirect.app.data.Item
import com.jellydirect.app.data.JellyApi
import com.jellydirect.app.data.isPlayable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlin.math.abs

@Serializable
data class LatestRow(val view: Item, val items: List<Item>)

@Serializable
data class HomeData(
    val views: List<Item>,
    val resume: List<Item>,
    val nextUp: List<Item>,
    val latest: List<LatestRow>,
)

private suspend fun loadHome(api: JellyApi): HomeData = coroutineScope {
    val resume = async { runCatching { api.resume() }.getOrDefault(emptyList()) }
    val next = async { runCatching { api.nextUp() }.getOrDefault(emptyList()) }
    val views = api.views()
    val latest = views
        .filter { it.collectionType in setOf("movies", "tvshows", "homevideos", "boxsets") }
        .map { v -> async { LatestRow(v, runCatching { api.latest(v.id) }.getOrDefault(emptyList())) } }
    HomeData(views, resume.await(), next.await(), latest.map { it.await() }.filter { it.items.isNotEmpty() })
}

@Composable
fun HomeScreen(api: JellyApi, onOpen: (Item) -> Unit, onOpenLibrary: (Item) -> Unit, onSearch: () -> Unit, onProfile: () -> Unit) {
    val ctx = LocalContext.current
    val app = currentApp()
    val accounts by app.store.accounts.collectAsState()
    val me = accounts.firstOrNull { it.key == app.store.activeKey }
    val loader = rememberLoad(api, "home", HomeData.serializer()) { loadHome(api) }
    val menu = remember { mutableStateOf<Item?>(null) }
    var cats by remember { mutableStateOf(false) }
    val skipFirst = remember { booleanArrayOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (skipFirst[0]) skipFirst[0] = false else loader.reload()
    }
    loader.Show { d ->
        val heroes = (d.resume + d.nextUp + d.latest.flatMap { it.items }).distinctBy { it.id }.take(6)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item { TopBar(api, me?.userName.orEmpty(), onSearch, onProfile) }
            item {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.Center) {
                    Row(
                        Modifier.focusClick(RoundedCornerShape(50), onClick = { cats = true }).padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Categories", style = Type.labelLarge, color = InkDim)
                        Icon(Icons.Filled.ExpandMore, null, tint = InkDim)
                    }
                }
            }
            if (heroes.isNotEmpty()) item { Hero(heroes, api, onPlay = { if (it.isPlayable) playItem(ctx, it.id) else onOpen(it) }, onOpen = onOpen) }
            item {
                ItemRow(
                    if (me != null) "Continue Watching for ${me.userName}" else "Continue Watching", d.resume, api, wide = true,
                    onLong = { menu.value = it }, index = 0,
                ) { playItem(ctx, it.id) }
            }
            item { ItemRow("Next Up", d.nextUp, api, wide = true, onLong = { menu.value = it }, index = 1) { playItem(ctx, it.id) } }
            item { ItemRow("My Media", d.views, api, wide = true, index = 2, onClick = onOpenLibrary) }
            items(d.latest, key = { it.view.id }) { (view, list) ->
                ItemRow("Latest in ${view.name}", list, api, onLong = { menu.value = it }, onClick = onOpen)
            }
        }
        if (cats) {
            AlertDialog(
                onDismissRequest = { cats = false },
                title = { Text("Categories") },
                text = {
                    Column {
                        d.views.forEach { v -> TextButton(onClick = { cats = false; onOpenLibrary(v) }) { Text(v.name) } }
                    }
                },
                confirmButton = { TextButton(onClick = { cats = false }) { Text("Close") } },
            )
        }
        ItemMenu(menu, api, onOpen = onOpen, onChanged = { loader.reload() })
    }
}

@Composable
private fun TopBar(api: JellyApi, name: String, onSearch: () -> Unit, onProfile: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (BuildConfig.FIXED_SERVER_URL.isBlank()) {
            Box(Modifier.size(32.dp).background(BrandBrush, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp), tint = Color.White)
            }
            Text(BuildConfig.BRAND, Modifier.padding(start = 10.dp).weight(1f), style = Type.titleMedium)
        } else {
            // Wordmark only, like a streaming service's own app.
            Text(BuildConfig.BRAND, Modifier.weight(1f), style = Type.titleLarge.copy(brush = BrandBrush, fontWeight = FontWeight.Black, letterSpacing = 4.sp))
        }
        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, "Search") }
        Spacer(Modifier.width(10.dp))
        Avatar(userImage(api.baseUrl, api.userId.orEmpty()), name, Modifier.size(44.dp).focusClick(CircleShape, onClick = onProfile))
    }
}

/** Swipeable carousel: the centred card is large and upright, its neighbours shrink, fade and tilt away. */
@Composable
private fun Hero(items: List<Item>, api: JellyApi, onPlay: (Item) -> Unit, onOpen: (Item) -> Unit) {
    val pager = rememberPagerState { items.size }
    val scope = rememberCoroutineScope()
    val shape = RoundedCornerShape(26.dp)
    HorizontalPager(pager, Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 72.dp), pageSpacing = 8.dp) { page ->
        val item = items[page]
        Box(
            Modifier.fillMaxWidth().aspectRatio(0.68f)
                .graphicsLayer {
                    val signed = page - pager.currentPage - pager.currentPageOffsetFraction
                    val off = abs(signed).coerceIn(0f, 1f)
                    val sc = 1f - 0.14f * off
                    scaleX = sc; scaleY = sc
                    alpha = 1f - 0.5f * off
                    rotationZ = signed.coerceIn(-1f, 1f) * 7f
                    translationY = off * 36f
                }
                .focusClick(shape, onClick = {
                    if (page == pager.currentPage) onOpen(item) else scope.launch { pager.animateScrollToPage(page) }
                })
                .background(Surface1),
        ) {
            AsyncImage(
                model = api.poster(item, 480) ?: api.landscape(item, 640),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color(0xCC000000)))))
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).size(54.dp)
                    .focusClick(CircleShape, onClick = { onPlay(item) })
                    .background(Color(0x66000000)).rim(CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.PlayArrow, "Play", Modifier.size(30.dp), tint = Color.White) }
        }
    }
    val cur = items[pager.currentPage.coerceIn(0, items.lastIndex)]
    Crossfade(cur, label = "heroMeta") { it ->
        Column(Modifier.fillMaxWidth().padding(top = 14.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (it.type == "Episode") (it.seriesName ?: it.name) else it.name,
                style = Type.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val line = it.genres.take(3).joinToString("  ·  ").ifBlank { listOfNotNull(it.year?.toString(), it.officialRating).joinToString("  ·  ") }
            if (line.isNotBlank()) Text(line, style = Type.bodySmall, color = InkDim, maxLines = 1)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.Center) {
        items.indices.forEach { i ->
            val on = i == pager.currentPage
            val w by animateDpAsState(if (on) 18.dp else 6.dp, tween(250), label = "dot")
            Box(Modifier.padding(horizontal = 3.dp).height(6.dp).width(w).background(if (on) Accent else Color(0x44FFFFFF), CircleShape))
        }
    }
}
