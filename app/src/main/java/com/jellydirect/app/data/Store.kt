package com.jellydirect.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.util.UUID

enum class SubMode { OFF, AUTO, ALWAYS }

@Serializable
data class Settings(
    val streamMode: StreamMode = StreamMode.DIRECT_URL,
    val hw: HwMode = HwMode.AUTO,
    /** ISO 639 code such as "eng"; blank = whatever the file marks as default. */
    val audioLang: String = "",
    val subLang: String = "",
    val subMode: SubMode = SubMode.AUTO,
    val autoNext: Boolean = true,
    val skipBackSec: Int = 10,
    val skipFwdSec: Int = 30,
    val bufferMs: Int = 2000,
)

/** Accounts + settings, in app-private SharedPreferences. */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("jd", Context.MODE_PRIVATE)

    val deviceId: String = sp.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
        sp.edit().putString("deviceId", it).apply()
    }

    private val _settings = MutableStateFlow(
        try {
            sp.getString("settings", null)?.let { AppJson.decodeFromString(Settings.serializer(), it) } ?: Settings()
        } catch (_: Exception) {
            Settings()
        },
    )
    val settings: StateFlow<Settings> = _settings

    fun updateSettings(f: (Settings) -> Settings) {
        val n = f(_settings.value)
        _settings.value = n
        sp.edit().putString("settings", AppJson.encodeToString(Settings.serializer(), n)).apply()
    }

    private val accSer = ListSerializer(Account.serializer())

    private val _accounts = MutableStateFlow(
        try {
            sp.getString("accounts", null)?.let { AppJson.decodeFromString(accSer, it) } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        },
    )
    val accounts: StateFlow<List<Account>> = _accounts

    var activeKey: String?
        get() = sp.getString("active", null)
        set(v) {
            sp.edit().putString("active", v).apply()
        }

    private fun saveAccounts(l: List<Account>) {
        _accounts.value = l
        sp.edit().putString("accounts", AppJson.encodeToString(accSer, l)).apply()
    }

    fun addAccount(a: Account) {
        saveAccounts(_accounts.value.filter { it.key != a.key } + a)
        activeKey = a.key
    }

    fun removeAccount(key: String) {
        saveAccounts(_accounts.value.filter { it.key != key })
        if (activeKey == key) activeKey = _accounts.value.firstOrNull()?.key
    }

    /** Returns (and clears) the stack trace saved by the last crash, if any. */
    fun takeLastCrash(): String? {
        val c = sp.getString("lastCrash", null) ?: return null
        sp.edit().remove("lastCrash").apply()
        return c
    }

    fun activeAccount(): Account? = _accounts.value.firstOrNull { it.key == activeKey }
}
