package me.malm.realmdo

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/** The editor's draft as the API reads it, and the routine helpers. */
class DraftTest {
    @Test
    fun aNewOneOffNamesItsKindAndClearsWhatIsEmpty() {
        val o = Draft(title = "  Book dentist ").toJson()
        assertEquals("Book dentist", o.getString("title"))
        assertEquals("once", o.getString("kind"))
        assertTrue(o.isNull("due_on"))
        assertTrue(o.isNull("notes"))
        assertTrue(o.isNull("steps"))
        assertFalse(o.has("after_days"))
        assertFalse(o.has("slots"))
    }

    @Test
    fun anEditLeavesTheKindOut() {
        val o = Draft(id = 4, title = "Bathroom", kind = "interval", afterDays = 6).toJson()
        assertFalse(o.has("kind"))
        assertEquals(6, o.getInt("after_days"))
        assertTrue("default ramp is an explicit null", o.isNull("ramp_days"))
        assertFalse(o.has("due_on"))
    }

    @Test
    fun slotsGoInDayOrder() {
        val o = Draft(title = "Teeth", kind = "slot", slots = setOf("evening", "morning")).toJson()
        val arr = o.getJSONArray("slots")
        assertEquals(listOf("morning", "evening"), (0 until arr.length()).map { arr.getString(it) })
    }

    @Test
    fun stepsCarryTheirWordsAndNulls() {
        val o = Draft(title = "Yoga", steps = listOf(Step("Frog", null, "Knees wide", 90))).toJson()
        val step = o.getJSONArray("steps").getJSONObject(0)
        assertEquals("Frog", step.getString("title"))
        assertTrue(step.isNull("speech"))
        assertEquals("Knees wide", step.getString("details"))
        assertEquals(90, step.getInt("seconds"))
    }

    @Test
    fun problemsSayWhatIsMissing() {
        assertEquals("Give it a title.", Draft().problem)
        assertEquals("Pick at least one time of day.", Draft(title = "Teeth", kind = "slot").problem)
        assertNull(Draft(title = "Teeth", kind = "slot", slots = setOf("morning")).problem)
        assertEquals("Every step needs a title.", stepsProblem(listOf(Step(" ", null, null, 60))))
        assertNull(stepsProblem(listOf(Step("Frog", null, null, 60))))
    }

    @Test
    fun aDraftOfATaskRoundTrips() {
        val t = Task.from(
            JSONObject(
                """{"id":3,"title":"Bathroom","kind":"interval","due_on":null,"after_days":6,"ramp_days":5,
                   "slots":null,"notes":"Under the sink","created_at":1,"done_at":null,"last_done_at":null,
                   "urgency":0.2,"important":false,"today":null,"pending_slot":null}""",
            ),
        )
        val d = Draft.of(t)
        assertEquals(3L, d.id)
        assertEquals(6, d.afterDays)
        assertEquals(5, d.rampDays)
        assertEquals("Under the sink", d.notes)
    }

    @Test
    fun theOtherSideSwapsTitleAndSpeech() {
        val left = Step("Pigeon, left", "Pigeon. Left leg forward.", null, 60)
        val right = otherSide(left)!!
        assertEquals("Pigeon, right", right.title)
        assertEquals("Pigeon. Right leg forward.", right.speech)
        assertEquals("right", otherSideName(left))
        assertEquals("left", otherSideName(right))
        assertNull(otherSide(Step("Frog", null, null, 60)))
        assertNull("only whole words", otherSide(Step("Bright light", null, null, 60)))
    }

    @Test
    fun hiddenLabelsSayWhenATaskComesBack() {
        val today = LocalDate.of(2026, 9, 17)
        val doneOn = today.minusDays(4).atStartOfDay(ZoneOffset.UTC).toEpochSecond()
        val t = Task(
            id = 1, title = "Water plants", kind = "interval", dueOn = null, afterDays = 6, lastDoneAt = doneOn,
            urgency = -0.3, important = false, today = null, pendingSlot = null, notes = null,
        )
        assertEquals("back in 2 d", hiddenLabel(t, today, ZoneOffset.UTC))
        assertEquals("back tomorrow", hiddenLabel(t.copy(afterDays = 5), today, ZoneOffset.UTC))
        assertNull(hiddenLabel(t.copy(urgency = 0.0), today, ZoneOffset.UTC))
        val slot = t.copy(kind = "slot", afterDays = null)
        assertEquals("done for today", hiddenLabel(slot, today, ZoneOffset.UTC))
        assertEquals("later today", hiddenLabel(slot.copy(pendingSlot = "evening"), today, ZoneOffset.UTC))
    }

    @Test
    fun dayHeadingsAndHelp() {
        val today = LocalDate.of(2026, 9, 27)
        assertEquals("Today", dayHeading(today, today))
        assertEquals("Yesterday", dayHeading(today.minusDays(1), today))
        assertEquals("Thursday 24 September", dayHeading(today.minusDays(3), today))
        assertEquals("Out of sight for 6 days after you finish it, then urgent 6 days later.", repeatHelp(6, null))
        assertEquals("It shows again right after you finish it, then urgent 1 day later.", repeatHelp(0, null))
    }
}
