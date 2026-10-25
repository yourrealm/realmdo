package me.malm.realmdo

/**
 * The list as the server will show it once [task] is marked done, so the app
 * and the notification can move before the server answers. It mirrors the
 * server's urgency rules (`urgency.rs`); the next refresh corrects any drift.
 * A one-off leaves the list; a slot task checks its pending slot; an interval
 * task restarts its clock at [now] (Unix seconds).
 */
fun afterDone(tasks: List<Task>, task: Task, now: Long): List<Task> = tasks.mapNotNull { t ->
    if (t.id != task.id) t else when (t.kind) {
        "once" -> null
        "slot" -> checkSlot(t)
        "interval" -> {
            val after = t.afterDays ?: 0
            val ramp = (t.rampDays ?: maxOf(after, 1)).coerceAtLeast(1)
            val urgency = -after.toDouble() / ramp
            t.copy(lastDoneAt = now, urgency = urgency, important = urgency >= 1)
        }
        else -> t
    }
}

private fun checkSlot(t: Task): Task {
    val slot = t.pendingSlot ?: return t
    val today = t.today?.map { (s, state) -> s to if (s == slot) "done" else state } ?: return t
    val urgency = today.maxOfOrNull { (_, state) -> SLOT_URGENCY[state] ?: -1.0 }?.coerceAtLeast(-1.0) ?: -1.0
    // Overdue first, then current, then the next upcoming, as the server picks.
    val pending = listOf("overdue", "current", "upcoming")
        .firstNotNullOfOrNull { want -> today.firstOrNull { it.second == want }?.first }
    return t.copy(today = today, pendingSlot = pending, urgency = urgency, important = urgency >= 1)
}

private val SLOT_URGENCY = mapOf("overdue" to 1.5, "current" to 1.0, "upcoming" to -0.5, "done" to -1.0)
