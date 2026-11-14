package com.jellydirect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jellydirect.app.BuildConfig
import com.jellydirect.app.data.UpdateState
import com.jellydirect.app.data.Updater

/** Quiet check on launch, then prompts: update available -> downloading -> install. Mounted once, above the app. */
@Composable
fun UpdateHost() {
    if (!Updater.enabled) return
    val ctx = LocalContext.current
    val st by Updater.state.collectAsState()
    val dismissed by Updater.dismissed.collectAsState()
    LaunchedEffect(Unit) { Updater.checkOnLaunch(ctx) }
    // As soon as the file is downloaded, open the installer.
    LaunchedEffect(st) { (st as? UpdateState.Ready)?.let { Updater.install(ctx, it.file) } }

    when (val s = st) {
        is UpdateState.Available -> if (dismissed != s.build) AlertDialog(
            onDismissRequest = { Updater.dismiss(s.build) },
            title = { Text("Update available") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Build ${s.build} is ready (you have ${BuildConfig.BUILD})${if (s.size > 0) "  ·  ${fmtBytes(s.size)}" else ""}.", style = Type.bodyMedium)
                    if (s.notes.isNotBlank()) Text(s.notes, style = Type.bodySmall, color = InkDim, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = { Button(onClick = { Updater.download(ctx) }) { Text("Update") } },
            dismissButton = { TextButton(onClick = { Updater.dismiss(s.build) }) { Text("Later") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Downloading update") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val f = if (s.total > 0) (s.done.toFloat() / s.total).coerceIn(0f, 1f) else 0.05f
                    LinearProgressIndicator({ f }, Modifier.fillMaxWidth().height(6.dp))
                    Text(if (s.total > 0) "${fmtBytes(s.done)} of ${fmtBytes(s.total)}" else fmtBytes(s.done), style = Type.bodySmall, color = InkDim)
                }
            },
            confirmButton = {},
        )
        is UpdateState.Ready -> AlertDialog(
            onDismissRequest = { Updater.reset() },
            title = { Text("Ready to install") },
            text = { Text("If Android asks, allow this app to install updates, then come back and tap Install.", style = Type.bodyMedium) },
            confirmButton = { Button(onClick = { Updater.install(ctx, s.file) }) { Text("Install") } },
            dismissButton = { TextButton(onClick = { Updater.reset() }) { Text("Later") } },
        )
        is UpdateState.Failed -> if (s.duringDownload) AlertDialog(
            onDismissRequest = { Updater.reset() },
            title = { Text("Update failed") },
            text = { Text(s.message, style = Type.bodyMedium) },
            confirmButton = { TextButton(onClick = { Updater.reset(); Updater.check(ctx, manual = true) }) { Text("Retry") } },
            dismissButton = { TextButton(onClick = { Updater.reset() }) { Text("Close") } },
        )
        else -> {}
    }
}

/** Settings entry: current build plus a manual "Check for updates". */
@Composable
fun UpdateRow() {
    if (!Updater.enabled) return
    val ctx = LocalContext.current
    val st by Updater.state.collectAsState()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("App updates", style = Type.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = { Updater.check(ctx, manual = true) }) { Text("Check for updates") }
            Text(
                when (val s = st) {
                    UpdateState.Checking -> "Checking..."
                    UpdateState.UpToDate -> "You're on the latest build"
                    is UpdateState.Available -> "Build ${s.build} available"
                    is UpdateState.Downloading -> "Downloading..."
                    is UpdateState.Ready -> "Build ${s.build} ready to install"
                    is UpdateState.Failed -> s.message
                    UpdateState.Idle -> "Build ${BuildConfig.BUILD}"
                },
                style = Type.bodySmall, color = InkDim, modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}
