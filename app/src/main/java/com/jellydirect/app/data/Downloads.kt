package com.jellydirect.app.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

enum class DlStatus { QUEUED, ACTIVE, PAUSED, DONE, FAILED }

@Serializable
data class DlItem(
    val id: String,
    val name: String,
    val type: String = "",
    val seriesName: String? = null,
    val code: String? = null,
    val year: Int? = null,
    val runtimeMs: Long = 0,
    val status: DlStatus = DlStatus.QUEUED,
    val total: Long = 0,
    val done: Long = 0,
    val file: String? = null,
    val thumb: String? = null,
    val error: String? = null,
    val positionMs: Long = 0,
    val addedAt: Long = 0,
)

/** Offline library: the list of saved/queued items (persisted) and the operations on them. */
object Downloads {
    private var appCtx: Context? = null
    lateinit var dir: File
        private set
    private val ser = ListSerializer(DlItem.serializer())
    private val _items = MutableStateFlow<List<DlItem>>(emptyList())
    val items: StateFlow<List<DlItem>> = _items

    fun init(ctx: Context) {
        val c = ctx.applicationContext
        appCtx = c
        dir = File(c.getExternalFilesDir(null) ?: c.filesDir, "downloads").apply { mkdirs() }
        val loaded = try {
            val f = File(c.filesDir, "downloads.json")
            if (f.exists()) AppJson.decodeFromString(ser, f.readText()) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        _items.value = loaded
            .filter { it.status != DlStatus.DONE || (it.file != null && File(it.file).exists()) }
            .map { if (it.status == DlStatus.ACTIVE) it.copy(status = DlStatus.QUEUED) else it }
    }

    @Synchronized
    private fun save() {
        try {
            File(appCtx!!.filesDir, "downloads.json").writeText(AppJson.encodeToString(ser, _items.value))
        } catch (_: Exception) {
        }
    }

    fun get(id: String): DlItem? = _items.value.firstOrNull { it.id == id }

    fun fileFor(id: String): File? =
        get(id)?.takeIf { it.status == DlStatus.DONE }?.file?.let { File(it) }?.takeIf { it.exists() }

    fun update(id: String, f: (DlItem) -> DlItem) {
        _items.update { l -> l.map { if (it.id == id) f(it) else it } }
        save()
    }

    fun setPosition(id: String, ms: Long) = update(id) { it.copy(positionMs = ms) }

    fun enqueue(list: List<Item>) {
        val have = _items.value.map { it.id }.toSet()
        val add = list.filter { it.id !in have }.map {
            DlItem(
                id = it.id, name = it.name, type = it.type, seriesName = it.seriesName, code = it.episodeCode,
                year = it.year, runtimeMs = it.runtimeMs, addedAt = System.currentTimeMillis(),
            )
        }
        if (add.isNotEmpty()) {
            _items.update { it + add }
            save()
        }
        startService()
    }

    fun pause(id: String) = update(id) { if (it.status == DlStatus.QUEUED || it.status == DlStatus.ACTIVE) it.copy(status = DlStatus.PAUSED) else it }

    fun resume(id: String) {
        update(id) { if (it.status == DlStatus.PAUSED || it.status == DlStatus.FAILED) it.copy(status = DlStatus.QUEUED, error = null) else it }
        startService()
    }

    fun remove(id: String) {
        val d = get(id)
        _items.update { l -> l.filter { it.id != id } }
        save()
        File(dir, "$id.part").delete()
        d?.file?.let { File(it).delete() }
        d?.thumb?.let { File(it).delete() }
    }

    /** Called when the app opens: carry on with anything that was queued or interrupted. */
    fun resumePending() {
        if (_items.value.any { it.status == DlStatus.QUEUED }) startService()
    }

    private fun startService() {
        val c = appCtx ?: return
        try {
            ContextCompat.startForegroundService(c, Intent(c, DownloadService::class.java))
        } catch (_: Exception) {
        }
    }
}
