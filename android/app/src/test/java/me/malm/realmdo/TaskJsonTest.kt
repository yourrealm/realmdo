package me.malm.realmdo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The server's task view, as `GET /api/tasks` serialises it. */
class TaskJsonTest {
    @Test
    fun onceTaskWithNulls() {
        val t = Task.from(
            JSONObject(
                """{"id":7,"title":"Buy milk","kind":"once","due_on":null,"after_days":null,"ramp_days":null,
                   "slots":null,"created_at":1758000000,"done_at":null,"last_done_at":null,
                   "urgency":0.5,"important":false,"today":null,"pending_slot":null}""",
            ),
        )
        assertEquals(7L, t.id)
        assertEquals("Buy milk", t.title)
        assertNull(t.dueOn)
        assertNull(t.afterDays)
        assertNull(t.lastDoneAt)
        assertNull(t.today)
        assertNull(t.pendingSlot)
    }

    @Test
    fun slotTaskCarriesTodayAndPendingSlot() {
        val t = Task.from(
            JSONObject(
                """{"id":2,"title":"Brush teeth","kind":"slot","due_on":null,"after_days":null,"ramp_days":null,
                   "slots":["before_lunch","evening"],"created_at":1,"done_at":null,"last_done_at":1758000000,
                   "urgency":1.5,"important":true,
                   "today":[{"slot":"before_lunch","state":"overdue"},{"slot":"evening","state":"upcoming"}],
                   "pending_slot":"before_lunch"}""",
            ),
        )
        assertTrue(t.important)
        assertEquals(listOf("before_lunch" to "overdue", "evening" to "upcoming"), t.today)
        assertEquals("before_lunch", t.pendingSlot)
        assertEquals(1758000000L, t.lastDoneAt)
    }

    @Test
    fun intervalTaskWithDue() {
        val t = Task.from(
            JSONObject(
                """{"id":3,"title":"Clean","kind":"interval","due_on":"2026-09-20","after_days":6,"ramp_days":null,
                   "slots":null,"created_at":1,"done_at":null,"last_done_at":null,"urgency":0.2,"important":false,
                   "today":null,"pending_slot":null}""",
            ),
        )
        assertEquals(6L, t.afterDays)
        assertEquals("2026-09-20", t.dueOn)
    }
}
