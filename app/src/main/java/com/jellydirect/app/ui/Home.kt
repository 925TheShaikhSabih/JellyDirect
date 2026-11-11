package com.jellydirect.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import kotlinx.serialization.Serializable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
fun HomeScreen(api: JellyApi, onOpen: (Item) -> Unit, onOpenLibrary: (Item) -> Unit) {
    val ctx = LocalContext.current
    val loader = rememberLoad(api, "home", HomeData.serializer()) { loadHome(api) }
    val menu = remember { mutableStateOf<Item?>(null) }
    val skipFirst = remember { booleanArrayOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (skipFirst[0]) skipFirst[0] = false else loader.reload()
    }
    loader.Show { d ->
        val hero = d.resume.firstOrNull() ?: d.nextUp.firstOrNull() ?: d.latest.firstOrNull()?.items?.firstOrNull()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (hero != null) item { Hero(hero, api, onPlay = { if (it.isPlayable) playItem(ctx, it.id) else onOpen(it) }, onInfo = onOpen) }
            else item { Box(Modifier.statusBarsPadding().height(8.dp)) }
            item {
                ItemRow("My Media", d.views, api, wide = true, onClick = onOpenLibrary)
            }
            item {
                ItemRow("Continue Watching", d.resume, api, wide = true, onLong = { menu.value = it }) { playItem(ctx, it.id) }
            }
            item {
                ItemRow("Next Up", d.nextUp, api, wide = true, onLong = { menu.value = it }) { playItem(ctx, it.id) }
            }
            items(d.latest, key = { it.view.id }) { (view, list) ->
                ItemRow("Latest in ${view.name}", list, api, onLong = { menu.value = it }, onClick = onOpen)
            }
        }
        ItemMenu(menu, api, onOpen = onOpen, onChanged = { loader.reload() })
    }
}

@Composable
private fun Hero(item: Item, api: JellyApi, onPlay: (Item) -> Unit, onInfo: (Item) -> Unit) {
    val tv = LocalIsTv.current
    Box(Modifier.fillMaxWidth().height(if (tv) 340.dp else 260.dp)) {
        AsyncImage(
            model = api.backdrop(item) ?: api.landscape(item, 1000),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().background(Surface1),
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0x55000000), Color.Transparent, Bg)),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (item.type == "Episode") (item.seriesName ?: item.name) else item.name,
                style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            item.overview?.let {
                Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, color = Color(0xFFD6CFCF), style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth(0.8f))
            }
            Row2(onPlay = { onPlay(item) }, onInfo = { onInfo(item) })
        }
    }
}

@Composable
private fun Row2(onPlay: () -> Unit, onInfo: () -> Unit) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Text("Play")
        }
        androidx.compose.material3.OutlinedButton(onClick = onInfo) { Text("Details") }
    }
}
