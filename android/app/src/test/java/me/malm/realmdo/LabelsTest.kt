package me.malm.realmdo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

private val TODAY: LocalDate = LocalDate.of(2026, 9, 17)

private fun task(
    kind: String,
    dueOn: String? = null,
    afterDays: Long? = null,
    lastDoneAt: Long? = null,
    important: Boolean = false,
    today: List<Pair<String, String>>? = null,
    pendingSlot: String? = null,
) = Task(
    id = 1, title = "t", kind = kind, dueOn = dueOn, afterDays = afterDays, lastDoneAt = lastDoneAt,
    urgency = 0.5, important = important, today = today, pendingSlot = pendingSlot, notes = null,
)

class LabelsTest {
    @Test
    fun dueLabelsMirrorTheWebUi() {
        assertEquals("today", dueLabel("2026-09-17", TODAY))
        assertEquals("tomorrow", dueLabel("2026-09-18", TODAY))
        assertEquals("yesterday", dueLabel("2026-09-16", TODAY))
        assertEquals("in 3 d", dueLabel("2026-09-20", TODAY))
        assertEquals("2 d ago", dueLabel("2026-09-15", TODAY))
    }

    @Test
    fun onceDetailIsTheDueLabelOrNothing() {
        assertEquals("due in 3 d", detail(task("once", dueOn = "2026-09-20"), TODAY))
        assertNull(detail(task("once"), TODAY))
    }

    @Test
    fun intervalDetailCountsDaysSinceTheLastCompletion() {
        assertEquals("every 6 d", detail(task("interval", afterDays = 6), TODAY))
        val threeDaysAgo = LocalDate.of(2026, 9, 14).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        assertEquals(
            "every 6 d · done 3 d ago",
            detail(task("interval", afterDays = 6, lastDoneAt = threeDaysAgo), TODAY, ZoneOffset.UTC),
        )
        val today = TODAY.atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        assertEquals(
            "every 6 d · done today",
            detail(task("interval", afterDays = 6, lastDoneAt = today), TODAY, ZoneOffset.UTC),
        )
    }

    @Test
    fun slotDetailListsEachSlot() {
        val t = task("slot", today = listOf("before_lunch" to "done", "evening" to "upcoming"))
        assertEquals("before lunch ✓ · evening upcoming", detail(t, TODAY))
        val now = task("slot", today = listOf("before_lunch" to "current"))
        assertEquals("before lunch now", detail(now, TODAY))
    }

    @Test
    fun chipIsTheTitleCutToFit() {
        assertEquals("Brush teeth", chip(task("slot").copy(title = "Brush teeth")))
        assertEquals("Clean the…", chip(task("interval").copy(title = "Clean the bathroom")))
        assertEquals("Supercalifra…", chip(task("once").copy(title = "Supercalifragilistic")))
        assertEquals("Call mum", chip(task("once").copy(title = "  Call mum ")))
    }

    @Test
    fun durationsReadAndSpoken() {
        assertEquals("1:30", clock(90))
        assertEquals("0:05", clock(5))
        assertEquals("12:00", clock(720))
        assertEquals("45 seconds", spokenDuration(45))
        assertEquals("1 second", spokenDuration(1))
        assertEquals("2 minutes", spokenDuration(120))
        assertEquals("1 minute 30 seconds", spokenDuration(90))
    }

    @Test
    fun aStepIsAnnouncedBySpeechElseTitle() {
        assertEquals("Frog. 1 minute.", announcement(Step("Frog", null, null, 60)))
        assertEquals(
            "Pigeon. Left leg forward. 1 minute 30 seconds.",
            announcement(Step("Pigeon, left", "Pigeon. Left leg forward.", null, 90)),
        )
    }

    @Test
    fun previousRestartsAStepUnderWayElseStepsBack() {
        assertEquals(2, previousIndex(2, elapsedMs = 10_000))
        assertEquals(1, previousIndex(2, elapsedMs = 1_000))
        assertEquals(0, previousIndex(0, elapsedMs = 0))
    }
}
