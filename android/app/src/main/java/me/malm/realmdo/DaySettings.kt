package me.malm.realmdo

import org.json.JSONObject
import java.time.ZoneId

/** The user's time zone and slot start times, as `/api/settings` has them. */
data class DaySettings(
    /** An IANA name, like Europe/Stockholm. */
    val timezone: String,
    /** Slot name to its start time, `HH:MM`. */
    val slotTimes: Map<String, String>,
) {
    companion object {
        fun from(o: JSONObject): DaySettings {
            val times = o.getJSONObject("slot_times")
            return DaySettings(
                timezone = o.getString("timezone"),
                slotTimes = times.keys().asSequence().associateWith { times.getString(it) },
            )
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("timezone", timezone)
        .put("slot_times", JSONObject(slotTimes))
}

/** `HH:MM` to minutes since midnight. */
fun minutesOf(hhmm: String): Int? {
    val (h, m) = hhmm.split(":").takeIf { it.size == 2 }?.map { it.toIntOrNull() } ?: return null
    if (h == null || m == null || h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

fun hhmm(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

/** Why these times would be refused, or null: each slot must start after the one before. */
fun slotOrderError(times: Map<String, String>): String? {
    SLOTS.zipWithNext().forEach { (a, b) ->
        val ma = times[a]?.let(::minutesOf) ?: return "${slotTitle(a)} has no time"
        val mb = times[b]?.let(::minutesOf) ?: return "${slotTitle(b)} has no time"
        if (mb <= ma) return "${slotTitle(b)} must start after ${slotLabel(a)} (${times[a]})"
    }
    return null
}

/** Region zones people pick from, sorted: no Etc/, SystemV/ or bare abbreviations. */
fun pickableZones(all: Set<String> = ZoneId.getAvailableZoneIds()): List<String> =
    (all.filter { '/' in it && !it.startsWith("Etc/") && !it.startsWith("SystemV/") } + "UTC").distinct().sorted()

/** "America/Argentina/Buenos_Aires" as "America / Argentina / Buenos Aires". */
fun zoneLabel(zone: String): String = zone.replace('_', ' ').replace("/", " / ")

/** The phone's zone when it differs from the user's and is one they could pick. */
fun phoneZoneOffer(userZone: String, phoneZone: String, zones: List<String>): String? =
    phoneZone.takeIf { it != userZone && it in zones }
