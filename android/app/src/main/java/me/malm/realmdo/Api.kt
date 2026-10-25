package me.malm.realmdo

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** One timed step of a routine: shown, spoken, and read but never spoken. */
data class Step(
    val title: String,
    /** Spoken instead of the title. */
    val speech: String?,
    /** Instructions to read before or during the step. */
    val details: String?,
    val seconds: Int,
) {
    companion object {
        fun from(o: JSONObject): Step = Step(
            title = o.getString("title"),
            speech = o.optString("speech").takeIf { !o.isNull("speech") && it.isNotEmpty() },
            details = o.optString("details").takeIf { !o.isNull("details") && it.isNotEmpty() },
            seconds = o.getInt("seconds"),
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("speech", speech ?: JSONObject.NULL)
        .put("details", details ?: JSONObject.NULL)
        .put("seconds", seconds)
}

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
    /** Free text. */
    val notes: String?,
    /** A timed routine. */
    val steps: List<Step>? = null,
    /** One-off: days before the due date it shows up; null is the default. */
    val leadDays: Long? = null,
    /** Interval: days from appearing to important; null is the default. */
    val rampDays: Long? = null,
    /** Slot: which slots of the day, in day order. */
    val slots: List<String>? = null,
    /** Unix seconds; an interval task's clock starts here until its first completion. */
    val createdAt: Long = 0,
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
            steps = o.optJSONArray("steps")?.let { arr ->
                (0 until arr.length()).map { Step.from(arr.getJSONObject(it)) }
            }?.takeIf { it.isNotEmpty() },
            leadDays = if (o.isNull("lead_days")) null else o.optLong("lead_days"),
            rampDays = if (o.isNull("ramp_days")) null else o.optLong("ramp_days"),
            slots = o.optJSONArray("slots")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } },
            createdAt = o.optLong("created_at"),
        )
    }

    /** The inverse of [from], for the notification's cached list. */
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("kind", kind)
        .put("due_on", dueOn ?: JSONObject.NULL)
        .put("after_days", afterDays ?: JSONObject.NULL)
        .put("last_done_at", lastDoneAt ?: JSONObject.NULL)
        .put("urgency", urgency)
        .put("important", important)
        .put("today", today?.let { JSONArray(it.map { (s, state) -> JSONObject().put("slot", s).put("state", state) }) } ?: JSONObject.NULL)
        .put("pending_slot", pendingSlot ?: JSONObject.NULL)
        .put("notes", notes ?: JSONObject.NULL)
        .put("steps", steps?.let { JSONArray(it.map(Step::toJson)) } ?: JSONObject.NULL)
        .put("lead_days", leadDays ?: JSONObject.NULL)
        .put("ramp_days", rampDays ?: JSONObject.NULL)
        .put("slots", slots?.let { JSONArray(it) } ?: JSONObject.NULL)
        .put("created_at", createdAt)
}

/** One row of the done history, as `GET /api/completions` returns it. */
data class Completion(
    val id: Long,
    val taskId: Long,
    val title: String,
    val kind: String,
    val slot: String?,
    /** The user's local day it was for, `YYYY-MM-DD`; absent on old rows. */
    val day: String?,
    val completedAt: Long,
) {
    companion object {
        fun from(o: JSONObject): Completion = Completion(
            id = o.getLong("id"),
            taskId = o.getLong("task_id"),
            title = o.getString("title"),
            kind = o.getString("kind"),
            slot = o.optString("slot").takeIf { !o.isNull("slot") && it.isNotEmpty() },
            day = o.optString("day").takeIf { !o.isNull("day") && it.isNotEmpty() },
            completedAt = o.getLong("completed_at"),
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

    /** Open tasks in the user's order. */
    fun tasks(): List<Task> = taskList(requestArray("GET", "/api/tasks"))

    /** A one-off task with just a title. */
    fun add(title: String): Task = create(JSONObject().put("title", title).put("kind", "once"))

    fun create(body: JSONObject): Task = Task.from(request("POST", "/api/tasks", body))

    /** A partial edit; see [Draft.toJson] for what it sends. */
    fun update(id: Long, body: JSONObject): Task = Task.from(request("PATCH", "/api/tasks/$id", body))

    fun delete(id: Long) {
        raw("DELETE", "/api/tasks/$id", null)
    }

    /** Puts the open tasks in this order, first to last; answers the new list. */
    fun order(ids: List<Long>): List<Task> =
        taskList(requestArray("PUT", "/api/tasks/order", JSONObject().put("ids", JSONArray(ids))))

    /** Marks a task done. `slot` names the slot to check; null takes the most pressing. */
    fun done(id: Long, slot: String? = null) {
        val body = JSONObject()
        if (slot != null) body.put("slot", slot)
        request("POST", "/api/tasks/$id/done", body)
    }

    /** The done history, newest first. */
    fun completions(): List<Completion> {
        val arr = requestArray("GET", "/api/completions")
        return (0 until arr.length()).map { Completion.from(arr.getJSONObject(it)) }
    }

    /** Undoes one completion; a one-off returns to the list. */
    fun restore(completionId: Long) {
        raw("DELETE", "/api/completions/$completionId", null)
    }

    fun settings(): DaySettings = DaySettings.from(request("GET", "/api/settings"))

    /** Saves the whole of it; the server validates the zone and the order again. */
    fun saveSettings(s: DaySettings): DaySettings = DaySettings.from(request("PUT", "/api/settings", s.toJson()))

    private fun taskList(arr: JSONArray): List<Task> = (0 until arr.length()).map { Task.from(arr.getJSONObject(it)) }

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val text = raw(method, path, body)
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun requestArray(method: String, path: String, body: JSONObject? = null): JSONArray =
        JSONArray(raw(method, path, body))

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
