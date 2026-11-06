package me.malm.realmdo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** One line of the order: a task, or a section's divider. */
sealed interface Entry {
    /** The list key: a task's id, or "s" and a section's id. */
    val key: Any

    data class OfTask(val task: Task) : Entry {
        override val key: Any get() = task.id
    }

    data class OfSection(val section: Section) : Entry {
        override val key: Any get() = "s${section.id}"
    }
}

/** Tasks and sections on one line by position; a section goes first at the same place. */
fun entries(tasks: List<Task>, sections: List<Section>): List<Entry> =
    (sections.map { Entry.OfSection(it) to it.position * 2 } + tasks.map { Entry.OfTask(it) to it.position * 2 + 1 })
        .sortedBy { it.second }
        .map { it.first }

/** The tasks and sections of [entries], each with its index as its position. */
fun positioned(entries: List<Entry>): Pair<List<Task>, List<Section>> {
    val tasks = mutableListOf<Task>()
    val sections = mutableListOf<Section>()
    entries.forEachIndexed { i, e ->
        when (e) {
            is Entry.OfTask -> tasks.add(e.task.copy(position = i.toLong()))
            is Entry.OfSection -> sections.add(e.section.copy(position = i.toLong()))
        }
    }
    return tasks to sections
}

/** What the list shows: everything but the tasks of folded sections. [keep] (the one being dragged) always shows. */
fun visible(entries: List<Entry>, folded: Set<Long>, keep: Any? = null): List<Entry> {
    var shut = false
    return entries.filter { e ->
        when (e) {
            is Entry.OfSection -> {
                shut = e.section.id in folded
                true
            }
            is Entry.OfTask -> !shut || e.key == keep
        }
    }
}

/** The tasks under each section, up to the next one, by section id. */
fun sectionTasks(entries: List<Entry>): Map<Long, List<Task>> {
    val out = mutableMapOf<Long, MutableList<Task>>()
    var current: Long? = null
    for (e in entries) {
        when (e) {
            is Entry.OfSection -> current = e.section.id.also { out[it] = mutableListOf() }
            is Entry.OfTask -> current?.let { out.getValue(it).add(e.task) }
        }
    }
    return out
}

/** [list] with [item] moved into gap [gap] of the others: 0 puts it first, the number of others last. */
fun <T> placeAt(list: List<T>, item: T, gap: Int): List<T> {
    val others = list - item
    return others.take(gap) + item + others.drop(gap)
}

/**
 * Every open task, hidden ones included, in the order the user wants them,
 * with named sections between them. Dragging moves a row at once; letting
 * go sends the whole order. A section folds to its header on this phone.
 *
 * Right after a task is created it arrives here lifted ([UiState.placing]),
 * with a HERE gap between the other rows and folds open: tap one, or drag,
 * to place it. It is already saved on top, so leaving keeps it there.
 */
