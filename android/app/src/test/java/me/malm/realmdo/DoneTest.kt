package me.malm.realmdo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val NOW = 1_790_000_000L

private fun task(
    id: Long,
    kind: String,
    urgency: Double = 0.5,
    afterDays: Long? = null,
    rampDays: Long? = null,
    today: List<Pair<String, String>>? = null,
    pendingSlot: String? = null,
) = Task(
    id = id, title = "t$id", kind = kind, dueOn = null, afterDays = afterDays, lastDoneAt = null,
    urgency = urgency, important = urgency >= 1, today = today, pendingSlot = pendingSlot, notes = null,
    rampDays = rampDays,
)

/** The optimistic list after "Done", as the server will show it. */
class DoneTest {
    @Test
    fun onceLeavesTheListAndTheRestKeepTheirOrder() {
        val list = listOf(task(1, "once"), task(2, "once"), task(3, "once"))
        assertEquals(listOf(1L, 3L), afterDone(list, list[1], NOW).map { it.id })
    }

    @Test
    fun slotWithALaterSlotMovesToLaterToday() {
        val t = task(
            1, "slot", urgency = 1.0,
            today = listOf("morning" to "current", "evening" to "upcoming"), pendingSlot = "morning",
        )
        val after = afterDone(listOf(t), t, NOW).single()
        assertEquals(listOf("morning" to "done", "evening" to "upcoming"), after.today)
        assertEquals("evening", after.pendingSlot)
        assertEquals(-0.5, after.urgency, 0.0)
        assertFalse(after.important)
        assertEquals(listOf(after), laterToday(listOf(after)))
    }

    @Test
    fun slotWithAnOverdueSlotLeftStaysImportant() {
        val t = task(
            1, "slot", urgency = 1.5,
            today = listOf("morning" to "overdue", "afternoon" to "current"), pendingSlot = "morning",
        )
        val after = afterDone(listOf(t), t, NOW).single()
        assertEquals("afternoon", after.pendingSlot)
        assertEquals(1.0, after.urgency, 0.0)
        assertTrue(after.important)
    }

    @Test
    fun lastSlotFinishesTheDay() {
        val t = task(1, "slot", urgency = 1.0, today = listOf("evening" to "current"), pendingSlot = "evening")
        val after = afterDone(listOf(t), t, NOW).single()
        assertNull(after.pendingSlot)
        assertEquals(-1.0, after.urgency, 0.0)
        assertTrue(laterToday(listOf(after)).isEmpty())
        assertTrue(comingUp(listOf(after)).isEmpty())
    }

    @Test
    fun placingMovesTheNewTaskIntoAGapOfTheOthers() {
        val ids = listOf(9L, 1L, 2L, 3L)
        assertEquals(listOf(9L, 1L, 2L, 3L), placeAt(ids, 9L, 0))
        assertEquals(listOf(1L, 9L, 2L, 3L), placeAt(ids, 9L, 1))
        assertEquals(listOf(1L, 2L, 3L, 9L), placeAt(ids, 9L, 3))
        assertEquals(listOf(9L, 1L, 2L, 3L), placeAt(listOf(1L, 2L, 9L, 3L), 9L, 0))
    }

    @Test
    fun sectionsSitOnOneLineWithTasks() {
        val a = task(1, "once").copy(position = 0)
        val b = task(2, "once", urgency = -1.0).copy(position = 2)
        val c = task(3, "once").copy(position = 3)
        val morning = Section(10, "Morning", 1)
        val empty = Section(11, "Empty", 3)
        val list = entries(listOf(a, b, c), listOf(empty, morning))
        assertEquals(listOf<Any>(1L, "s10", 2L, "s11", 3L), list.map { it.key })
        assertEquals(mapOf(10L to listOf(b), 11L to listOf(c)), sectionTasks(list))
        assertEquals(listOf<Any>(1L, "s10", "s11", 3L), visible(list, folded = setOf(10L)).map { it.key })
        assertEquals(listOf<Any>(1L, "s10", 2L, "s11", 3L), visible(list, setOf(10L), keep = 2L).map { it.key })
        val (tasks, sections) = positioned(list)
        assertEquals(listOf(0L, 2L, 4L), tasks.map { it.position })
        assertEquals(listOf(1L, 3L), sections.map { it.position })
    }

    @Test
    fun anOffDayStaysHiddenWhenCheckedAnyway() {
        val t = task(1, "slot", urgency = -1.0, today = listOf("morning" to "overdue"), pendingSlot = "morning")
            .copy(weekdays = listOf("mon", "wed"), backOn = "2026-11-11")
        val after = afterDone(listOf(t), t, NOW).single()
        assertEquals(listOf("morning" to "done"), after.today)
        assertEquals(-1.0, after.urgency, 0.0)
        assertEquals(listOf(after), comingUp(listOf(after)))
    }

    @Test
    fun intervalRestartsItsClock() {
        val t = task(1, "interval", urgency = 1.2, afterDays = 6)
        val after = afterDone(listOf(t), t, NOW).single()
        assertEquals(NOW, after.lastDoneAt)
        assertEquals(-1.0, after.urgency, 0.0)
        assertFalse(after.important)
        assertEquals(-3.0, afterDone(listOf(t.copy(rampDays = 2)), t, NOW).single().urgency, 0.0)
    }

    @Test
    fun everyDayIntervalStaysVisible() {
        val t = task(1, "interval", urgency = 1.0, afterDays = 0)
        assertEquals(0.0, afterDone(listOf(t), t, NOW).single().urgency, 0.0)
    }
}
