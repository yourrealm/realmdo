package me.malm.realmdo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Writing a task, new or existing: what, which kind (fixed once it exists),
 * the kind's own settings, then notes and the routine. Save is the only way
 * anything reaches the server; closing with changes asks first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    draft: Draft,
    editor: EditorViewModel,
    tasks: TasksViewModel,
    slotTimes: Map<String, String>,
) {
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmClose by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val tryClose = { if (editor.dirty) confirmClose = true else editor.close() }
    val save = {
        val problem = draft.problem
        if (problem != null) {
            error = problem
        } else {
            saving = true
            error = null
            tasks.save(draft) { err ->
                saving = false
                if (err == null) editor.close() else error = err
            }
        }
    }
    BackHandler(onBack = tryClose)
    androidx.compose.material3.Scaffold(
        containerColor = LocalExtra.current.card,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = LocalExtra.current.card),
                navigationIcon = { IconButton(onClick = tryClose) { Icon(Glyph.Close, "Close") } },
                title = { Text(if (draft.isNew) "New task" else "Edit task") },
                actions = {
                    Button(
                        onClick = save,
                        enabled = !saving,
                        modifier = Modifier.padding(end = 8.dp),
                    ) { Text(if (saving) "Saving…" else "Save") }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            TitleField(draft.title, autofocus = draft.isNew) { t -> editor.update { it.copy(title = t) } }
            if (draft.isNew) {
                KindPicker(draft.kind) { k -> editor.update { it.copy(kind = k) } }
            } else {
                KindBadge(draft.kind)
            }
            when (draft.kind) {
                "once" -> DueSection(draft.dueOn) { d -> editor.update { it.copy(dueOn = d) } }
                "interval" -> RepeatSection(draft, editor)
                "slot" -> SlotSection(draft.slots, slotTimes) { s -> editor.update { it.copy(slots = s) } }
            }
            Column {
                NotesSection(draft.notes) { n -> editor.update { it.copy(notes = n) } }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                RoutineRow(draft.steps, onClick = editor::openRoutine)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
            if (draft.isNew) {
                Text(
                    "New tasks go to the top of your order.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                TextButton(
                    onClick = { confirmDelete = true },
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.tertiary,
                    ),
                ) {
                    Icon(Glyph.Trash, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete task")
                }
            }
        }
    }
    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Discard changes?") },
            text = { Text(if (draft.isNew) "This task has not been saved." else "Your edits have not been saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClose = false
                    editor.close()
                }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep editing") } },
        )
    }
    if (confirmDelete && draft.id != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete “${draft.title.trim()}”?") },
            text = { Text("Its history goes with it. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    tasks.delete(draft.id, draft.title.trim())
                    editor.close()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TitleField(title: String, autofocus: Boolean, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    if (autofocus) LaunchedEffect(Unit) { focus.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SectionLabel("What")
        TextField(
            value = title,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth().focusRequester(focus),
            textStyle = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
            placeholder = { Text("What needs doing?", style = MaterialTheme.typography.headlineSmall) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
        )
    }
}

private val KINDS = listOf("once" to "Once", "interval" to "Repeats", "slot" to "Daily")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KindPicker(kind: String, onPick: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        KINDS.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = kind == value,
                onClick = { onPick(value) },
                shape = SegmentedButtonDefaults.itemShape(i, KINDS.size),
                icon = { Icon(Glyph.kind(value), null, Modifier.size(18.dp)) },
            ) { Text(label) }
        }
    }
}

@Composable
private fun KindBadge(kind: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Glyph.kind(kind), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "${KINDS.first { it.first == kind }.second} · the kind stays",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DueSection(dueOn: String?, onChange: (String?) -> Unit) {
    val today = LocalDate.now()
    var picking by rememberSaveable { mutableStateOf(false) }
    val picked = dueOn != null && dueOn != today.toString() && dueOn != today.plusDays(1).toString()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Due")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("No date", dueOn == null) { onChange(null) }
            Chip("Today", dueOn == today.toString()) { onChange(today.toString()) }
            Chip("Tomorrow", dueOn == today.plusDays(1).toString()) { onChange(today.plusDays(1).toString()) }
            Chip(if (picked) shortDate(dueOn!!) else "Pick a day", picked, Glyph.Calendar) { picking = true }
        }
    }
    if (picking) {
        val initial = (dueOn?.let(LocalDate::parse) ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        onChange(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString())
                    }
                    picking = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = icon?.let { { Icon(it, null, Modifier.size(18.dp)) } },
        modifier = Modifier.height(40.dp),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@Composable
private fun RepeatSection(draft: Draft, editor: EditorViewModel) {
    var tuning by rememberSaveable { mutableStateOf(draft.rampDays != null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Every")
        DayStepper(
            label = days(draft.afterDays),
            value = draft.afterDays,
            range = 0..MAX_DAYS,
        ) { n -> editor.update { it.copy(afterDays = n) } }
        if (tuning) {
            SectionLabel("Urgent after", Modifier.padding(top = 6.dp))
            val ramp = draft.rampDays ?: maxOf(draft.afterDays, 1)
            DayStepper(
                label = days(ramp).replace("day", "more day"),
                value = ramp,
                range = 1..MAX_DAYS,
            ) { n -> editor.update { it.copy(rampDays = n) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                repeatHelp(draft.afterDays, draft.rampDays),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (tuning && draft.rampDays != null) {
                TextButton(onClick = { editor.update { it.copy(rampDays = null) } }) { Text("Reset") }
            }
        }
        if (!tuning) {
            Chip("Urgent sooner or later…", false) { tuning = true }
        }
    }
}

/** A number of days with round minus and plus buttons. */
@Composable
private fun DayStepper(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            val colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = LocalExtra.current.card,
                contentColor = MaterialTheme.colorScheme.onSurface,
            )
            FilledIconButton(onClick = { onChange(value - 1) }, enabled = value > range.first, colors = colors) {
                Icon(Glyph.Minus, "Fewer days")
            }
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = { onChange(value + 1) }, enabled = value < range.last, colors = colors) {
                Icon(Glyph.Plus, "More days")
            }
        }
    }
}

@Composable
private fun SlotSection(slots: Set<String>, times: Map<String, String>, onChange: (Set<String>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("When")
        SLOTS.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { slot ->
                    val on = slot in slots
                    Surface(
                        onClick = { onChange(if (on) slots - slot else slots + slot) },
                        modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                        shape = RoundedCornerShape(18.dp),
                        color = if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                        border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Text(slotTitle(slot), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text("from ${times[slot] ?: ""}", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        Text(
            "Each one is its own check, every day.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ColumnScope.NotesSection(notes: String, onChange: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(notes.isNotEmpty()) }
    if (!open) {
        LineRow(Glyph.Note, "Notes", "Add", onClick = { open = true })
        return
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (notes.isEmpty()) focus.requestFocus() }
    Row(Modifier.padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
        Icon(Glyph.Note, null, Modifier.padding(top = 16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        OutlinedTextField(
            value = notes,
            onValueChange = { onChange(it.take(MAX_NOTES)) },
            modifier = Modifier.weight(1f).focusRequester(focus),
            label = { Text("Notes") },
            minLines = 3,
            supportingText = if (notes.length > MAX_NOTES - 500) {
                { Text("${notes.length} / $MAX_NOTES") }
            } else {
                null
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
    }
}

@Composable
private fun RoutineRow(steps: List<Step>, onClick: () -> Unit) {
    val hint = if (steps.isEmpty()) "Add steps" else "${steps.size} steps · ${clock(steps.sumOf { it.seconds })}"
    LineRow(Glyph.Play, "Routine", hint, chevron = true, onClick = onClick)
}

@Composable
private fun LineRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    hint: String,
    chevron: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (chevron) Icon(Glyph.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
