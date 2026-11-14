package com.jellydirect.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.jellydirect.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.Request
import java.io.File

@Serializable
private data class GhAsset(
    val name: String = "",
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String = "",
)

@Serializable
private data class GhRelease(
    @SerialName("tag_name") val tag: String = "",
    val body: String? = null,
    val assets: List<GhAsset> = emptyList(),
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val build: Int, val notes: String, val url: String, val size: Long) : UpdateState
    data class Downloading(val build: Int, val done: Long, val total: Long) : UpdateState
    data class Ready(val build: Int, val file: File) : UpdateState
    data class Failed(val message: String, val duringDownload: Boolean = false) : UpdateState
}

/**
 * Self-updater for sideloaded builds: looks at the newest GitHub Release (tag "build-N"), picks the APK that matches
 * this edition and CPU, downloads it and hands it to the system installer. Builds are all signed with the same key,
 * so the update installs over the current app and keeps the login.
 */
object Updater {
    val enabled: Boolean get() = BuildConfig.REPO.isNotBlank()

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state

    /** Build number the person pressed "Later" on, so the prompt doesn't nag again this session. */
    private val _dismissed = MutableStateFlow(0)
    val dismissed: StateFlow<Int> = _dismissed
    fun dismiss(build: Int) { _dismissed.value = build }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var launchChecked = false

    /** Quiet check once per app launch. */
    fun checkOnLaunch(ctx: Context) {
        if (!enabled || launchChecked) return
        launchChecked = true
        check(ctx, manual = false)
    }

    fun check(ctx: Context, manual: Boolean) {
        if (!enabled) return
        val cur = _state.value
        if (cur is UpdateState.Checking || cur is UpdateState.Downloading) return
        if (manual) _dismissed.value = 0
        _state.value = UpdateState.Checking
        job = scope.launch {
            try {
                _state.value = lookup()
            } catch (e: Exception) {
                // A failed quiet check shouldn't surface as an error.
                _state.value = if (manual) UpdateState.Failed(e.message ?: "Couldn't reach GitHub") else UpdateState.Idle
            }
        }
    }

    private suspend fun lookup(): UpdateState = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("https://api.github.com/repos/${BuildConfig.REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        val text = SharedHttp.newCall(req).execute().use { r ->
            if (r.code == 404) throw IllegalStateException("No releases found (is the repository public?)")
            if (!r.isSuccessful) throw IllegalStateException("GitHub answered ${r.code}")
            r.body?.string().orEmpty()
        }
        val rel = AppJson.decodeFromString<GhRelease>(text)
        val build = rel.tag.substringAfterLast('-').toIntOrNull() ?: throw IllegalStateException("Unrecognised release tag ${rel.tag}")
        if (build <= BuildConfig.BUILD) return@withContext UpdateState.UpToDate
        val edition = if (BuildConfig.FIXED_SERVER_URL.isBlank()) "standard" else "server"
        val apks = rel.assets.filter { it.name.endsWith(".apk", true) && it.name.contains(edition, true) }
        val pick = Build.SUPPORTED_ABIS.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.name.contains(abi, true) } }
            ?: apks.singleOrNull()
            ?: throw IllegalStateException("Build $build has no APK for this device")
        UpdateState.Available(build, rel.body.orEmpty().trim(), pick.url, pick.size)
    }

    fun download(ctx: Context) {
        val a = _state.value as? UpdateState.Available ?: return
        val dir = File(ctx.applicationContext.cacheDir, "updates").also { it.mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "update-${a.build}.apk")
        _state.value = UpdateState.Downloading(a.build, 0, a.size)
        job = scope.launch {
            try {
                SharedHttp.newCall(Request.Builder().url(a.url).build()).execute().use { r ->
                    if (!r.isSuccessful) throw IllegalStateException("Download failed (${r.code})")
                    val body = r.body ?: throw IllegalStateException("Empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: a.size
                    var done = 0L
                    var last = 0L
                    out.outputStream().use { o ->
                        val src = body.source()
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = src.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n)
                            done += n
                            val now = System.nanoTime()
                            if (now - last > 250_000_000L) {
                                last = now
                                _state.value = UpdateState.Downloading(a.build, done, total)
                            }
                        }
                    }
                    if (total > 0 && done < total) throw IllegalStateException("Download was cut short")
                }
                _state.value = UpdateState.Ready(a.build, out)
            } catch (e: Exception) {
                out.delete()
                _state.value = UpdateState.Failed(e.message ?: "Download failed", duringDownload = true)
            }
        }
    }

    /** Opens the system installer. If installs from this app aren't allowed yet, opens that setting first. */
    fun install(ctx: Context, file: File) {
        val app = ctx.applicationContext
        if (!app.packageManager.canRequestPackageInstalls()) {
            app.startActivity(
                Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
        app.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    fun reset() { _state.value = UpdateState.Idle }
}
