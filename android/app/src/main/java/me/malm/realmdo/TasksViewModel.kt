package me.malm.realmdo

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val configured: Boolean = false,
    val username: String = "",
    val tasks: List<Task> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val liveUpdate: Boolean = false,
    /** Whether refreshes reach the app in the background; taken on each resume. */
    val background: BackgroundRefresh.Status? = null,
)

/** Holds the list and runs every API call off the main thread. */
class TasksViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val _state = MutableStateFlow(
        UiState(configured = prefs.configured, username = prefs.username, liveUpdate = prefs.liveUpdate),
    )
    val state: StateFlow<UiState> = _state

    /** On resume: judge the background refresh first, since [refresh] reschedules it. */
    fun resumed() {
        val status = BackgroundRefresh.onResume(getApplication())
        _state.update { it.copy(background = status) }
        refresh()
    }

    fun refresh() {
        val api = Api.from(prefs) ?: return
        _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.tasks() } }
            // Re-read who the token maps to; the server's answer can change.
            val name = withContext(Dispatchers.IO) { runCatching { api.me() }.getOrNull() }
            if (name != null && name != prefs.username) prefs.username = name
            _state.update { s ->
                result.fold(
                    { s.copy(tasks = it, loading = false, error = null, username = name ?: s.username) },
                    { s.copy(loading = false, error = it.message ?: "request failed") },
                )
            }
            // The same list feeds the notification; no second fetch.
            result.onSuccess { tasks ->
                withContext(Dispatchers.IO) { TaskNotification.show(getApplication(), tasks) }
            }
        }
    }

    fun add(title: String) = mutate { it.add(title) }

    fun done(task: Task) = mutate { it.done(task.id, task.pendingSlot) }

    /** Checks the connection, then stores it. */
    fun connect(baseUrl: String, token: String, onDone: (String?) -> Unit) {
        val api = Api(baseUrl.trimEnd('/'), token.trim())
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.me() } }
            result.fold(
                { name ->
                    prefs.baseUrl = baseUrl
                    prefs.token = token
                    prefs.username = name
                    _state.update { it.copy(configured = true, username = name, error = null) }
                    onDone(null)
                    refresh()
                },
                { onDone(it.message ?: "request failed") },
            )
        }
    }

    fun signOut() {
        prefs.clear()
        TaskNotification.cancel(getApplication())
        _state.value = UiState()
    }

    fun setLiveUpdate(on: Boolean) {
        prefs.liveUpdate = on
        _state.update { it.copy(liveUpdate = on) }
        val tasks = _state.value.tasks
        viewModelScope.launch(Dispatchers.IO) { TaskNotification.show(getApplication(), tasks) }
    }

    private fun mutate(call: (Api) -> Unit) {
        val api = Api.from(prefs) ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { call(api) } }
            result.onFailure { e -> _state.update { it.copy(error = e.message ?: "request failed") } }
            refresh()
        }
    }
}
