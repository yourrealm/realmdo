package me.malm.realmdo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * A task's routine, one card per step. A card opens to its words: the title
 * shown, what is said instead, details to read. Durations are set in a sheet
 * with nudges and presets rather than typed as m:ss. Cards drag by their
 * grip; a left step offers its right side in one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutineEditorScreen(
    title: String,
    steps: List<EditStep>,
    editor: EditorViewModel,
    onTry: (List<Step>) -> Unit,
) {
    var openKey by rememberSaveable { mutableStateOf<Long?>(null) }
    var timingKey by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        editor.updateRoutine { list ->
            val a = list.indexOfFirst { it.key == from.key }
            val b = list.indexOfFirst { it.key == to.key }
            if (a < 0 || b < 0) list else list.toMutableList().apply { add(b, removeAt(a)) }
        }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    val tryClose = { if (editor.routineDirty) confirmClose = true else editor.dropRoutine() }
    val change = { key: Long, f: (Step) -> Step ->
        editor.updateRoutine { list -> list.map { if (it.key == key) it.copy(step = f(it.step)) else it } }
    }
    val insertAfter = { key: Long, step: Step ->
        editor.updateRoutine { list ->
            val i = list.indexOfFirst { it.key == key }
            list.toMutableList().apply { add(i + 1, editor.keyed(step)) }
        }
    }
    val all = steps.map { it.step }
    BackHandler(onBack = tryClose)
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = tryClose) { Icon(Glyph.Back, "Back") } },
                title = {
                    Column {
                        Text("Routine")
                        Text(
                            title.ifBlank { "New task" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = {
                            val problem = stepsProblem(all)
                            if (problem == null) editor.keepRoutine() else error = problem
                        },
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text("Done") }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize().imePadding(),
            state = listState,
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "head") {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                clock(all.sumOf { it.seconds }),
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                when (all.size) {
                                    0 -> "No steps yet"
                                    1 -> "1 step, spoken aloud"
                                    else -> "${all.size} steps, spoken aloud"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        OutlinedButton(onClick = { onTry(all) }, enabled = all.isNotEmpty() && stepsProblem(all) == null) {
                            Icon(Glyph.Play, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Try it")
                        }
                    }
                    error?.let { Text(it, Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.error) }
                    if (all.isEmpty()) {
                        Text(
                            "A routine is a list of timed steps, read aloud one at a time. " +
                                "Add a left or right step to get the other side in one tap.",
                            Modifier.padding(top = 16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            itemsIndexed(steps, key = { _, s -> s.key }) { i, s ->
                ReorderableItem(reorder, key = s.key) { dragging ->
                    val lift by animateDpAsState(if (dragging) 8.dp else 0.dp, label = "lift")
                    StepCard(
                        step = s.step,
                        number = i + 1,
                        open = openKey == s.key,
                        lift = lift,
                        grip = Modifier.draggableHandle(
                            onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                            onDragStopped = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                        ),
                        onToggle = { openKey = if (openKey == s.key) null else s.key },
                        onChange = { f -> change(s.key, f) },
                        onTiming = { timingKey = s.key },
                        onDuplicate = { insertAfter(s.key, s.step) },
                        onOtherSide = otherSide(s.step)?.let { other -> { insertAfter(s.key, other) } },
                        onDelete = {
                            val at = steps.indexOfFirst { it.key == s.key }
                            editor.updateRoutine { list -> list.filter { it.key != s.key } }
                            scope.launch {
                                val r = snackbar.showSnackbar(
                                    "Deleted “${s.step.title.ifBlank { "untitled step" }}”",
                                    actionLabel = "Undo",
                                    duration = SnackbarDuration.Short,
                                )
                                if (r == SnackbarResult.ActionPerformed) {
                                    editor.updateRoutine { list ->
                                        list.toMutableList().apply { add(at.coerceAtMost(size), s) }
                                    }
                                }
                            }
                        },
                    )
                }
            }
            item(key = "add") {
                if (steps.size < MAX_STEPS) {
                    OutlinedButton(
                        onClick = {
                            val fresh = editor.keyed(Step("", null, null, all.lastOrNull()?.seconds ?: 60))
                            editor.updateRoutine { it + fresh }
                            openKey = fresh.key
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(18.dp),
                        border = BorderStroke(2.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Icon(Glyph.Plus, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Add step")
                    }
                } else {
                    Text(
                        "$MAX_STEPS steps is the most a routine holds.",
                        Modifier.padding(8.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    timingKey?.let { key ->
        val index = steps.indexOfFirst { it.key == key }
        if (index < 0) {
            LaunchedEffect(key) { timingKey = null }
        } else {
            DurationSheet(
                step = steps[index].step,
                number = index + 1,
                count = steps.size,
                onApply = { secs -> change(key) { it.copy(seconds = secs) } },
                onApplyAll = { secs -> editor.updateRoutine { list -> list.map { it.copy(step = it.step.copy(seconds = secs)) } } },
                onDismiss = { timingKey = null },
            )
        }
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Discard routine changes?") },
            text = { Text("The steps go back to how they were.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    editor.dropRoutine()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep editing") } },
        )
    }
}

@Composable
private fun StepCard(
    step: Step,
    number: Int,
    open: Boolean,
    lift: androidx.compose.ui.unit.Dp,
    grip: Modifier,
    onToggle: () -> Unit,
    onChange: ((Step) -> Step) -> Unit,
    onTiming: () -> Unit,
    onDuplicate: () -> Unit,
    onOtherSide: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = LocalExtra.current.card,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = lift,
        border = if (open) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().animateContentSize(),
    ) {
        Column {
            Row(Modifier.heightIn(min = 60.dp).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {}, modifier = grip) {
                    Icon(Glyph.Grip, "Drag to reorder", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "$number",
                    Modifier.width(24.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier.weight(1f).heightIn(min = 48.dp).clickable(onClick = onToggle),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        step.title.ifBlank { "Untitled step" },
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (step.title.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!open && step.speech != null) {
                        Icon(Glyph.Speaker, "Says something else", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!open && step.details != null) {
                        Icon(Glyph.Note, "Has details", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                FilledTonalButton(
                    onClick = onTiming,
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.height(36.dp).widthIn(min = 64.dp),
                ) { Text(clock(step.seconds), fontWeight = FontWeight.SemiBold) }
            }
            if (open) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { if (step.title.isBlank()) focus.requestFocus() }
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = step.title,
                        onValueChange = { t -> onChange { it.copy(title = t.take(MAX_STEP_TITLE)) } },
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        label = { Text("Title") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    OutlinedTextField(
                        value = step.speech.orEmpty(),
                        onValueChange = { t -> onChange { it.copy(speech = t.take(MAX_STEP_SPEECH).ifEmpty { null }) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Say instead") },
                        placeholder = { Text("Says the title") },
                        leadingIcon = { Icon(Glyph.Speaker, null) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    OutlinedTextField(
                        value = step.details.orEmpty(),
                        onValueChange = { t -> onChange { it.copy(details = t.take(MAX_STEP_DETAILS).ifEmpty { null }) } },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Details") },
                        supportingText = { Text("Shown while it runs, never spoken") },
                        minLines = 2,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDuplicate) {
                            Icon(Glyph.Copy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Duplicate")
                        }
                        if (onOtherSide != null) {
                            TextButton(onClick = onOtherSide) {
                                Icon(Glyph.Swap, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Add ${otherSideName(step)} side")
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onDelete) {
                            Icon(Glyph.Trash, "Delete step", tint = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
            }
        }
    }
}

/** Nudges, presets and exact minutes and seconds for one step. */
private val PRESETS = listOf(15, 30, 45, 60, 90, 120, 180, 300)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DurationSheet(
    step: Step,
    number: Int,
    count: Int,
    onApply: (Int) -> Unit,
    onApplyAll: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var minutes by remember { mutableStateOf((step.seconds / 60).toString()) }
    var seconds by remember { mutableStateOf((step.seconds % 60).toString().padStart(2, '0')) }
    val total = ((minutes.toIntOrNull() ?: 0) * 60 + (seconds.toIntOrNull() ?: 0)).coerceIn(1, MAX_STEP_SECONDS)
    val set = { s: Int ->
        val v = s.coerceIn(1, MAX_STEP_SECONDS)
        minutes = (v / 60).toString()
        seconds = (v % 60).toString().padStart(2, '0')
    }
    val finish = { apply: (Int) -> Unit ->
        apply(total)
        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LocalExtra.current.card,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    step.title.ifBlank { "Untitled step" },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "step $number of $count",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Nudge("−15", "15 seconds less") { set(total - 15) }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    ClockField(minutes, TextAlign.End) { minutes = it.filter(Char::isDigit).take(3) }
                    Text(":", style = MaterialTheme.typography.displayMedium)
                    ClockField(seconds, TextAlign.Start) { seconds = it.filter(Char::isDigit).take(2) }
                }
                Nudge("+15", "15 seconds more") { set(total + 15) }
            }
            PRESETS.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { p ->
                        val on = p == total
                        Surface(
                            onClick = { set(p) },
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = if (on) MaterialTheme.colorScheme.primaryContainer else LocalExtra.current.card,
                            contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(clock(p), fontWeight = if (on) FontWeight.Bold else null)
                            }
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (count > 1) {
                    OutlinedButton(onClick = { finish(onApplyAll) }, Modifier.weight(1f).height(48.dp)) {
                        Text("Use for all $count")
                    }
                }
                Button(onClick = { finish(onApply) }, Modifier.weight(1f).height(48.dp)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun Nudge(label: String, description: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(64.dp).semantics { contentDescription = description },
    ) { Text(label, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun ClockField(value: String, align: TextAlign, onChange: (String) -> Unit) {
    val style = MaterialTheme.typography.displayMedium.copy(
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = align,
        fontWeight = FontWeight.Light,
    )
    BasicTextField(
        value = value,
        onValueChange = onChange,
        textStyle = style,
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(92.dp),
    )
}
