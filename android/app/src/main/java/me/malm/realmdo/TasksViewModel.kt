package me.malm.realmdo

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The server's defaults, shown until the user's own times arrive. */
private val DEFAULT_SLOT_TIMES = mapOf(
    "morning" to "07:00",
    "before_lunch" to "11:00",
    "afternoon" to "14:00",
    "evening" to "19:00",
)

data class UiState(
    val configured: Boolean = false,
    val username: String = "",
    /** Home's origin: the realm this phone signs in to. */
    val home: String = "",
    /** Finding Home or trading the browser's code for tokens; not while the browser is open. */
    val connecting: Boolean = false,
    /** Every open task, in the user's order. */
    val tasks: List<Task> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val liveUpdate: Boolean = false,
    /** Whether refreshes reach the app in the background; taken on each resume. */
    val background: BackgroundRefresh.Status? = null,
    /** Slot name to its start time, `HH:MM`, from the user's settings. */
    val slotTimes: Map<String, String> = DEFAULT_SLOT_TIMES,
    /** The user's IANA time zone; null until the settings arrive. */
    val timezone: String? = null,
    /** The done history, newest first; null until first loaded. */
    val history: List<Completion>? = null,
    /** A short confirmation for the snackbar, taken once by [TasksViewModel.snackShown]. */
    val snack: String? = null,
)

