package me.malm.realmdo

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * What was done lately, grouped by day: the latest check of every recurring
 * task and the recent one-offs. Undo removes that completion; a one-off comes
 * back to the list, a recurring task falls back to its previous check.
 */
@Composable
fun HistoryScreen(vm: TasksViewModel, state: UiState, padding: PaddingValues) {
    LaunchedEffect(Unit) { vm.loadHistory() }
    val history = state.history
    val zone = ZoneId.systemDefault()
    val groups = history.orEmpty().groupBy { c ->
        c.day?.let(LocalDate::parse) ?: Instant.ofEpochSecond(c.completedAt).atZone(zone).toLocalDate()
    }
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "head") {
            ScreenTitle(
                "Done",
                "Tap undo to put one back.",
                Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
            )
        }
        if (history != null && history.isEmpty()) {
            item(key = "empty") {
                Text(
                    "Nothing done yet. It shows up here as you go.",
                    Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        groups.forEach { (day, rows) ->
            item(key = "day-$day") {
                SectionLabel(dayHeading(day), Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))
            }
            itemsIndexed(rows, key = { _, c -> c.id }) { i, c ->
                GroupRow(i, rows.size) {
                    Box(Modifier.padding(start = 8.dp, end = 12.dp)) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Glyph.kind(c.kind), null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                        Text(c.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOfNotNull(c.slot?.let(::slotLabel), timeOfDay(c.completedAt, zone)).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { vm.restore(c) }) {
                        Icon(Glyph.Undo, "Undo ${c.title}", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}
