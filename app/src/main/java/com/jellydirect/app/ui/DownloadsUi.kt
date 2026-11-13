package com.jellydirect.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.jellydirect.app.data.DlItem
import com.jellydirect.app.data.DlStatus
import com.jellydirect.app.data.Downloads
import com.jellydirect.app.data.Item
import java.io.File

fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.0f MB".format(b / 1048576.0)
    else -> "${b / 1024} KB"
}

private fun DlItem.frac(): Float = if (status == DlStatus.DONE) 1f else if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0.04f

/** Returns a function that queues items, asking for notification permission the first time (Android 13+). */
@Composable
fun rememberDownloader(): (List<Item>) -> Unit {
    val ctx = LocalContext.current
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    return { list ->
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) perm.launch(Manifest.permission.POST_NOTIFICATIONS)
        Downloads.enqueue(list)
    }
}

@Composable
private fun Ring(p: Float, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = 3.dp.toPx()
        val tl = Offset(w / 2, w / 2)
        val sz = Size(size.width - w, size.height - w)
        drawArc(Color(0x33FFFFFF), 0f, 360f, false, tl, sz, style = Stroke(w))
        drawArc(Accent, -90f, 360f * p.coerceIn(0f, 1f), false, tl, sz, style = Stroke(w, cap = StrokeCap.Round))
    }
}

/** Shared state + click handling for download buttons; [content] only has to draw. */
@Composable
private fun Dl(item: Item, content: @Composable (ImageVector, String, Color, Float?, () -> Unit) -> Unit) {
    val start = rememberDownloader()
    val all by Downloads.items.collectAsState()
    val d = all.firstOrNull { it.id == item.id }
    var confirm by remember { mutableStateOf(false) }
    val pct = d?.let { (it.frac() * 100).toInt() } ?: 0
    when (d?.status) {
        null -> content(Icons.Filled.Download, "Download", Color.White, null) { start(listOf(item)) }
        DlStatus.DONE -> content(Icons.Filled.DownloadDone, "Saved", Accent, null) { confirm = true }
        DlStatus.QUEUED, DlStatus.ACTIVE -> content(Icons.Filled.Pause, "$pct%", Accent, d!!.frac()) { Downloads.pause(item.id) }
        DlStatus.PAUSED -> content(Icons.Filled.PlayArrow, "Paused", Color.White, d!!.frac()) { Downloads.resume(item.id) }
        DlStatus.FAILED -> content(Icons.Filled.Refresh, "Retry", Danger, null) { Downloads.resume(item.id) }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Remove download?") },
            text = { Text("${item.name} will be deleted from this device.") },
            confirmButton = { TextButton(onClick = { confirm = false; Downloads.remove(item.id) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep") } },
        )
    }
}

/** Small round download button, for episode rows. */
@Composable
fun DlIcon(item: Item) = Dl(item) { icon, label, tint, p, click ->
    Box(Modifier.size(42.dp).focusClick(CircleShape, onClick = click), contentAlignment = Alignment.Center) {
        if (p != null) Ring(p, Modifier.size(30.dp))
        Icon(icon, label, Modifier.size(20.dp), tint = tint)
    }
}

/** Icon with a label, for the detail screen's action row. */
@Composable
fun DlAction(item: Item) = Dl(item) { icon, label, tint, p, click ->
    Column(
        Modifier.focusClick(RoundedCornerShape(12.dp), onClick = click).padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (p != null) Ring(p, Modifier.size(30.dp))
            Icon(icon, label, tint = tint)
        }
        Text(label, style = Type.labelSmall, color = InkDim)
    }
}

@Composable
fun DownloadsScreen() {
    val ctx = LocalContext.current
    val list by Downloads.items.collectAsState()
    val sorted = list.sortedWith(compareBy<DlItem>({ it.status == DlStatus.DONE }, { (it.seriesName ?: it.name).lowercase() }, { it.code ?: "" }))
    val saved = list.filter { it.status == DlStatus.DONE }
    var confirm by remember { mutableStateOf<DlItem?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.statusBarsPadding().padding(top = 16.dp)) {
                Text("Downloads", style = Type.titleLarge)
                Text(
                    if (list.isEmpty()) "Nothing saved yet" else "${saved.size} saved  ·  ${fmtBytes(saved.sumOf { it.total })}",
                    style = Type.bodySmall, color = InkDim,
                )
            }
        }
        if (list.isEmpty()) {
            item { Text("Open a movie, episode or recording and tap Download to watch it without a connection.", color = InkDim, style = Type.bodyMedium) }
        }
        items(sorted, key = { it.id }) { d ->
            Row(
                Modifier.fillMaxWidth().animateItem().focusClick(RoundedCornerShape(14.dp)) { if (d.status == DlStatus.DONE) playItem(ctx, d.id) },
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(Surface1)) {
                    d.thumb?.let { AsyncImage(File(it), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    if (d.status != DlStatus.DONE) {
                        LinearProgressIndicator({ d.frac() }, Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp), color = Accent, trackColor = Color(0x66000000))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(d.seriesName ?: d.name, style = Type.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = if (d.seriesName != null) listOfNotNull(d.code, d.name).joinToString(" · ") else d.year?.toString().orEmpty()
                    if (sub.isNotBlank()) Text(sub, style = Type.bodySmall, color = InkDim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val pct = (d.frac() * 100).toInt()
                    when (d.status) {
                        DlStatus.DONE -> Text(fmtBytes(d.total), style = Type.labelSmall, color = Accent)
                        DlStatus.ACTIVE -> Text(if (d.total > 0) "$pct%  ·  ${fmtBytes(d.done)} of ${fmtBytes(d.total)}" else "Downloading...", style = Type.labelSmall, color = Accent)
                        DlStatus.QUEUED -> Text("Waiting...", style = Type.labelSmall, color = InkDim)
                        DlStatus.PAUSED -> Text("Paused  ·  $pct%", style = Type.labelSmall, color = InkDim)
                        DlStatus.FAILED -> Text(d.error ?: "Failed", style = Type.labelSmall, color = Danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                when (d.status) {
                    DlStatus.QUEUED, DlStatus.ACTIVE -> IconButton(onClick = { Downloads.pause(d.id) }) { Icon(Icons.Filled.Pause, "Pause") }
                    DlStatus.PAUSED, DlStatus.FAILED -> IconButton(onClick = { Downloads.resume(d.id) }) { Icon(Icons.Filled.Refresh, "Resume") }
                    DlStatus.DONE -> {}
                }
                IconButton(onClick = { confirm = d }) { Icon(Icons.Filled.Delete, "Delete") }
            }
        }
    }
    confirm?.let { d ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Remove download?") },
            text = { Text("${d.seriesName ?: d.name} will be deleted from this device.") },
            confirmButton = { TextButton(onClick = { confirm = null; Downloads.remove(d.id) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Keep") } },
        )
    }
}
