package me.malm.realmdo

import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Where a run through a task's steps stands. */
data class RoutineState(
    val task: Task,
    val steps: List<Step>,
    val index: Int = 0,
    /** Time left on the current step. */
    val leftMs: Long,
    val running: Boolean = true,
    /** Past the last step; the task is marked done by hand from here. */
    val finished: Boolean = false,
    /** A try-out from the routine editor: nothing to mark done at the end. */
    val preview: Boolean = false,
) {
    val step: Step get() = steps[index]
    val next: Step? get() = steps.getOrNull(index + 1)
}

/** The spoken warning before a step ends, and the shortest step that gets it. */
const val WARN_MS = 10_000L
const val WARN_MIN_SECONDS = 20

/** A few seconds into a step, "previous" restarts it; before that it steps back. */
const val RESTART_AFTER_MS = 3_000L

fun previousIndex(index: Int, elapsedMs: Long): Int =
    if (elapsedMs > RESTART_AFTER_MS || index == 0) index else index - 1

/**
 * Runs a routine: one step at a time, each announced by voice and counted
 * down to the second. Lives as long as the activity, so a rotation keeps it.
 */
class RoutineViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<RoutineState?>(null)
    val state: StateFlow<RoutineState?> = _state
    // Started on the first run, not for every screen that holds this model.
    private val voiceLazy = lazy { Voice(app) }
    private val voice by voiceLazy
    private var ticker: Job? = null
    /** `elapsedRealtime` at which the current step ends, while running. */
    private var endsAt = 0L

    fun start(task: Task) {
        val steps = task.steps ?: return
        _state.value = RoutineState(task, steps, leftMs = steps[0].seconds * 1000L)
        begin(0)
    }

    /** Runs steps still being edited, under [title], without a task to complete. */
    fun preview(title: String, steps: List<Step>) {
        if (steps.isEmpty()) return
        val task = Task(
            id = -1, title = title, kind = "once", dueOn = null, afterDays = null, lastDoneAt = null,
            urgency = 0.0, important = false, today = null, pendingSlot = null, notes = null, steps = steps,
        )
        _state.value = RoutineState(task, steps, leftMs = steps[0].seconds * 1000L, preview = true)
        begin(0)
    }

    fun pause() {
        val s = _state.value ?: return
        if (!s.running) return
        ticker?.cancel()
        _state.value = s.copy(running = false, leftMs = (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        voice.stop()
    }

    fun resume() {
        val s = _state.value ?: return
        if (s.running || s.finished) return
        _state.value = s.copy(running = true)
        tick(s.leftMs)
    }

    fun next() {
        val s = _state.value ?: return
        if (s.finished) return
        if (s.next != null) begin(s.index + 1) else finish()
    }

    fun previous() {
        val s = _state.value ?: return
        if (s.finished) {
            begin(s.steps.lastIndex)
            return
        }
        begin(previousIndex(s.index, s.step.seconds * 1000L - s.leftMs))
    }

    fun stop() {
        ticker?.cancel()
        _state.value = null
        if (voiceLazy.isInitialized()) voice.stop()
    }

    private fun begin(index: Int) {
        val s = _state.value ?: return
        val step = s.steps[index]
        val left = step.seconds * 1000L
        _state.value = s.copy(index = index, leftMs = left, running = true, finished = false)
        voice.say(announcement(step))
        tick(left)
    }

    private fun finish() {
        ticker?.cancel()
        val s = _state.value ?: return
        _state.value = s.copy(leftMs = 0, running = false, finished = true)
        voice.say("All done.")
    }

    private fun tick(left: Long) {
        endsAt = SystemClock.elapsedRealtime() + left
        ticker?.cancel()
        ticker = viewModelScope.launch {
            // Resumed with less than the warning left: it was said, or skipped.
            var warned = left <= WARN_MS
            while (true) {
                delay(TICK_MS)
                val s = _state.value ?: return@launch
                val now = endsAt - SystemClock.elapsedRealtime()
                if (now <= 0) {
                    next()
                    return@launch
                }
                if (!warned && now <= WARN_MS) {
                    warned = true
                    if (s.step.seconds >= WARN_MIN_SECONDS) voice.say("10 seconds.")
                }
                _state.value = s.copy(leftMs = now)
            }
        }
    }

    override fun onCleared() {
        ticker?.cancel()
        if (voiceLazy.isInitialized()) voice.shutdown()
    }

    private companion object {
        const val TICK_MS = 200L
    }
}