/** Holds the list and runs every API call off the main thread. */
class TasksViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = Prefs(app)
    private val _state = MutableStateFlow(
        UiState(
            configured = prefs.configured,
            username = prefs.username,
            home = prefs.home,
            liveUpdate = prefs.liveUpdate,
        ),
    )
    val state: StateFlow<UiState> = _state

    /** On resume: judge the background refresh first, since [refresh] reschedules it. */
    fun resumed() {
        val status = BackgroundRefresh.onResume(getApplication())
        _state.update { it.copy(background = status) }
        refresh()
    }

    /** A [quiet] refresh shows no spinner; it follows the user's own changes. */
    fun refresh(quiet: Boolean = false) {
        val api = Api.from(prefs) ?: return disconnected()
        if (!quiet) _state.update { it.copy(loading = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.tasks() } }
            // Re-read who the token maps to; the server's answer can change.
            val name = withContext(Dispatchers.IO) { runCatching { api.me() }.getOrNull() }
            if (name != null && name != prefs.username) prefs.username = name
            val day = withContext(Dispatchers.IO) { runCatching { api.settings() }.getOrNull() }
            _state.update { s ->
                result.fold(
                    {
                        s.copy(
                            tasks = it,
                            loading = false,
                            error = null,
                            username = name ?: s.username,
                            slotTimes = day?.slotTimes ?: s.slotTimes,
                            timezone = day?.timezone ?: s.timezone,
                        )
                    },
                    { s.copy(loading = false, error = it.message ?: "request failed") },
                )
            }
            if (!prefs.configured) return@launch disconnected()
            // The same list feeds the notification; no second fetch.
            result.onSuccess { tasks -> notify(tasks) }
        }
    }

    fun done(task: Task) =
        mutate({ afterDone(it, task, System.currentTimeMillis() / 1000) }, { it.done(task.id, task.pendingSlot) })

    /** Creates or saves a task; [onDone] gets the error, or null once it is saved. */
    fun save(draft: Draft, onDone: (String?) -> Unit) {
        val api = Api.from(prefs) ?: return
        viewModelScope.launch {
            val body = draft.toJson()
            val result = withContext(Dispatchers.IO) {
                runCatching { if (draft.id == null) api.create(body) else api.update(draft.id, body) }
            }
            onDone(result.exceptionOrNull()?.let { it.message ?: "request failed" })
            if (result.isSuccess) refresh(quiet = true)
        }
    }

    fun delete(id: Long, title: String) =
        mutate({ tasks -> tasks.filter { it.id != id } }, { it.delete(id) }, snack = "Deleted “$title”")

    /**
     * Puts the tasks in this order. The list moves at once; the server's
     * answer then replaces it, or a refresh puts it back on failure.
     */
    fun order(ids: List<Long>) {
        val api = Api.from(prefs) ?: return
        val byId = _state.value.tasks.associateBy { it.id }
        val moved = ids.mapNotNull(byId::get)
        _state.update { it.copy(tasks = moved + it.tasks.filter { t -> t.id !in ids }) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.order(ids) } }
            result.fold(
                { tasks ->
                    _state.update { it.copy(tasks = tasks, error = null) }
                    notify(tasks)
                },
                { e ->
                    _state.update { it.copy(error = e.message ?: "request failed") }
                    refresh(quiet = true)
                },
            )
        }
    }

    fun loadHistory() {
        val api = Api.from(prefs) ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.completions() } }
            _state.update { s ->
                result.fold(
                    { s.copy(history = it, error = null) },
                    { s.copy(error = it.message ?: "request failed") },
                )
            }
        }
    }

    /** Undoes a completion, then reloads both the history and the list. */
    fun restore(c: Completion) {
        val api = Api.from(prefs) ?: return
        _state.update { s -> s.copy(history = s.history?.filter { it.id != c.id }) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.restore(c.id) } }
            result.fold(
                { _state.update { it.copy(snack = "“${c.title}” is back in your list") } },
                { e -> _state.update { it.copy(error = e.message ?: "request failed") } },
            )
            loadHistory()
            refresh(quiet = true)
        }
    }

    /**
     * Saves the time zone and slot times, then refreshes: they move every
     * task's standing. [onDone] gets the error, or null once saved.
     */
    fun saveDay(day: DaySettings, onDone: (String?) -> Unit) {
        val api = Api.from(prefs) ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { api.saveSettings(day) } }
            result.fold(
                { saved ->
                    _state.update { it.copy(slotTimes = saved.slotTimes, timezone = saved.timezone) }
                    onDone(null)
                    refresh(quiet = true)
                },
                { onDone(it.message ?: "request failed") },
            )
        }
    }

    fun snackShown() = _state.update { it.copy(snack = null) }

    /**
     * Step one of connecting: find Home at what the user typed or scanned,
     * register once, then [open] its consent page in the browser.
     */
    fun connect(raw: String, open: (String) -> Unit) {
        if (raw.trim().startsWith("realm:")) {
            return _state.update { it.copy(error = "That is the companion app's code. Scan the address on Home's Phones page.") }
        }
        val home = Auth.home(raw) ?: return _state.update { it.copy(error = "Not a realm address.") }
        _state.update { it.copy(connecting = true, error = null, home = home) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val endpoints = Auth.discover(home)
                    val clientId = prefs.clientId.takeIf { prefs.home == home && it.isNotEmpty() }
                        ?: Auth.register(endpoints.registration)
                    val verifier = Auth.verifier()
                    val state = Auth.verifier()
                    prefs.startConnect(home, clientId, endpoints, verifier, state)
                    Auth.authorizeUrl(endpoints.authorization, clientId, state, Auth.challenge(verifier), Build.MODEL)
                }
            }
            _state.update { it.copy(connecting = false, error = result.exceptionOrNull()?.let { e -> e.message ?: "Home did not answer." }) }
            result.onSuccess(open)
        }
    }

    /** Step two: the browser came back to [Auth.REDIRECT]. Trade the code for tokens. */
    fun finishConnect(redirect: String) {
        val verifier = prefs.pendingVerifier
        val back = Auth.redirect(redirect, prefs.pendingState) ?: return
        prefs.endConnect()
        when (back) {
            is Auth.Redirect.Failed -> {
                // Anything but a cancel may be a client this Home forgot; register again next time.
                if (back.error != "access_denied") prefs.forgetClient()
                _state.update { it.copy(error = if (back.error == "access_denied") "Not connected." else back.error) }
            }
            is Auth.Redirect.Code -> {
                _state.update { it.copy(connecting = true, error = null) }
                viewModelScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            prefs.saveTokens(Auth.exchange(prefs.tokenEndpoint, prefs.clientId, back.code, verifier))
                            Api(prefs).me()
                        }
                    }
                    result.fold(
                        { name ->
                            prefs.username = name
                            _state.update { it.copy(configured = true, connecting = false, username = name, error = null) }
                            refresh()
                        },
                        { e ->
                            prefs.dropTokens()
                            _state.update { it.copy(connecting = false, error = e.message ?: "request failed") }
                        },
                    )
                }
            }
        }
    }

    /** Signs out at Home too, so the phone leaves its Phones page. */
    fun signOut() {
        val endpoint = prefs.revocationEndpoint
        val clientId = prefs.clientId
        val refreshToken = prefs.refreshToken
        prefs.clear()
        TaskNotification.cancel(getApplication())
        _state.value = UiState(home = prefs.home)
        if (refreshToken.isNotEmpty()) {
            viewModelScope.launch(Dispatchers.IO) { runCatching { Auth.revoke(endpoint, clientId, refreshToken) } }
        }
    }

    /** The realm ended the connection while the app was away; back to the connect screen. */
    private fun disconnected() {
        if (!_state.value.configured) return
        TaskNotification.cancel(getApplication())
        _state.value = UiState(home = prefs.home, liveUpdate = prefs.liveUpdate, error = "Disconnected from your realm. Connect again.")
    }

    fun setLiveUpdate(on: Boolean) {
        prefs.liveUpdate = on
        _state.update { it.copy(liveUpdate = on) }
        val tasks = _state.value.tasks
        viewModelScope.launch(Dispatchers.IO) { TaskNotification.show(getApplication(), tasks) }
    }

    private suspend fun notify(tasks: List<Task>) =
        withContext(Dispatchers.IO) { TaskNotification.show(getApplication(), tasks) }

    /**
     * Applies [change] to the list and the notification at once, then calls
     * the server. A failure puts the old list back; either way a quiet
     * refresh then brings in what the server shows.
     */
    private fun mutate(change: (List<Task>) -> List<Task>, call: (Api) -> Unit, snack: String? = null) {
        val api = Api.from(prefs) ?: return
        val before = _state.value.tasks
        val after = change(before)
        _state.update { it.copy(tasks = after) }
        viewModelScope.launch {
            notify(after)
            val result = withContext(Dispatchers.IO) { runCatching { call(api) } }
            _state.update { s ->
                result.fold(
                    { s.copy(snack = snack ?: s.snack) },
                    { e -> s.copy(tasks = before, error = e.message ?: "request failed") },
                )
            }
            if (result.isFailure) notify(before)
            refresh(quiet = true)
        }
    }
}
