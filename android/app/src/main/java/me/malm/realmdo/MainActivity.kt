package me.malm.realmdo

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private lateinit var vm: TasksViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[TasksViewModel::class.java]
        enableEdgeToEdge()
        setContent {
            RealmdoTheme {
                val model: TasksViewModel = viewModel()
                App(model)
            }
        }
        pairFrom(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pairFrom(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.resumed()
    }

    /** A `realmdo://pair` link opened from the camera app or any scanner. */
    private fun pairFrom(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val pairing = Pairing.parse(intent.dataString) ?: return
        intent.data = null // once, not again on rotation
        vm.connect(pairing.baseUrl, pairing.token) { err ->
            Toast.makeText(this, err ?: "Paired as ${vm.state.value.username}", Toast.LENGTH_LONG).show()
        }
    }
}

private enum class Tab(val label: String) { Today("Today"), Order("Order"), Done("Done") }

/**
 * One screen at a time, most specific first: pairing, a running routine, the
 * routine editor, the task editor, settings, then the three tabs.
 */
@Composable
private fun App(vm: TasksViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val routineVm: RoutineViewModel = viewModel()
    val routine by routineVm.state.collectAsStateWithLifecycle()
    val editor: EditorViewModel = viewModel()
    val draft by editor.draft.collectAsStateWithLifecycle()
    val steps by editor.routine.collectAsStateWithLifecycle()
    var showSettings by rememberSaveable { mutableStateOf(false) }
    when {
        !state.configured -> SetupScreen(vm, state)
        routine != null -> PlayerScreen(
            routine!!,
            routineVm,
            // The list's copy, for a pending slot that moved during the run.
            onDone = { task -> vm.done(state.tasks.find { it.id == task.id } ?: task) },
        )
        draft != null && steps != null -> RoutineEditorScreen(
            draft!!.title,
            steps!!,
            editor,
            onTry = { s -> routineVm.preview(draft!!.title.ifBlank { "Routine" }, s) },
        )
        draft != null -> EditorScreen(draft!!, editor, vm, state.slotTimes)
        showSettings -> SettingsScreen(vm, state, onBack = { showSettings = false })
        else -> Tabs(
            vm,
            state,
            onSettings = { showSettings = true },
            onStart = routineVm::start,
            onAdd = editor::startNew,
            onEdit = editor::startEdit,
        )
    }
}

@Composable
private fun Tabs(
    vm: TasksViewModel,
    state: UiState,
    onSettings: () -> Unit,
    onStart: (Task) -> Unit,
    onAdd: () -> Unit,
    onEdit: (Task) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    var sheetId by rememberSaveable { mutableStateOf<Long?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.snack) {
        state.snack?.let {
            vm.snackShown()
            snackbar.showSnackbar(it)
        }
    }
    BackHandler(enabled = tab != Tab.Today) { tab = Tab.Today }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (tab == Tab.Today) {
                ExtendedFloatingActionButton(
                    onClick = onAdd,
                    icon = { Icon(Glyph.Plus, null) },
                    text = { Text("Add", fontWeight = FontWeight.SemiBold) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                )
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Today -> Glyph.Today
                                    Tab.Order -> Glyph.Order
                                    Tab.Done -> Glyph.History
                                },
                                null,
                            )
                        },
                        label = { Text(t.label, fontWeight = FontWeight.SemiBold) },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        },
    ) { pad ->
        when (tab) {
            Tab.Today -> TodayScreen(
                vm, state, pad,
                onOpen = { sheetId = it.id },
                onStart = onStart,
                onSettings = onSettings,
            )
            Tab.Order -> OrderScreen(vm, state, pad)
            Tab.Done -> HistoryScreen(vm, state, pad)
        }
    }
    // Looked up by id so the sheet follows refreshes; it closes if the task goes.
    val sheetTask = sheetId?.let { id -> state.tasks.find { it.id == id } }
    if (sheetTask != null) {
        TaskSheet(
            sheetTask,
            onDone = { vm.done(sheetTask) },
            onStart = { onStart(sheetTask) },
            onEdit = { onEdit(sheetTask) },
            onDismiss = { sheetId = null },
        )
    } else if (sheetId != null) {
        LaunchedEffect(Unit) { sheetId = null }
    }
}
