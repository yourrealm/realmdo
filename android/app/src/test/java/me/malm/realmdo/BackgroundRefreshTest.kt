package me.malm.realmdo

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundRefreshTest {
    private val hour = 60 * 60 * 1000L
    private val minute = 60 * 1000L

    @Test
    fun anAlarmHoursOverdueIsHeld() {
        assertEquals(9 * hour, BackgroundRefresh.verdict(now = 10 * hour, due = hour, away = 9 * hour, previous = 0))
    }

    @Test
    fun batchingDriftIsNotHeld() {
        assertEquals(0, BackgroundRefresh.verdict(now = 25 * minute, due = 0, away = 0, previous = 0))
        assertEquals(0, BackgroundRefresh.verdict(now = hour, due = hour - 25 * minute, away = 10 * minute, previous = 0))
    }

    @Test
    fun anOnTimeAlarmAfterALongAbsenceClearsTheVerdict() {
        assertEquals(0, BackgroundRefresh.verdict(now = hour, due = hour - 5 * minute, away = 8 * hour, previous = 9 * hour))
    }

    @Test
    fun aFireWhileTheAppIsInUseSaysNothingNew() {
        assertEquals(9 * hour, BackgroundRefresh.verdict(now = hour, due = hour - 5 * minute, away = 10 * minute, previous = 9 * hour))
        // The alarm was just rescheduled by a launch: due in the future, verdict kept.
        assertEquals(9 * hour, BackgroundRefresh.verdict(now = hour, due = hour + 15 * minute, away = 8 * hour, previous = 9 * hour))
    }

    @Test
    fun noAlarmSetKeepsTheVerdict() {
        assertEquals(hour, BackgroundRefresh.verdict(now = 10 * hour, due = 0, away = 9 * hour, previous = hour))
    }

    @Test
    fun labels() {
        assertEquals("45 min", BackgroundRefresh.label(45 * minute))
        assertEquals("9 h 22 min", BackgroundRefresh.label(9 * hour + 22 * minute + 30_000))
    }
}