@Composable
fun OrderScreen(vm: TasksViewModel, state: UiState, padding: PaddingValues, onPlaced: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    // A local copy moves under the finger; the server's lists replace it
    // whenever they change and no drag is under way.
    var items by remember { mutableStateOf(entries(state.tasks, state.sections)) }
    var dragKey by remember { mutableStateOf<Any?>(null) }
    LaunchedEffect(state.tasks, state.sections) { if (dragKey == null) items = entries(state.tasks, state.sections) }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = items.indexOfFirst { it.key == from.key }
        val b = items.indexOfFirst { it.key == to.key }
        if (a >= 0 && b >= 0) items = items.toMutableList().apply { add(b, removeAt(a)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    val drop = {
        dragKey = null
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        if (items.map { it.key } != entries(state.tasks, state.sections).map { it.key }) vm.order(items)
    }
    val lift = { key: Any ->
        dragKey = key
        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }
    // A new section lands at the bottom: bring it into view.
    var sectionCount by remember { mutableStateOf(state.sections.size) }
    LaunchedEffect(state.sections.size) {
        if (state.sections.size > sectionCount) listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        sectionCount = state.sections.size
    }

    val placing = state.placing?.let { id -> items.firstOrNull { it.key == id } }
    val placingTitle = (placing as? Entry.OfTask)?.task?.title.orEmpty()
    val newAt = placing?.let(items::indexOf) ?: -1
    var moved by remember(placing?.key) { mutableStateOf(false) }
    // 0 to 1 as the new card lifts and the rest dim, once on arrival.
    val arrive = remember(placing?.key) { Animatable(0f) }
    LaunchedEffect(placing?.key) { if (placing != null) arrive.animateTo(1f, tween(350)) }
    val dim by animateFloatAsState(if (placing != null) 0.72f else 1f, tween(250), label = "dim")
    val place = { gap: Int ->
        if (placing != null) {
            items = placeAt(items, placing, gap)
            moved = true
            haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
            vm.order(items)
        }
    }
    // Placing opens every fold, so each gap is in reach.
    val shown = if (placing != null) items else visible(items, state.folded, dragKey)
    val numbers = items.filterIsInstance<Entry.OfTask>().withIndex().associate { (i, e) -> e.key to i + 1 }
    val inSection = sectionTasks(items)
    var renaming by remember { mutableStateOf<Section?>(null) }
    var removing by remember { mutableStateOf<Section?>(null) }

    Box(Modifier.fillMaxSize().padding(padding)) {
        LazyColumn(
            Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = if (placing != null) 120.dp else 104.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item(key = "head") {
                ScreenTitle(
                    "Order",
                    "Drag to decide what comes first. Hidden tasks keep their place and come back to it.",
                    Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 12.dp),
                )
            }
            if (items.isEmpty() && !state.loading) {
                item(key = "empty") {
                    Text(
                        "No open tasks. Add one from Today.",
                        Modifier.padding(4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            shown.forEachIndexed { i, e ->
                // The gap before this row, unless the new task is right here already.
                if (placing != null && e != placing && i != newAt + 1) {
                    val gap = if (i > newAt) i - 1 else i
                    val before = when (e) {
                        is Entry.OfTask -> e.task.title
                        is Entry.OfSection -> e.section.name
                    }
                    item(key = "gap-$gap") { HereGap("Put $placingTitle before $before", Modifier.animateItem()) { place(gap) } }
                }
                when (e) {
                    is Entry.OfSection -> item(key = e.key) {
                        ReorderableItem(reorder, key = e.key) { lifted ->
                            val s = e.section
                            SectionHeader(
                                s,
                                tasks = inSection[s.id].orEmpty(),
                                open = placing != null || s.id !in state.folded,
                                lifted = lifted,
                                onToggle = { if (placing == null) vm.toggleFold(s.id) },
                                onRename = { renaming = s },
                                onRemove = { removing = s },
                                modifier = Modifier
                                    .then(if (placing != null) Modifier.alpha(dim) else Modifier)
                                    .longPressDraggableHandle(onDragStarted = { lift(e.key) }, onDragStopped = { drop() }),
                            )
                        }
                    }
                    is Entry.OfTask -> item(key = e.key) {
                        ReorderableItem(reorder, key = e.key) { lifted ->
                            TaskLine(
                                e.task,
                                number = numbers[e.key] ?: 0,
                                isNew = e == placing,
                                lifted = lifted,
                                arrive = arrive.value,
                                dragHandle = Modifier.draggableHandle(
                                    onDragStarted = {
                                        if (e == placing) moved = true
                                        lift(e.key)
                                    },
                                    onDragStopped = { drop() },
                                ),
                                modifier = Modifier
                                    .then(if (placing != null && e != placing) Modifier.alpha(dim) else Modifier)
                                    .longPressDraggableHandle(
                                        onDragStarted = {
                                            if (e == placing) moved = true
                                            lift(e.key)
                                        },
                                        onDragStopped = { drop() },
                                    ),
                            )
                        }
                    }
                }
            }
            if (placing != null && newAt != items.lastIndex) {
                item(key = "gap-end") {
                    HereGap("Put $placingTitle last", Modifier.animateItem()) { place(items.size - 1) }
                }
            }
        }
        if (placing != null) {
            val after = items.getOrNull(newAt - 1)
            val afterName = when (after) {
                is Entry.OfTask -> after.task.title
                is Entry.OfSection -> "the ${after.section.name} heading"
                null -> null
            }
            PlaceBar(
                title = if (!moved) "Where does $placingTitle go?" else if (afterName == null) "$placingTitle stays on top." else "$placingTitle goes after $afterName.",
                line = if (moved) "Tap another gap, or Done." else "Tap a gap or drag it. Leave it and it stays on top.",
                onDone = onPlaced,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
    renaming?.let { s ->
        SectionNameDialog("Rename section", s.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            vm.renameSection(s.id, name)
        }
    }
    removing?.let { s ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove “${s.name}”?") },
            text = { Text("Only the heading goes. Its tasks stay where they are.") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    vm.removeSection(s.id)
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

/** One task in the order. Hidden ones are a slim single line. */
@Composable
private fun TaskLine(
    t: Task,
    number: Int,
    isNew: Boolean,
    lifted: Boolean,
    arrive: Float,
    dragHandle: Modifier,
    modifier: Modifier,
) {
    val lift by animateDpAsState(if (lifted) 10.dp else 0.dp, label = "lift")
    val hidden = hiddenLabel(t)
    val slim = hidden != null && !isNew
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = LocalExtra.current.card,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = if (isNew) maxOf(lift, 10.dp * arrive) else lift,
        border = if (isNew) BorderStroke(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = arrive)) else null,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.heightIn(min = if (slim) 48.dp else 60.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$number",
                Modifier.width(28.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (number == 1) MaterialTheme.colorScheme.primary else muted,
            )
            Column(Modifier.weight(1f).padding(vertical = 8.dp).then(if (hidden != null) Modifier.alpha(0.62f) else Modifier)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        t.title,
                        Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isNew) NewTag()
                    if (slim) {
                        Spacer(Modifier.weight(1f))
                        Icon(Glyph.Hidden, null, Modifier.width(14.dp), tint = muted)
                        Text(hidden!!, style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                }
                if (!slim) {
                    (hidden ?: detail(t))?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
                }
            }
            IconButton(onClick = {}, modifier = dragHandle) {
                Icon(Glyph.Grip, "Drag ${t.title}", tint = if (lifted) MaterialTheme.colorScheme.primary else muted)
            }
        }
    }
}

/**
 * A section's divider: its name in small capitals, how many tasks, a thin
 * rule, and when folded how many of them show. Tap to fold; ⋯ to rename or
 * remove; long-press to drag.
 */
@Composable
private fun SectionHeader(
    section: Section,
    tasks: List<Task>,
    open: Boolean,
    lifted: Boolean,
    onToggle: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val showing = tasks.count { it.urgency >= 0 }
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = if (lifted) LocalExtra.current.card else Color.Transparent,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = if (lifted) 10.dp else 0.dp,
        modifier = modifier.fillMaxWidth().padding(top = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 44.dp)
                    .clickable(onClickLabel = if (open) "Fold ${section.name}" else "Unfold ${section.name}", onClick = onToggle)
                    .padding(start = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    section.name.uppercase(),
                    Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.4.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text("${tasks.size}", style = MaterialTheme.typography.labelLarge, color = muted)
                HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                if (!open && tasks.isNotEmpty()) {
                    Text(
                        if (showing == 0) "all hidden" else "$showing showing",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
                Icon(if (open) Glyph.ChevronUp else Glyph.ChevronDown, null, tint = muted)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Glyph.More, "Rename or remove ${section.name}", tint = muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Glyph.Edit, null) },
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Remove") },
                        leadingIcon = { Icon(Glyph.Trash, null) },
                        onClick = {
                            menu = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

/** Asks for a section's name: new, or a rename. */
@Composable
fun SectionNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val ok = name.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(MAX_SECTION_NAME) },
                modifier = Modifier.focusRequester(focus),
                singleLine = true,
                placeholder = { Text("Morning, Killy, Home…") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }, enabled = ok) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A dashed line with HERE on it: tap to put the new task in this gap. */
@Composable
private fun HereGap(label: String, modifier: Modifier, onClick: () -> Unit) {
    val line = MaterialTheme.colorScheme.inversePrimary
    Row(
        modifier.fillMaxWidth().height(28.dp).clickable(onClickLabel = label, onClick = onClick).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DashedLine(line, Modifier.weight(1f))
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                "HERE",
                Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        DashedLine(line, Modifier.weight(1f))
    }
}

@Composable
private fun DashedLine(color: Color, modifier: Modifier) {
    Canvas(modifier.height(2.dp)) {
        drawLine(
            color,
            Offset(0f, size.height / 2),
            Offset(size.width, size.height / 2),
            strokeWidth = size.height,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f)),
        )
    }
}

@Composable
private fun NewTag() {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primary) {
        Text(
            "NEW",
            Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onPrimary,
        )
    }
}

/** Where the new task stands, and Done. */
@Composable
private fun PlaceBar(title: String, line: String, onDone: () -> Unit, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.75f))
            }
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.inversePrimary,
                    contentColor = MaterialTheme.colorScheme.inverseSurface,
                ),
            ) { Text("Done") }
        }
    }
}
