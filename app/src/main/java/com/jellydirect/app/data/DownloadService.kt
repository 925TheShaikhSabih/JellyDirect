package com.jellydirect.app.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.jellydirect.app.App
import com.jellydirect.app.MainActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/** Foreground service that downloads the queue, two files at a time, straight from the source link. */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = ConcurrentHashMap<String, Job>()
    private var watcher: Job? = null
    private var lastNote = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val n = notification(Downloads.items.value)
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTE, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(NOTE, n)
        if (watcher == null) watcher = scope.launch { Downloads.items.collect { onList(it) } }
        return START_NOT_STICKY
    }

    // Android 15 limits how long a data-sync service may run; stop cleanly and let the app resume later.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun onList(list: List<DlItem>) {
        for ((id, job) in running.entries.toList()) {
            if (list.none { it.id == id && it.status == DlStatus.ACTIVE }) {
                job.cancel(); running.remove(id)
            }
        }
        for (d in list.filter { it.status == DlStatus.QUEUED }.sortedBy { it.addedAt }) {
            if (running.size >= 2) break
            Downloads.update(d.id) { it.copy(status = DlStatus.ACTIVE, error = null) }
            running[d.id] = scope.launch {
                try {
                    work(d.id)
                } finally {
                    running.remove(d.id)
                    if (Downloads.get(d.id) == null) File(Downloads.dir, "${d.id}.part").delete()
                }
            }
        }
        if (list.none { it.status == DlStatus.QUEUED || it.status == DlStatus.ACTIVE }) {
            stopSelf(); return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastNote > 1000) {
            lastNote = now
            getSystemService(NotificationManager::class.java).notify(NOTE, notification(list))
        }
    }

    private fun notification(list: List<DlItem>): Notification {
        val act = list.filter { it.status == DlStatus.ACTIVE }
        val total = act.sumOf { it.total }
        val done = act.sumOf { it.done }
        val pct = if (total > 0) (done * 100 / total).toInt() else 0
        val left = list.count { it.status == DlStatus.QUEUED || it.status == DlStatus.ACTIVE }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (left > 1) "Downloading $left items" else "Downloading")
            .setContentText(act.firstOrNull()?.let { it.seriesName ?: it.name } ?: "Starting...")
            .setProgress(100, pct, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private suspend fun work(id: String) {
        try {
            val api = (application as App).api.value ?: throw IllegalStateException("Not signed in")
            val full = api.item(id)
            saveThumb(api, full)
            val s = full.mediaSources.firstOrNull()
            val urls = buildList {
                s?.path?.trim()
                    ?.takeIf { it.startsWith("http", true) && !it.contains(".m3u8", true) && api.isUsableRemoteUrl(it) }
                    ?.let { add(it) }
                if (s != null) add(api.staticStreamUrl(id, s, null))
            }
            var err = "No source to download from"
            for (u in urls) {
                try {
                    fetch(id, u)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    err = e.message ?: e.javaClass.simpleName
                }
            }
            Downloads.update(id) { it.copy(status = DlStatus.FAILED, error = err) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Downloads.update(id) { it.copy(status = DlStatus.FAILED, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun saveThumb(api: JellyApi, item: Item) {
        val u = api.landscape(item, 420) ?: api.poster(item, 420) ?: return
        runCatching {
            SharedHttp.newCall(Request.Builder().url(u).build()).execute().use { r ->
                val body = r.body
                if (r.isSuccessful && body != null) {
                    val f = File(Downloads.dir, "${item.id}.jpg")
                    f.outputStream().use { o -> body.byteStream().copyTo(o) }
                    Downloads.update(item.id) { it.copy(thumb = f.absolutePath) }
                }
            }
        }
    }

    private suspend fun fetch(id: String, url: String) = withContext(Dispatchers.IO) {
        val part = File(Downloads.dir, "$id.part")
        val have = if (part.exists()) part.length() else 0L
        val req = Request.Builder().url(url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
        SharedHttp.newCall(req).execute().use { r ->
            if (r.code == 416 && have > 0) {
                finish(id, url, part)
                return@use
            }
            if (!r.isSuccessful) throw IllegalStateException("Server answered ${r.code}")
            val type = r.header("Content-Type").orEmpty().lowercase()
            if (type.contains("mpegurl") || type.startsWith("text/")) throw IllegalStateException("This source is a live playlist and can't be saved")
            val body = r.body ?: throw IllegalStateException("Empty response")
            val append = r.code == 206
            val start = if (append) have else 0L
            val len = body.contentLength()
            Downloads.update(id) { it.copy(total = if (len > 0) start + len else 0L, done = start, error = null) }
            var done = start
            var lastUpdate = 0L
            FileOutputStream(part, append).use { out ->
                val src = body.source()
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    ensureActive()
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastUpdate > 700) {
                        lastUpdate = now
                        val d = done
                        Downloads.update(id) { it.copy(done = d) }
                    }
                }
            }
            if (len > 0 && done < start + len) throw IllegalStateException("Connection dropped (tap resume to continue)")
            finish(id, url, part)
        }
    }

    private fun finish(id: String, url: String, part: File) {
        val ext = url.substringBefore('?').substringAfterLast('.', "").lowercase().takeIf { it in EXT } ?: "mkv"
        val out = File(Downloads.dir, "$id.$ext")
        out.delete()
        part.renameTo(out)
        Downloads.update(id) { it.copy(status = DlStatus.DONE, file = out.absolutePath, done = out.length(), total = out.length(), error = null) }
    }

    private companion object {
        const val CH = "downloads"
        const val NOTE = 7
        val EXT = setOf("mp4", "mkv", "avi", "mov", "m4v", "ts", "webm", "wmv", "flv", "mpg", "mpeg")
    }
}
