package me.malm.realmdo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DaySettingsTest {
    private val times = mapOf("morning" to "07:00", "before_lunch" to "11:00", "afternoon" to "14:00", "evening" to "19:00")

    @Test
    fun roundTripsTheServersShape() {
        val json = """{"timezone":"Europe/Stockholm","slot_times":{"morning":"07:00","before_lunch":"11:00","afternoon":"14:00","evening":"19:00"}}"""
        val day = DaySettings.from(JSONObject(json))
        assertEquals(DaySettings("Europe/Stockholm", times), day)
        assertEquals(day, DaySettings.from(day.toJson()))
    }

    @Test
    fun parsesAndFormatsTimes() {
        assertEquals(7 * 60 + 5, minutesOf("07:05"))
        assertNull(minutesOf("24:00"))
        assertNull(minutesOf("7"))
        assertEquals("07:05", hhmm(7 * 60 + 5))
    }

    @Test
    fun slotsMustIncrease() {
        assertNull(slotOrderError(times))
        assertEquals(
            "Afternoon must start after before lunch (11:00)",
            slotOrderError(times + ("afternoon" to "11:00")),
        )
        assertEquals("Evening has no time", slotOrderError(times - "evening"))
    }

    @Test
    fun zonesAreRegionsPlusUtc() {
        val zones = pickableZones(setOf("Europe/Stockholm", "Etc/GMT+1", "SystemV/EST5", "EST", "America/New_York"))
        assertEquals(listOf("America/New_York", "Europe/Stockholm", "UTC"), zones)
        assertEquals("America / Argentina / Buenos Aires", zoneLabel("America/Argentina/Buenos_Aires"))
    }

    @Test
    fun offersThePhonesZoneOnlyWhenItDiffersAndIsPickable() {
        val zones = listOf("Europe/London", "Europe/Stockholm", "UTC")
        assertEquals("Europe/London", phoneZoneOffer("Europe/Stockholm", "Europe/London", zones))
        assertNull(phoneZoneOffer("Europe/Stockholm", "Europe/Stockholm", zones))
        assertNull(phoneZoneOffer("Europe/Stockholm", "GMT", zones))
    }
}
