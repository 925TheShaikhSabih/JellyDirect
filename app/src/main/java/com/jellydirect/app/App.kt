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
import kotlinx.coroutines.flow.MutableStateFlow
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
    }

    fun apiFor(a: Account) = JellyApi(a.serverUrl, store.deviceId, a.token, a.userId)

    /** Makes [a] the active account (saving it) and rebuilds the API. */
    fun signIn(a: Account) {
        store.addAccount(a)
        _api.value = apiFor(a)
    }

    fun switchTo(a: Account) {
        store.activeKey = a.key
        _api.value = apiFor(a)
    }

    fun signOut(a: Account) {
        store.removeAccount(a.key)
        _api.value = store.activeAccount()?.let { apiFor(it) }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(SharedHttp)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(200L * 1024 * 1024).build() }
        .crossfade(150)
        .respectCacheHeaders(false)
        .build()
}
