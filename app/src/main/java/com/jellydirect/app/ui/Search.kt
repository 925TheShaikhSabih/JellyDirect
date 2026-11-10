package com.jellydirect.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.jellydirect.app.data.Item
import com.jellydirect.app.data.ItemQuery
import com.jellydirect.app.data.JellyApi
import kotlinx.coroutines.delay

private class Results(val movies: List<Item>, val shows: List<Item>, val episodes: List<Item>, val other: List<Item>, val channels: List<Item>)

@Composable
fun SearchScreen(api: JellyApi, onOpen: (Item) -> Unit) {
    val ctx = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<Results?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val menu = remember { mutableStateOf<Item?>(null) }

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
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            label = { Text("Search movies, shows, channels") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        Box(Modifier.fillMaxSize()) {
            val r = results
            when {
                r != null -> LazyColumn {
                    item { ItemRow("Movies", r.movies, api, onLong = { menu.value = it }, onClick = onOpen) }
                    item { ItemRow("Shows", r.shows, api, onLong = { menu.value = it }, onClick = onOpen) }
                    item { ItemRow("Episodes", r.episodes, api, wide = true, onLong = { menu.value = it }, onClick = onOpen) }
                    item { ItemRow("Other", r.other, api, onLong = { menu.value = it }, onClick = onOpen) }
                    item { ItemRow("Live TV", r.channels, api, wide = true) { playItem(ctx, it.id) } }
                    if (r.movies.isEmpty() && r.shows.isEmpty() && r.episodes.isEmpty() && r.other.isEmpty() && r.channels.isEmpty()) {
                        item { Text("No results", color = Color.Gray, modifier = Modifier.padding(16.dp)) }
                    }
                }
                error != null -> Text(error!!, color = Color.Gray, modifier = Modifier.padding(16.dp))
            }
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.TopCenter), color = Accent)
        }
    }
    ItemMenu(menu, api, onOpen = onOpen, onChanged = {})
}
