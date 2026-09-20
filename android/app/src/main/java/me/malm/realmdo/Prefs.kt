package me.malm.realmdo

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Server URL, bearer token, the Live Update switch and the refresh bookkeeping. App-private storage. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("realmdo", Context.MODE_PRIVATE)

    /** Origin of the app, no trailing slash: `https://realmdo.example.org`. */
    var baseUrl: String
        get() = sp.getString("base_url", "") ?: ""
        set(v) = sp.edit { putString("base_url", v.trimEnd('/')) }

    /** A `rdo_...` token minted under Settings in the web UI. */
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit { putString("token", v.trim()) }

    var username: String
        get() = sp.getString("username", "") ?: ""
        set(v) = sp.edit { putString("username", v) }

    /** Whether the top task is pinned as a notification. */
    var liveUpdate: Boolean
        get() = sp.getBoolean("live_update", false)
        set(v) = sp.edit { putBoolean("live_update", v) }

    /** When the pending refresh alarm should fire; 0 when none is set. */
    var refreshDue: Long
        get() = sp.getLong("refresh_due", 0)
        set(v) = sp.edit { putLong("refresh_due", v) }

    /** How late the last held refresh was, in ms; 0 when the chain runs on time. */
    var refreshHeld: Long
        get() = sp.getLong("refresh_held", 0)
        set(v) = sp.edit { putLong("refresh_held", v) }

    /** When the app last came to the foreground. */
    var lastResume: Long
        get() = sp.getLong("last_resume", 0)
        set(v) = sp.edit { putLong("last_resume", v) }

    val configured: Boolean get() = baseUrl.isNotEmpty() && token.isNotEmpty()

    fun clear() = sp.edit { clear() }
}
