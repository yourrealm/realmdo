package me.malm.realmdo

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Every open task, hidden ones included, in the order the user wants them.
 * Dragging moves a row at once; letting go sends the whole order. Hidden
 * tasks keep their place, so they come back to it when they are due.
 */
@Composable
fun OrderScreen(vm: TasksViewModel, state: UiState, padding: PaddingValues) {
    val haptics = LocalHapticFeedback.current
    // A local copy moves under the finger; the server's list replaces it
    // whenever it changes and no drag is under way.
    var items by remember { mutableStateOf(state.tasks) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(state.tasks) { if (!dragging) items = state.tasks }
    val listState = rememberLazyListState()
    val reorder = rememberReorderableLazyListState(listState) { from, to ->
        val a = items.indexOfFirst { it.id == from.key }
        val b = items.indexOfFirst { it.id == to.key }
        if (a >= 0 && b >= 0) items = items.toMutableList().apply { add(b, removeAt(a)) }
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }
    val drop = {
        dragging = false
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        val ids = items.map { it.id }
        if (ids != state.tasks.map { it.id }) vm.order(ids)
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(padding),
        state = listState,
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
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
        itemsIndexed(items, key = { _, t -> t.id }) { i, t ->
            ReorderableItem(reorder, key = t.id) { lifted ->
                val lift by animateDpAsState(if (lifted) 10.dp else 0.dp, label = "lift")
                val hidden = hiddenLabel(t)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = LocalExtra.current.card,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    shadowElevation = lift,
                    modifier = Modifier.fillMaxWidth().longPressDraggableHandle(
                        onDragStarted = {
                            dragging = true
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        },
                        onDragStopped = { drop() },
                    ),
                ) {
                    Row(
                        Modifier.heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "${i + 1}",
                            Modifier.width(28.dp),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (i == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Column(
                            Modifier.weight(1f).padding(vertical = 8.dp).then(if (hidden != null) Modifier.alpha(0.62f) else Modifier),
                        ) {
                            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (hidden != null) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Icon(Glyph.Hidden, null, Modifier.width(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "hidden · $hidden",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                detail(t)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        IconButton(
                            onClick = {},
                            modifier = Modifier.draggableHandle(
                                onDragStarted = {
                                    dragging = true
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                },
                                onDragStopped = { drop() },
                            ),
                        ) {
                            Icon(
                                Glyph.Grip,
                                "Drag ${t.title}",
                                tint = if (lifted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
