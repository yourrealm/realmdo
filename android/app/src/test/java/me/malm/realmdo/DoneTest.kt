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
        assertTrue(isLater(after))
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
        assertFalse(isLater(after))
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
