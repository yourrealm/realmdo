package me.malm.realmdo

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** "today", "tomorrow", "in 3 d", "2 d ago". */
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

/** "Before lunch", for tiles and headings. */
fun slotTitle(slot: String): String = slotLabel(slot).replaceFirstChar { it.uppercase() }

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
        when (state) {
            "done" -> "${slotLabel(slot)} ✓"
            "current" -> "${slotLabel(slot)} now"
            else -> "${slotLabel(slot)} $state"
        }
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

/** 90 -> "1:30". */
fun clock(seconds: Int): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** 90 -> "1 minute 30 seconds", for the voice. */
fun spokenDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    fun unit(n: Int, name: String) = if (n == 1) "1 $name" else "$n ${name}s"
    return when {
        m == 0 -> unit(s, "second")
        s == 0 -> unit(m, "minute")
        else -> "${unit(m, "minute")} ${unit(s, "second")}"
    }
}

/** What the voice says as a step begins: its words, then how long. */
fun announcement(step: Step): String =
    "${(step.speech ?: step.title).trimEnd('.', ' ')}. ${spokenDuration(step.seconds)}."

/**
 * Why a task is out of the list for now, for the order screen: "back in 2 d",
 * "done for today". Null when it is showing.
 */
fun hiddenLabel(
    t: Task,
    today: LocalDate = LocalDate.now(),
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    if (t.urgency >= 0) return null
    return when (t.kind) {
        "interval" -> {
            val from = Instant.ofEpochSecond(t.lastDoneAt ?: t.createdAt).atZone(zone).toLocalDate()
            val days = ChronoUnit.DAYS.between(today, from.plusDays(t.afterDays ?: 0))
            when {
                days <= 0L -> "back today"
                days == 1L -> "back tomorrow"
                else -> "back in $days d"
            }
        }
        "slot" -> if (t.pendingSlot == null) "done for today" else "later today"
        else -> t.dueOn?.let { "due ${dueLabel(it, today)}" } ?: "hidden"
    }
}

private val DAY_HEADING = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH)
private val SHORT_DATE = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/** "Sunday 27 September", under the Today title. */
fun longDay(day: LocalDate): String = DAY_HEADING.format(day)

/** "Today", "Yesterday", else "Thursday 24 September": the history's groups. */
fun dayHeading(day: LocalDate, today: LocalDate = LocalDate.now()): String = when (day) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> longDay(day)
}

/** "Tue 6 Oct", for a picked due date. */
fun shortDate(dueOn: String): String = SHORT_DATE.format(LocalDate.parse(dueOn))

/** Unix seconds as "07:42" on the phone's clock. */
fun timeOfDay(epochSeconds: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    CLOCK.format(Instant.ofEpochSecond(epochSeconds).atZone(zone))

/** "1 day", "6 days". */
fun days(n: Int): String = if (n == 1) "1 day" else "$n days"

/** What the interval numbers mean, in the editor's words. */
fun repeatHelp(after: Int, ramp: Int?): String {
    val urgent = ramp ?: maxOf(after, 1)
    val hidden = if (after == 0) "It shows again right after you finish it" else "Out of sight for ${days(after)} after you finish it"
    return "$hidden, then urgent ${days(urgent)} later."
}
