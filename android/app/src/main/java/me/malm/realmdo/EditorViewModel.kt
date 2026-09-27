package me.malm.realmdo

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** A step in the routine editor, keyed so a drag keeps track of it. */
data class EditStep(val key: Long, val step: Step)

/**
 * The task being written and, over it, its routine being edited. Both live
 * here rather than in the screens so a rotation or the trip into the routine
 * editor loses nothing. Nothing reaches the server until the task is saved.
 */
class EditorViewModel : ViewModel() {
    private val _draft = MutableStateFlow<Draft?>(null)
    val draft: StateFlow<Draft?> = _draft
    private var original: Draft? = null

    private val _routine = MutableStateFlow<List<EditStep>?>(null)
    /** Non-null while the routine editor is open. */
    val routine: StateFlow<List<EditStep>?> = _routine
    private var nextKey = 0L

    val dirty: Boolean get() = _draft.value != original

    fun startNew() = open(Draft())

    fun startEdit(task: Task) = open(Draft.of(task))

    private fun open(d: Draft) {
        original = d
        _draft.value = d
        _routine.value = null
    }

    fun update(f: (Draft) -> Draft) = _draft.update { it?.let(f) }

    fun close() {
        _draft.value = null
        _routine.value = null
        original = null
    }

    fun openRoutine() {
        _routine.value = _draft.value?.steps.orEmpty().map(::keyed)
    }

    fun keyed(step: Step): EditStep = EditStep(nextKey++, step)

    fun updateRoutine(f: (List<EditStep>) -> List<EditStep>) = _routine.update { it?.let(f) }

    val routineDirty: Boolean
        get() = _routine.value?.map { it.step } != _draft.value?.steps.orEmpty()

    /** Back to the task with these steps; still unsaved until the task is. */
    fun keepRoutine() {
        val steps = _routine.value ?: return
        update { it.copy(steps = steps.map(EditStep::step)) }
        _routine.value = null
    }

    fun dropRoutine() {
        _routine.value = null
    }
}
