package me.malm.realmdo

import java.net.URI
import java.net.URLDecoder

/**
 * The server URL and token inside a `realmdo://pair?url=...&token=...` code.
 * Parsed with `java.net`, not `android.net.Uri`, so it runs in JVM tests.
 */
data class Pairing(val baseUrl: String, val token: String) {
    companion object {
        fun parse(raw: String?): Pairing? {
            val uri = runCatching { URI(raw ?: return null) }.getOrNull() ?: return null
            if (uri.scheme != "realmdo" || uri.host != "pair") return null
            val query = uri.rawQuery ?: return null
            val params = query.split('&').mapNotNull { pair ->
                val eq = pair.indexOf('=')
                if (eq < 0) null else pair.substring(0, eq) to URLDecoder.decode(pair.substring(eq + 1), "UTF-8")
            }.toMap()
            val url = params["url"]?.trimEnd('/') ?: return null
            val token = params["token"] ?: return null
            if (!url.startsWith("http") || !token.startsWith("rdo_")) return null
            return Pairing(url, token)
        }
    }
}
