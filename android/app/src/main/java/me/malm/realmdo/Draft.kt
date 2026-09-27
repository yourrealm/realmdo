package me.malm.realmdo

import org.json.JSONArray
import org.json.JSONObject

/** The four slots of a day, in day order, as the API names them. */
val SLOTS = listOf("morning", "before_lunch", "afternoon", "evening")

/** Limits the server enforces, checked here first so the editor can say so. */
const val MAX_NOTES = 4000
const val MAX_STEPS = 50
const val MAX_STEP_TITLE = 200
const val MAX_STEP_SPEECH = 500
const val MAX_STEP_DETAILS = 2000
const val MAX_STEP_SECONDS = 6 * 3600
const val MAX_DAYS = 3650

/**
 * A task being written in the editor: a new one ([id] null) or an edit. The
 * kind of an existing task is fixed, as the API requires.
 */
data class Draft(
    val id: Long? = null,
    val title: String = "",
    val kind: String = "once",
    /** One-off: `YYYY-MM-DD`, or none. */
    val dueOn: String? = null,
    /** Interval: days out of sight after a completion. */
    val afterDays: Int = 3,
    /** Interval: days from appearing to urgent; null is the default (the same as [afterDays]). */
    val rampDays: Int? = null,
    /** Slot: which slots of the day. */
    val slots: Set<String> = emptySet(),
    val notes: String = "",
    val steps: List<Step> = emptyList(),
) {
    val isNew: Boolean get() = id == null

    /** Why it cannot be saved yet, or null when it can. */
    val problem: String?
        get() = when {
            title.isBlank() -> "Give it a title."
            kind == "slot" && slots.isEmpty() -> "Pick at least one time of day."
            notes.length > MAX_NOTES -> "Notes are limited to $MAX_NOTES characters."
            else -> null
        }

    /**
     * The request body. A new task names its kind; an edit sends every field
     * of its kind, with explicit nulls, so what is on screen is what is saved.
     */
    fun toJson(): JSONObject {
        val o = JSONObject().put("title", title.trim())
        if (isNew) o.put("kind", kind)
        when (kind) {
            "once" -> o.put("due_on", dueOn ?: JSONObject.NULL)
            "interval" -> o.put("after_days", afterDays).put("ramp_days", rampDays ?: JSONObject.NULL)
            "slot" -> o.put("slots", JSONArray(SLOTS.filter { it in slots }))
        }
        o.put("notes", notes.trim().ifEmpty { null } ?: JSONObject.NULL)
        o.put("steps", if (steps.isEmpty()) JSONObject.NULL else JSONArray(steps.map { it.toJson() }))
        return o
    }

    companion object {
        fun of(t: Task): Draft = Draft(
            id = t.id,
            title = t.title,
            kind = t.kind,
            dueOn = t.dueOn,
            afterDays = (t.afterDays ?: 3).toInt(),
            rampDays = t.rampDays?.toInt(),
            slots = t.slots.orEmpty().toSet(),
            notes = t.notes.orEmpty(),
            steps = t.steps.orEmpty(),
        )
    }
}

/** Why a routine cannot be saved yet, or null when it can. */
fun stepsProblem(steps: List<Step>): String? = when {
    steps.size > MAX_STEPS -> "A routine holds at most $MAX_STEPS steps."
    steps.any { it.title.isBlank() } -> "Every step needs a title."
    steps.any { it.title.length > MAX_STEP_TITLE } -> "Step titles are limited to $MAX_STEP_TITLE characters."
    steps.any { (it.speech?.length ?: 0) > MAX_STEP_SPEECH } -> "What a step says is limited to $MAX_STEP_SPEECH characters."
    steps.any { (it.details?.length ?: 0) > MAX_STEP_DETAILS } -> "Details are limited to $MAX_STEP_DETAILS characters."
    else -> null
}

private val SIDE = Regex("""\b(left|right|Left|Right|LEFT|RIGHT)\b""")

/**
 * The same step for the other side: "Pigeon, left" becomes "Pigeon, right",
 * in the speech too. Null when the title names no side.
 */
fun otherSide(step: Step): Step? {
    if (!SIDE.containsMatchIn(step.title)) return null
    fun swap(s: String) = SIDE.replace(s) { m ->
        when (m.value) {
            "left" -> "right"
            "right" -> "left"
            "Left" -> "Right"
            "Right" -> "Left"
            "LEFT" -> "RIGHT"
            else -> "LEFT"
        }
    }
    return step.copy(title = swap(step.title), speech = step.speech?.let(::swap))
}

/** The side [otherSide] would add, for its button: "right" for a left step. */
fun otherSideName(step: Step): String? =
    SIDE.find(step.title)?.value?.lowercase()?.let { if (it == "left") "right" else "left" }
