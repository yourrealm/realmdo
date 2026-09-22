package me.malm.realmdo

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One open task as `GET /api/tasks` returns it. */
data class Task(
    val id: Long,
    val title: String,
    val kind: String,
    val dueOn: String?,
    val afterDays: Long?,
    val lastDoneAt: Long?,
    val urgency: Double,
    val important: Boolean,
    /** Slot tasks: slot name to state (done, upcoming, current, overdue). */
    val today: List<Pair<String, String>>?,
    /** Slot tasks: the slot a bare "done" would check. */
    val pendingSlot: String?,
    /** Free text, edited in the web UI. */
    val notes: String?,
) {
    companion object {
        fun from(o: JSONObject): Task = Task(
            id = o.getLong("id"),
            title = o.getString("title"),
            kind = o.getString("kind"),
            dueOn = o.optString("due_on").takeIf { !o.isNull("due_on") },
            afterDays = if (o.isNull("after_days")) null else o.getLong("after_days"),
            lastDoneAt = if (o.isNull("last_done_at")) null else o.getLong("last_done_at"),
            urgency = o.getDouble("urgency"),
            important = o.getBoolean("important"),
            today = o.optJSONArray("today")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val s = arr.getJSONObject(i)
                    s.getString("slot") to s.getString("state")
                }
            },
            pendingSlot = o.optString("pending_slot").takeIf { !o.isNull("pending_slot") },
            notes = o.optString("notes").takeIf { !o.isNull("notes") && it.isNotEmpty() },
        )
    }
}

class ApiException(val status: Int, message: String) : IOException(message)

/**
 * The JSON API behind the gate, authenticated with a bearer token. Plain
 * `HttpURLConnection`, blocking: callers run it off the main thread.
 */
class Api(private val baseUrl: String, private val token: String) {
    companion object {
        fun from(prefs: Prefs): Api? =
            if (prefs.configured) Api(prefs.baseUrl, prefs.token) else null
    }

    /** `GET /api/me`, the username. */
    fun me(): String = request("GET", "/api/me").getString("username")

    /** Open tasks, sorted by urgency, most urgent first. */
    fun tasks(): List<Task> {
        val arr = requestArray("GET", "/api/tasks")
        return (0 until arr.length()).map { Task.from(arr.getJSONObject(it)) }
    }

    /** A one-off task with just a title. */
    fun add(title: String): Task =
        Task.from(request("POST", "/api/tasks", JSONObject().put("title", title).put("kind", "once")))

    /** Marks a task done. `slot` names the slot to check; null takes the most pressing. */
    fun done(id: Long, slot: String? = null) {
        val body = JSONObject()
        if (slot != null) body.put("slot", slot)
        request("POST", "/api/tasks/$id/done", body)
    }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val text = raw(method, path, body)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun requestArray(method: String, path: String): JSONArray = JSONArray(raw(method, path, null))

    private fun raw(method: String, path: String, body: JSONObject?): String {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/json")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            val status = conn.responseCode
            if (status >= 400) {
                val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
                val message = runCatching { JSONObject(err).getString("error") }.getOrDefault(err.ifBlank { "HTTP $status" })
                throw ApiException(status, message)
            }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
