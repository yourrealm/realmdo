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
        assertNull(t.notes)
    }

    @Test
    fun notesKeepTheirLineBreaks() {
        val t = Task.from(
            JSONObject(
                """{"id":8,"title":"Buy protein powder","kind":"once","due_on":null,"after_days":null,"ramp_days":null,
                   "slots":null,"notes":"Whey, vanilla\nunder 400 kr","created_at":1,"done_at":null,"last_done_at":null,
                   "urgency":0.5,"important":false,"today":null,"pending_slot":null}""",
            ),
        )
        assertEquals("Whey, vanilla\nunder 400 kr", t.notes)
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

    @Test
    fun stepsKeepTheirOrderAndOptionalParts() {
        val t = Task.from(
            JSONObject(
                """{"id":4,"title":"Yoga","kind":"interval","due_on":null,"after_days":2,"ramp_days":null,
                   "slots":null,"created_at":1,"done_at":null,"last_done_at":null,"urgency":-0.5,"important":false,
                   "today":null,"pending_slot":null,
                   "steps":[{"title":"Frog","speech":null,"details":null,"seconds":90},
                            {"title":"Pigeon, left","speech":"Pigeon. Left leg forward.","details":"Square the hips.","seconds":60}]}""",
            ),
        )
        assertEquals(
            listOf(
                Step("Frog", null, null, 90),
                Step("Pigeon, left", "Pigeon. Left leg forward.", "Square the hips.", 60),
            ),
            t.steps,
        )
    }

    @Test
    fun toJsonRoundTrips() {
        val t = Task(
            id = 9, title = "Stretch", kind = "slot", dueOn = "2026-10-30", afterDays = 3, lastDoneAt = 1758000000,
            urgency = -0.5, important = false, today = listOf("morning" to "done", "evening" to "upcoming"),
            pendingSlot = "evening", notes = "Slowly\nboth sides", steps = listOf(Step("Frog", null, "Hips low.", 90)),
            leadDays = 0, rampDays = 2, slots = listOf("morning", "evening"), createdAt = 1757000000,
        )
        assertEquals(t, Task.from(t.toJson()))
        val bare = Task(1, "Plain", "once", null, null, null, 0.5, false, null, null, null)
        assertEquals(bare, Task.from(bare.toJson()))
    }

    @Test
    fun noStepsIsNull() {
        val t = Task.from(
            JSONObject(
                """{"id":5,"title":"Plain","kind":"once","due_on":null,"after_days":null,"ramp_days":null,
                   "slots":null,"created_at":1,"done_at":null,"last_done_at":null,"urgency":0.5,"important":false,
                   "today":null,"pending_slot":null,"steps":null}""",
            ),
        )
        assertNull(t.steps)
    }
}
