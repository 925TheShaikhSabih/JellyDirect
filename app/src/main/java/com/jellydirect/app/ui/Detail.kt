package com.jellydirect.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import com.jellydirect.app.data.ItemQuery
import com.jellydirect.app.data.JellyApi
import com.jellydirect.app.data.episodeCode
import com.jellydirect.app.data.formatDuration
import com.jellydirect.app.data.isPlayable
import com.jellydirect.app.data.positionMs
import com.jellydirect.app.data.progress
import com.jellydirect.app.data.runtimeMs
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
private class DetailData(val item: Item, val seasons: List<Item>, val children: List<Item>)

private suspend fun loadDetail(api: JellyApi, id: String): DetailData {
    val item = api.item(id)
    return when {
        item.type == "Series" -> {
            val seasons = api.seasons(id)
            DetailData(item, seasons, emptyList())
        }
        item.type == "Season" -> DetailData(item, emptyList(), api.episodes(item.seriesId ?: id, id))
        item.isFolder -> DetailData(
            item, emptyList(),
            api.items(ItemQuery(parentId = id, limit = 300, sortBy = "SortName")).items,
        )
        else -> DetailData(item, emptyList(), emptyList())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(api: JellyApi, id: String, onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val loader = rememberLoad(api, "detail:$id", DetailData.serializer()) { loadDetail(api, id) }
    val skipFirst = remember { booleanArrayOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (skipFirst[0]) skipFirst[0] = false else loader.reload()
    }

    loader.Show { d ->
        val item = d.item
        var seasonId by remember(id) { mutableStateOf(d.seasons.firstOrNull()?.id) }
        val episodes = if (item.type == "Series" && seasonId != null) {
            rememberLoad(api, "eps:$id:$seasonId", ListSerializer(Item.serializer())) { api.episodes(id, seasonId) }
        } else null
        val menu = remember { mutableStateOf<Item?>(null) }
        val played = item.userData?.played == true
        val fav = item.userData?.isFavorite == true

        LazyVerticalGrid(
            columns = GridCells.Adaptive(128.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().height(300.dp)) {
                    AsyncImage(
                        model = api.backdrop(item) ?: api.landscape(item, 1000),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().background(Surface1),
                    )
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent, Bg))))
                    Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (item.type == "Episode" && item.seriesName != null) {
                            Text(item.seriesName, color = Accent, style = Type.labelLarge)
                        }
                        Text(item.name, style = Type.titleLarge)
                        val meta = listOfNotNull(
                            item.episodeCode,
                            item.year?.toString(),
                            item.officialRating,
                            item.runtimeMs.takeIf { it > 0 }?.let { formatDuration(it) },
                            item.rating?.let { "★ %.1f".format(it) },
                        ).joinToString("  ·  ")
                        if (meta.isNotBlank()) Text(meta, color = Color(0xFFB0A8AE), style = Type.bodySmall)
                        if (item.genres.isNotEmpty()) {
                            Text(item.genres.take(4).joinToString(", "), color = Color(0xFFB0A8AE), style = Type.bodySmall)
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.isPlayable || item.type == "Series") {
                            val resumeMs = item.positionMs
                            Button(onClick = {
                                if (item.type == "Series") {
                                    scope.launch {
                                        val target = runCatching { api.nextUp(1, id).firstOrNull() }.getOrNull()
                                            ?: runCatching { api.episodes(id, d.seasons.firstOrNull()?.id).firstOrNull() }.getOrNull()
                                        target?.let { playItem(ctx, it.id) }
                                    }
                                } else playItem(ctx, item.id)
                            }) {
                                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                Text(if (resumeMs > 0 && item.type != "Series") "Resume ${formatDuration(resumeMs)}" else "Play")
                            }
                            if (resumeMs > 0 && item.isPlayable) {
                                OutlinedButton(onClick = { playItem(ctx, item.id, fromStart = true) }) {
                                    Icon(Icons.Filled.Replay, contentDescription = null)
                                    Text("Start over")
                                }
                            }
                        }
                        OutlinedButton(onClick = {
                            scope.launch { runCatching { api.setPlayed(item.id, !played) }; loader.reload() }
                        }) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = if (played) Accent else Color.White)
                            Text(if (played) "Watched" else "Mark watched")
                        }
                        IconButton(onClick = {
                            scope.launch { runCatching { api.setFavorite(item.id, !fav) }; loader.reload() }
                        }) {
                            Icon(
                                if (fav) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                contentDescription = "Favorite",
                                tint = if (fav) Color(0xFFFFC15E) else Color.White,
                            )
                        }
                    }
                    item.overview?.let { Text(it, color = Color(0xFFD6CFCF), style = Type.bodyMedium) }
                }
            }

            // Series: season chips, then the chosen season's episodes.
            if (d.seasons.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FlowRow(
                        Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) { d.seasons.forEach { s -> Chip(s.name, s.id == seasonId) { seasonId = s.id } } }
                }
            }
            val epList = (episodes?.state?.value as? Load.Ok)?.v ?: d.children.takeIf { item.type == "Season" }
            if (epList != null) {
                items(epList, key = { it.id }, span = { GridItemSpan(maxLineSpan) }) { ep ->
                    EpisodeRow(ep, api, onLong = { menu.value = it }) { playItem(ctx, ep.id) }
                }
            } else if (episodes != null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), Alignment.Center) {
                        CircularProgressIndicator(color = Accent)
                    }
                }
            }

            // Collections / folders: their children as posters.
            if (item.isFolder && item.type != "Series" && item.type != "Season") {
                items(d.children, key = { it.id }) { c ->
                    Box(Modifier.padding(horizontal = 8.dp)) { PosterCard(c, api, onLong = { menu.value = it }, onClick = onOpen) }
                }
            }
        }
        ItemMenu(menu, api, onOpen = onOpen, onChanged = { loader.reload(); episodes?.reload?.invoke() })
    }
}

@Composable
private fun EpisodeRow(ep: Item, api: JellyApi, onLong: (Item) -> Unit, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .focusClick(RoundedCornerShape(10.dp), onLong = { onLong(ep) }, onClick = onClick)
            .background(Surface1)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(150.dp).aspectRatio(16f / 9f).background(Surface2)) {
            AsyncImage(
                model = api.landscape(ep, 420), contentDescription = null,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            val p = ep.progress
            if (p > 0f && p < 1f) {
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = Accent, trackColor = Color(0x66000000),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                listOfNotNull(ep.index?.toString(), ep.name).joinToString(". "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = Type.titleMedium,
            )
            val sub = listOfNotNull(ep.runtimeMs.takeIf { it > 0 }?.let { formatDuration(it) }, if (ep.userData?.played == true) "Watched" else null)
            if (sub.isNotEmpty()) Text(sub.joinToString(" · "), color = Color.Gray, style = Type.bodySmall)
            ep.overview?.let { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis, color = Color(0xFFB0A8AE), style = Type.bodySmall) }
        }
    }
}
