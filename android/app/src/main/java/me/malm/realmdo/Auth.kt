package me.malm.realmdo

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Signing in through the realm's Home: OAuth authorization code with PKCE in
 * a Custom Tab, as holden's "Sign in from a native app" describes. The tokens
 * reach realmdo's own subdomain as the user; the gate strips them and the
 * server sees `X-Realmdo-User`. `java.*` and `org.json` only, so it runs in
 * JVM tests.
 */
object Auth {
    const val REDIRECT = "me.malm.realmdo:/callback"

    /** realmdo's `createApp` name as Home slugs it. */
    const val APP = "realmdo"

    /** Home's origin from what the user typed or scanned, or null. Any path is dropped. */
    fun home(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val uri = runCatching { URI(if ("://" in text) text else "https://$text") }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https" || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "https://${uri.host.lowercase()}$port"
    }

    class Endpoints(val authorization: String, val token: String, val registration: String, val revocation: String) {
        companion object {
            fun from(o: JSONObject) = Endpoints(
                authorization = o.getString("authorization_endpoint"),
                token = o.getString("token_endpoint"),
                registration = o.getString("registration_endpoint"),
                revocation = o.getString("revocation_endpoint"),
            )
        }
    }

    /** A token answer; [api] is `services.api`, realmdo's own origin, which may move on any refresh. */
    data class Tokens(val access: String, val refresh: String, val expiresAt: Long, val api: String) {
        companion object {
            fun from(o: JSONObject, nowMs: Long) = Tokens(
                access = o.getString("access_token"),
                refresh = o.getString("refresh_token"),
                expiresAt = nowMs + o.optLong("expires_in", 900) * 1000,
                api = o.getJSONObject("services").getString("api").trimEnd('/'),
            )
        }
    }

    sealed interface Redirect {
        data class Code(val code: String) : Redirect

        /** `access_denied` when the user cancelled. */
        data class Failed(val error: String) : Redirect
    }

    /** The browser's way back, or null when it is not ours or not this attempt's. */
    fun redirect(raw: String?, state: String): Redirect? {
        val uri = runCatching { URI(raw ?: return null) }.getOrNull() ?: return null
        if ("${uri.scheme}:${uri.rawPath}" != REDIRECT || state.isEmpty()) return null
        val params = query(uri.rawQuery)
        if (params["state"] != state) return null
        params["error"]?.let { return Redirect.Failed(it) }
        return params["code"]?.let { Redirect.Code(it) }
    }

    sealed interface Refreshed {
        class Done(val tokens: Tokens) : Refreshed

        /** `invalid_grant`: the connection is gone, so drop the tokens. */
        data object Disconnected : Refreshed

        /** Phone apps turned off, Home starting, or anything else: keep the tokens. */
        data object TryLater : Refreshed
    }

    fun refreshed(status: Int, body: String, nowMs: Long): Refreshed {
        val json = runCatching { JSONObject(body) }.getOrNull()
        return when {
            status == 200 && json != null -> Refreshed.Done(Tokens.from(json, nowMs))
            json?.optString("error") == "invalid_grant" -> Refreshed.Disconnected
            else -> Refreshed.TryLater
        }
    }

    fun verifier(): String = b64url(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun challenge(verifier: String): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizeUrl(endpoint: String, clientId: String, state: String, challenge: String, deviceName: String): String {
        val query = form(
            "response_type" to "code",
            "client_id" to clientId,
            "redirect_uri" to REDIRECT,
            "scope" to "offline_access",
            "state" to state,
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "app" to APP,
            "device_name" to deviceName,
        ).replace("+", "%20") // a space; URLEncoder writes a real + as %2B
        return endpoint + (if ('?' in endpoint) "&" else "?") + query
    }

    // ---- network, blocking ---------------------------------------------------

    fun discover(home: String): Endpoints = Endpoints.from(JSONObject(ok(http("$home/.well-known/oauth-authorization-server"))))

    /** Once per Home; the answer is a public client with no secret. */
    fun register(endpoint: String): String {
        val body = JSONObject()
            .put("client_name", "realmdo")
            .put("redirect_uris", JSONArray().put(REDIRECT))
            .put("token_endpoint_auth_method", "none")
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
        return JSONObject(ok(http(endpoint, "application/json", body.toString()))).getString("client_id")
    }

    fun exchange(endpoint: String, clientId: String, code: String, verifier: String): Tokens {
        val body = form(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT,
            "client_id" to clientId,
            "code_verifier" to verifier,
        )
        return Tokens.from(JSONObject(ok(http(endpoint, FORM, body))), System.currentTimeMillis())
    }

    /** A network failure is [Refreshed.TryLater] too. */
    fun refresh(endpoint: String, clientId: String, refreshToken: String): Refreshed {
        val body = form("grant_type" to "refresh_token", "refresh_token" to refreshToken, "client_id" to clientId)
        val (status, text) = runCatching { http(endpoint, FORM, body) }.getOrElse { return Refreshed.TryLater }
        return refreshed(status, text, System.currentTimeMillis())
    }

    /** Signs out at Home, as Disconnect on its Phones page would. */
    fun revoke(endpoint: String, clientId: String, refreshToken: String) {
        http(endpoint, FORM, form("token" to refreshToken, "token_type_hint" to "refresh_token", "client_id" to clientId))
    }

    private const val FORM = "application/x-www-form-urlencoded"

    private fun http(url: String, type: String? = null, body: String? = null): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", type)
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = conn.responseCode
            val stream = if (status < 400) conn.inputStream else conn.errorStream
            return status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    /** The body of a 2xx, or an error that says what Home said. */
    private fun ok(response: Pair<Int, String>): String {
        val (status, text) = response
        if (status in 200..299) return text
        val json = runCatching { JSONObject(text) }.getOrNull()
        val message = json?.optString("error_description")?.ifBlank { null }
            ?: json?.optString("error")?.ifBlank { null }
            ?: "HTTP $status"
        throw ApiException(status, message)
    }

    private fun b64url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun form(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

    private fun query(raw: String?): Map<String, String> = raw.orEmpty().split('&').mapNotNull { pair ->
        val eq = pair.indexOf('=')
        if (eq < 0) null else pair.substring(0, eq) to URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
    }.toMap()
}

