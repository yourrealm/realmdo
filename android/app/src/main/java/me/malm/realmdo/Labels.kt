package me.malm.realmdo

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** "today", "tomorrow", "in 3 d", "2 d ago". Mirrors the web UI. */
fun dueLabel(dueOn: String, today: LocalDate = LocalDate.now()): String {
    val diff = ChronoUnit.DAYS.between(today, LocalDate.parse(dueOn))
    return when {
        diff == 0L -> "today"
        diff == 1L -> "tomorrow"
        diff == -1L -> "yesterday"
        diff > 0 -> "in $diff d"
        else -> "${-diff} d ago"
    }
}

fun slotLabel(slot: String): String = slot.replace('_', ' ')

/** The second line under a task: due, interval or slot standing. */
fun detail(
    t: Task,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String? = when (t.kind) {
    "interval" -> {
        val every = "every ${t.afterDays ?: 0} d"
        val last = t.lastDoneAt
        if (last == null) every else {
            val days = ChronoUnit.DAYS.between(Instant.ofEpochSecond(last).atZone(zone).toLocalDate(), today)
            "$every · done ${if (days == 0L) "today" else "$days d ago"}"
        }
    }
    "slot" -> t.today?.joinToString(" · ") { (slot, state) ->
        if (state == "done") "${slotLabel(slot)} ✓" else "${slotLabel(slot)} $state"
    }
    else -> t.dueOn?.let { "due ${dueLabel(it, today)}" }
}

/** The status-bar chip: the title itself, cut to what a chip can show. */
fun chip(t: Task, max: Int = 12): String {
    val title = t.title.trim()
    if (title.length <= max) return title
    val cut = title.take(max)
    val atWord = cut.lastIndexOf(' ')
    return (if (atWord >= max / 2) cut.substring(0, atWord) else cut).trimEnd() + "…"
}
