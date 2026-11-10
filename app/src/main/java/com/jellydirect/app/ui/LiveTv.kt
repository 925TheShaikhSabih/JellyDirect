package com.jellydirect.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.jellydirect.app.data.Item
import com.jellydirect.app.data.JellyApi
import com.jellydirect.app.data.parsedEnd
import com.jellydirect.app.data.parsedStart
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private enum class LiveTab(val label: String) { CHANNELS("Channels"), GUIDE("Guide"), FAVORITES("Favorites") }

private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun LiveTvScreen(api: JellyApi) {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(LiveTab.CHANNELS) }
    val loader = rememberLoad(api, "channels:${tab == LiveTab.FAVORITES}") {
        api.channels(limit = 500, favoritesOnly = tab == LiveTab.FAVORITES).items
    }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Text("Live TV", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LiveTab.entries.forEach { t -> Chip(t.label, tab == t) { tab = t } }
        }
        loader.Show { channels ->
            if (channels.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) { Text("No channels", color = Color.Gray) }
            } else if (tab == LiveTab.GUIDE) {
                Guide(api, channels.take(100))
            } else {
                LazyColumn(Modifier.padding(top = 8.dp)) {
                    items(channels, key = { it.id }) { ch -> ChannelRow(ch, api) { playItem(ctx, ch.id) } }
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(ch: Item, api: JellyApi, onClick: () -> Unit) {
    val now = ch.currentProgram
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .focusClick(RoundedCornerShape(10.dp), onClick = onClick)
            .background(Surface1)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(64.dp).height(40.dp).background(Surface2), Alignment.Center) {
            AsyncImage(model = api.channelLogo(ch), contentDescription = null, modifier = Modifier.fillMaxSize().padding(4.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                listOfNotNull(ch.channelNumber, ch.name).joinToString("  "),
                maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium,
            )
            if (now != null) {
                Text(now.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Color(0xFFB4B4C0), style = MaterialTheme.typography.bodySmall)
                val s = now.parsedStart()
                val e = now.parsedEnd()
                if (s != null && e != null && e.isAfter(s)) {
                    val total = Duration.between(s, e).toMillis().toFloat()
                    val done = Duration.between(s, Instant.now()).toMillis().toFloat()
                    LinearProgressIndicator(
                        progress = { (done / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(3.dp),
                        color = Accent, trackColor = Surface2,
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Guide (EPG grid): channel names on the left, programs laid out on a shared horizontal time axis.
// ------------------------------------------------------------------------------------------------

private val DP_PER_MIN: Dp = 4.dp
private const val WINDOW_BEFORE_MIN = 30L
private const val WINDOW_AFTER_MIN = 6 * 60L

@Composable
private fun Guide(api: JellyApi, channels: List<Item>) {
    val ctx = LocalContext.current
    val windowStart = remember { Instant.now().minusSeconds(WINDOW_BEFORE_MIN * 60).let { Instant.ofEpochSecond(it.epochSecond / 1800 * 1800) } }
    val windowEnd = windowStart.plusSeconds((WINDOW_BEFORE_MIN + WINDOW_AFTER_MIN) * 60)
    val ids = channels.map { it.id }
    val loader = rememberLoad(api, "guide:${ids.size}:${windowStart.epochSecond}") {
        api.programs(ids, windowStart, windowEnd).groupBy { it.channelId.orEmpty() }
    }
    val scroll = rememberScrollState()
    val totalMin = (WINDOW_BEFORE_MIN + WINDOW_AFTER_MIN).toInt()

    loader.Show { byChannel ->
        Column(Modifier.fillMaxSize()) {
            // time header
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Spacer(Modifier.width(110.dp))
                Row(Modifier.horizontalScroll(scroll)) {
                    for (m in 0 until totalMin step 30) {
                        Text(
                            timeFmt.format(windowStart.plusSeconds(m * 60L)),
                            color = Color.Gray, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.width(DP_PER_MIN * 30),
                        )
                    }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(channels, key = { it.id }) { ch ->
                    Row(Modifier.fillMaxWidth().height(52.dp).padding(vertical = 2.dp)) {
                        Text(
                            listOfNotNull(ch.channelNumber, ch.name).joinToString(" "),
                            maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.width(110.dp).padding(horizontal = 8.dp).align(Alignment.CenterVertically),
                        )
                        Row(Modifier.horizontalScroll(scroll)) {
                            var cursor = windowStart
                            val progs = (byChannel[ch.id] ?: emptyList())
                                .mapNotNull { p ->
                                    val s = p.parsedStart() ?: return@mapNotNull null
                                    val e = p.parsedEnd() ?: return@mapNotNull null
                                    Triple(p, maxOf(s, windowStart), minOf(e, windowEnd))
                                }
                                .filter { it.third.isAfter(it.second) }
                                .sortedBy { it.second }
                            if (progs.isEmpty()) {
                                Box(Modifier.width(DP_PER_MIN * totalMin).fillMaxSize().padding(horizontal = 1.dp)
                                    .focusClick(RoundedCornerShape(6.dp)) { playItem(ctx, ch.id) }
                                    .background(Surface1).padding(8.dp)) {
                                    Text("No guide data", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            for ((p, s, e) in progs) {
                                if (s.isBefore(cursor)) continue
                                val gap = Duration.between(cursor, s).toMinutes()
                                if (gap > 0) Spacer(Modifier.width(DP_PER_MIN * gap.toInt()))
                                val mins = Duration.between(s, e).toMinutes().toInt().coerceAtLeast(1)
                                Box(
                                    Modifier.width(DP_PER_MIN * mins).fillMaxSize().padding(horizontal = 1.dp)
                                        .focusClick(RoundedCornerShape(6.dp)) { playItem(ctx, ch.id) }
                                        .background(Surface2).padding(8.dp),
                                ) {
                                    Text(p.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                                }
                                cursor = e
                            }
                        }
                    }
                }
            }
        }
    }
}
