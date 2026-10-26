package me.malm.realmdo

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The realm connection, the Live Update switch and the refresh bookkeeping.
 * App-private storage, kept out of backups (`data_extraction_rules.xml`): a
 * restored copy on another phone would be the same connection.
 */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("realmdo", Context.MODE_PRIVATE)

    /** Home's origin, as the user typed or scanned it. */
    val home: String get() = sp.getString("home", "") ?: ""

    /** Registered once per Home; Home rate-limits registration. */
    val clientId: String get() = sp.getString("client_id", "") ?: ""
    val tokenEndpoint: String get() = sp.getString("token_endpoint", "") ?: ""
    val revocationEndpoint: String get() = sp.getString("revocation_endpoint", "") ?: ""

    /** realmdo's own origin, `services.api` from the last token answer. No trailing slash. */
    val baseUrl: String get() = sp.getString("base_url", "") ?: ""
    val accessToken: String get() = sp.getString("access_token", "") ?: ""
    val refreshToken: String get() = sp.getString("refresh_token", "") ?: ""

    /** When the access token runs out, in ms. */
    val accessExpires: Long get() = sp.getLong("access_expires", 0)

    /** The verifier and `state` of the sign-in in the browser, on disk since the activity may be recreated meanwhile. */
    val pendingVerifier: String get() = sp.getString("pending_verifier", "") ?: ""
    val pendingState: String get() = sp.getString("pending_state", "") ?: ""

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

    val configured: Boolean get() = baseUrl.isNotEmpty() && refreshToken.isNotEmpty()

    /** Starts a sign-in at [home]. A new Home forgets the old one's client. */
    fun startConnect(home: String, clientId: String, endpoints: Auth.Endpoints, verifier: String, state: String) =
        sp.edit(commit = true) {
            putString("home", home)
            putString("client_id", clientId)
            putString("token_endpoint", endpoints.token)
            putString("revocation_endpoint", endpoints.revocation)
            putString("pending_verifier", verifier)
            putString("pending_state", state)
        }

    fun endConnect() = sp.edit(commit = true) { remove("pending_verifier"); remove("pending_state") }

    fun forgetClient() = sp.edit(commit = true) { remove("client_id") }

    /** Written at once: every refresh rotates, and losing the new refresh token disconnects the phone. */
    fun saveTokens(t: Auth.Tokens) = sp.edit(commit = true) {
        putString("access_token", t.access)
        putString("refresh_token", t.refresh)
        putLong("access_expires", t.expiresAt)
        putString("base_url", t.api)
    }

    fun dropTokens() = sp.edit(commit = true) {
        remove("access_token")
        remove("refresh_token")
        remove("access_expires")
        remove("base_url")
        remove("username")
    }

    /** Everything but Home and its client registration, so connecting again skips registering. */
    fun clear() {
        val keep = listOf("home", "client_id", "token_endpoint", "revocation_endpoint").associateWith { sp.getString(it, null) }
        sp.edit(commit = true) {
            clear()
            keep.forEach { (k, v) -> if (v != null) putString(k, v) }
        }
    }
}
