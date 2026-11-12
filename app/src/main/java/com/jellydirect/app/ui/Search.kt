package com.jellydirect.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.jellydirect.app.data.Item
import com.jellydirect.app.data.ItemQuery
import com.jellydirect.app.data.JellyApi
import kotlinx.coroutines.delay
import kotlinx.serialization.builtins.ListSerializer

private class Results(val movies: List<Item>, val shows: List<Item>, val episodes: List<Item>, val other: List<Item>, val channels: List<Item>)

@Composable
fun SearchScreen(api: JellyApi, onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Results?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val menu = remember { mutableStateOf<Item?>(null) }
    val recent = rememberLoad(api, "recent", ListSerializer(Item.serializer())) {
        api.items(ItemQuery(types = "Movie,Series", recursive = true, sortBy = "DateCreated", sortOrder = "Descending", limit = 12)).items
    }

    LaunchedEffect(text) {
        val q = text.trim()
        if (q.length < 2) {
            results = null; busy = false; error = null
            return@LaunchedEffect
        }
        delay(350) // debounce: cancelled automatically when the text changes again
        busy = true
        try {
            val found = api.items(
                ItemQuery(types = "Movie,Series,Episode,BoxSet,Video", recursive = true, search = q, limit = 60),
            ).items
            val ch = runCatching { api.channels(limit = 500).items }.getOrDefault(emptyList())
                .filter { it.name.contains(q, ignoreCase = true) }.take(20)
            results = Results(
                found.filter { it.type == "Movie" },
                found.filter { it.type == "Series" },
                found.filter { it.type == "Episode" },
                found.filter { it.type !in setOf("Movie", "Series", "Episode") },
                ch,
            )
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Search failed"
        }
        busy = false
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        SearchField(text, { text = it }, Modifier.fillMaxWidth().padding(16.dp))
        Box(Modifier.fillMaxSize()) {
            val r = results
            when {
                r != null -> LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
                    item { ItemRow("Movies", r.movies, api, onLong = { menu.value = it }, onClick = onOpen) }
                    item { ItemRow("Shows", r.shows, api, onLong = { menu.value = it }, onClick = onOpen) }
                    item { ItemRow("Episodes", r.episodes, api, wide = true, onLong = { menu.value = it }, onClick = onOpen) }
                    item { ItemRow("Other", r.other, api, onLong = { menu.value = it }, onClick = onOpen) }
                    item { ItemRow("Live TV", r.channels, api, wide = true) { playItem(ctx, it.id) } }
                    if (r.movies.isEmpty() && r.shows.isEmpty() && r.episodes.isEmpty() && r.other.isEmpty() && r.channels.isEmpty()) {
                        item { Text("No results", color = InkDim, modifier = Modifier.padding(16.dp)) }
                    }
                }
                error != null -> Text(error!!, color = InkDim, modifier = Modifier.padding(16.dp))
                text.trim().length < 2 -> RecentList(recent.state.value, api, onOpen)
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.TopCenter), color = Accent)
        }
    }
    ItemMenu(menu, api, onOpen = onOpen, onChanged = {})
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(50)
    Row(modifier.background(Color(0xFF1C1C20), shape).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Search, null, tint = InkDim)
        Box(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            BasicTextField(
                value, onChange, Modifier.fillMaxWidth(), singleLine = true,
                textStyle = Type.bodyMedium.copy(color = Ink, fontSize = 16.sp),
                cursorBrush = SolidColor(Accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            if (value.isEmpty()) Text("Search movies, shows, channels", color = InkDim)
        }
        if (value.isNotEmpty()) Icon(Icons.Filled.Close, "Clear", Modifier.focusClick(CircleShape) { onChange("") }, tint = InkDim)
    }
}

@Composable
private fun RecentList(st: Load<List<Item>>, api: JellyApi, onOpen: (Item) -> Unit) {
    val list = (st as? Load.Ok)?.v ?: return
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Recently added", style = Type.titleMedium, modifier = Modifier.padding(bottom = 4.dp)) }
        items(list, key = { it.id }) { m ->
            Row(
                Modifier.fillMaxWidth().animateItem().focusClick(RoundedCornerShape(12.dp), onClick = { onOpen(m) }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AsyncImage(
                    api.landscape(m, 320), null,
                    Modifier.width(110.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Surface1),
                    contentScale = ContentScale.Crop,
                )
                Text(m.name, Modifier.weight(1f), style = Type.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Box(Modifier.size(34.dp).border(1.5.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                }
            }
        }
    }
}
