package com.jellydirect.app.ui

import android.content.ClipData
import com.jellydirect.app.BuildConfig
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import com.jellydirect.app.data.JellyApi
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.jellydirect.app.data.HwMode
import com.jellydirect.app.data.Settings
import com.jellydirect.app.data.StreamMode
import com.jellydirect.app.data.SubMode

private val LANGS = listOf(
    "" to "Auto", "eng" to "English", "ara" to "Arabic", "hin" to "Hindi", "urd" to "Urdu", "fra" to "French",
    "spa" to "Spanish", "deu" to "German", "tur" to "Turkish", "rus" to "Russian", "jpn" to "Japanese", "kor" to "Korean",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Opt(title: String, current: T, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, label) -> Chip(label, v == current) { onPick(v) } }
        }
    }
}

@Composable
fun SettingsScreen(onAddAccount: (String?) -> Unit) {
    val app = currentApp()
    val s: Settings by app.store.settings.collectAsState()
    val accounts by app.store.accounts.collectAsState()
    val api by app.api.collectAsState()
    fun up(f: (Settings) -> Settings) = app.store.updateSettings(f)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.titleLarge)

        Text("Accounts", style = MaterialTheme.typography.titleMedium)
        FlowRow2 {
            accounts.forEach { a ->
                Chip("${a.userName} @ ${a.serverName}", a.key == app.store.activeKey) {
                    if (a.key != app.store.activeKey) { MemCache.map.clear(); app.switchTo(a) }
                }
            }
        }
        FlowRow2 {
            api?.let { cur ->
                OutlinedButton(onClick = { onAddAccount(cur.baseUrl) }) { Text("Add user on this server") }
            }
            if (BuildConfig.FIXED_SERVER_URL.isBlank()) OutlinedButton(onClick = { onAddAccount(null) }) { Text("Add server") }
            OutlinedButton(onClick = {
                accounts.firstOrNull { it.key == app.store.activeKey }?.let { MemCache.map.clear(); app.signOut(it) }
            }) { Text("Sign out") }
        }

        Opt("Streaming", s.streamMode, listOf(
            StreamMode.DIRECT_URL to "Direct from source URL",
            StreamMode.SERVER to "Via server (no transcoding)",
        )) { v -> up { it.copy(streamMode = v) } }
        Text(
            "Direct mode plays the link stored in the .strm file straight from its host, so your server only provides the library. " +
                "If that link can't be opened, the player falls back to the server's raw stream automatically.",
            color = Color.Gray, style = MaterialTheme.typography.bodySmall,
        )
        Opt("Hardware decoding", s.hw, listOf(HwMode.AUTO to "Auto (software fallback)", HwMode.OFF to "Software only")) { v -> up { it.copy(hw = v) } }
        Opt("Buffer", s.bufferMs, listOf(1000 to "Fast start", 2000 to "Balanced", 6000 to "Smooth")) { v -> up { it.copy(bufferMs = v) } }
        Opt("Preferred audio language", s.audioLang, LANGS) { v -> up { it.copy(audioLang = v) } }
        Opt("Subtitles", s.subMode, listOf(SubMode.OFF to "Off", SubMode.AUTO to "Auto", SubMode.ALWAYS to "Always on")) { v -> up { it.copy(subMode = v) } }
        Opt("Preferred subtitle language", s.subLang, LANGS) { v -> up { it.copy(subLang = v) } }
        Opt("Play next episode automatically", s.autoNext, listOf(true to "On", false to "Off")) { v -> up { it.copy(autoNext = v) } }
        Opt("Skip back", s.skipBackSec, listOf(5 to "5s", 10 to "10s", 15 to "15s")) { v -> up { it.copy(skipBackSec = v) } }
        Opt("Skip forward", s.skipFwdSec, listOf(10 to "10s", 30 to "30s", 60 to "60s")) { v -> up { it.copy(skipFwdSec = v) } }
        api?.let { LiveDiag(it) }
    }
}

@Composable
private fun LiveDiag(api: JellyApi) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var filter by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Live TV diagnostics", style = MaterialTheme.typography.titleMedium)
        Text(
            "Tests one channel step by step and shows what the server returns. Addresses are partly masked; check it before sharing.",
            color = Color.Gray, style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(value = filter, onValueChange = { filter = it }, label = { Text("Channel name (optional)") }, singleLine = true)
        FlowRow2 {
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                out = "Running..."
                scope.launch {
                    out = try { api.diagnoseLive(filter) } catch (e: Exception) { "Failed: " + (e.message ?: e.javaClass.simpleName) }
                    busy = false
                }
            }) { Text("Run test") }
            if (out.isNotEmpty() && !busy) {
                OutlinedButton(onClick = {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("diagnostics", out))
                }) { Text("Copy") }
            }
        }
        if (out.isNotEmpty()) SelectionContainer { Text(out, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRow2(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}
