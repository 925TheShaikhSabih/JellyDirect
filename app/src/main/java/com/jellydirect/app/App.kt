package com.jellydirect.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.jellydirect.app.data.Account
import com.jellydirect.app.data.JellyApi
import com.jellydirect.app.data.SharedHttp
import com.jellydirect.app.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow

class App : Application(), ImageLoaderFactory {
    lateinit var store: Store
        private set

    private val _api = MutableStateFlow<JellyApi?>(null)

    /** The API for the active account, or null when signed out. */
    val api: StateFlow<JellyApi?> = _api

    override fun onCreate() {
        super.onCreate()
        store = Store(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                getSharedPreferences("jd", MODE_PRIVATE).edit()
                    .putString("lastCrash", android.util.Log.getStackTraceString(e).take(2500)).commit()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(t, e)
        }
        store.activeAccount()?.let { _api.value = apiFor(it) }
        warm(_api.value)
    }

    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Opens a few connections to the server up front so the first screens do not wait on TCP setup. */
    private fun warm(api: JellyApi?) {
        if (api == null) return
        repeat(4) { bg.launch { runCatching { api.publicInfo() } } }
    }

    fun apiFor(a: Account) = JellyApi(a.serverUrl, store.deviceId, a.token, a.userId)

    /** Makes [a] the active account (saving it) and rebuilds the API. */
    fun signIn(a: Account) {
        store.addAccount(a)
        _api.value = apiFor(a)
        warm(_api.value)
    }

    fun switchTo(a: Account) {
        store.activeKey = a.key
        _api.value = apiFor(a)
        warm(_api.value)
    }

    fun signOut(a: Account) {
        store.removeAccount(a.key)
        _api.value = store.activeAccount()?.let { apiFor(it) }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(SharedHttp)
        .crossfade(200)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(200L * 1024 * 1024).build() }
        .crossfade(150)
        .respectCacheHeaders(false)
        .build()
}
