package me.malm.realmdo

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * Below the visible list, in two folds of what can be done early. Later
 * today: a slot task waiting for its next slot, by start time. Coming up:
 * everything back on another day, soonest first. A slot task whose slots are
 * all checked is finished for the day and stays out of both.
 */
fun laterToday(tasks: List<Task>): List<Task> = tasks
    .filter { it.urgency < 0 && it.backOn == null && it.kind == "slot" && it.pendingSlot != null }
    .sortedBy { SLOTS.indexOf(it.pendingSlot) }

fun comingUp(tasks: List<Task>): List<Task> = tasks
    .filter { it.urgency < 0 && it.backOn != null }
    .sortedBy { it.backOn }

/**
 * The day at a glance: the top task large (it is the one pinned to the
 * notification), the rest under it in the user's order, and what is not due
 * yet folded away under "Later".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    vm: TasksViewModel,
    state: UiState,
    padding: PaddingValues,
    onOpen: (Task) -> Unit,
    onStart: (Task) -> Unit,
    onSettings: () -> Unit,
) {
    var todayOpen by rememberSaveable { mutableStateOf(true) }
    var upOpen by rememberSaveable { mutableStateOf(false) }
    val done = rememberDone(vm)
    val visible = state.tasks.filter { it.urgency >= 0 }
    val later = laterToday(state.tasks)
    val coming = comingUp(state.tasks)
    val rest = visible.drop(1)
    PullToRefreshBox(
        isRefreshing = state.loading && state.tasks.isNotEmpty(),
        onRefresh = vm::refresh,
        modifier = Modifier.fillMaxSize().padding(padding),
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 104.dp)) {
            item(key = "head") {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppTitle()
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = onSettings) { Icon(Glyph.Sliders, "Settings") }
                    }
                    val count = visible.size
                    ScreenTitle(
                        "Today",
                        "${longDay(LocalDate.now())} · ${if (count == 0) "all clear" else "$count to do"}",
                        Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp),
                    )
                }
            }
            state.error?.let { err ->
                item(key = "error") {
                    Text(
                        err,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                }
            }
            state.background?.let {
                if (state.liveUpdate && it.held) item(key = "held") { HeldBanner(it, onSettings) }
            }
            val top = visible.firstOrNull()
            if (top == null) {
                if (!state.loading) item(key = "empty") { AllClear(later.size + coming.size) { todayOpen = true; upOpen = true } }
            } else {
                item(key = "top-${top.id}") {
                    UpNext(top, onOpen = { onOpen(top) }, onDone = { done(top) }, onStart = { onStart(top) })
                }
            }
            if (rest.isNotEmpty()) {
                item(key = "then") { SectionLabel("Then", Modifier.padding(start = 20.dp, top = 22.dp, bottom = 8.dp)) }
                itemsIndexed(rest, key = { _, t -> t.id }) { i, t ->
                    TaskRow(t, i, rest.size, onOpen = { onOpen(t) }, onDone = { done(t) }, onStart = { onStart(t) })
                }
            }
            if (later.isNotEmpty()) {
                item(key = "later") {
                    Fold(
                        "Later today · ${later.size}",
                        state.slotTimes[later.first().pendingSlot]?.let { "from $it" },
                        todayOpen,
                    ) { todayOpen = !todayOpen }
                }
                // Out of focus, but at hand to tick off early.
                if (todayOpen) {
                    itemsIndexed(later, key = { _, t -> t.id }) { i, t ->
                        TaskRow(
                            t, i, later.size, Modifier.alpha(0.62f),
                            lead = state.slotTimes[t.pendingSlot],
                            standing = t.pendingSlot?.let(::slotLabel),
                            onOpen = { onOpen(t) }, onDone = { done(t) }, onStart = { onStart(t) },
                        )
                    }
                }
            }
            if (coming.isNotEmpty()) {
                item(key = "coming") {
                    Fold("Coming up · ${coming.size}", "next ${backTag(coming.first().backOn!!)}", upOpen) { upOpen = !upOpen }
                }
                if (upOpen) {
                    itemsIndexed(coming, key = { _, t -> t.id }) { i, t ->
                        TaskRow(
                            t, i, coming.size, Modifier.alpha(0.62f),
                            lead = backTag(t.backOn!!),
                            standing = comingLine(t),
                            onOpen = { onOpen(t) }, onDone = { done(t) }, onStart = { onStart(t) },
                        )
                    }
                }
            }
        }
    }
}

/** A fold header: what is in it, a hint at the soonest, and the chevron. */
@Composable
private fun Fold(label: String, hint: String?, open: Boolean, onToggle: () -> Unit) {
    TextButton(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        hint?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
        }
        Icon(
            if (open) Glyph.ChevronUp else Glyph.ChevronDown,
            if (open) "Hide" else "Show",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The second line of a Coming up row: its days, its interval, or its due date. */
private fun comingLine(t: Task): String? = when (t.kind) {
    "slot" -> listOfNotNull(weekdaysLabel(t.weekdays), t.slots?.joinToString(" · ") { slotLabel(it) }).joinToString(" · ")
    "interval" -> "every ${t.afterDays ?: 0} d"
    else -> t.dueOn?.let { "due ${shortDate(it)}" }
}

/** The top task, large: what the notification shows, with Done at hand. */
@Composable
private fun UpNext(task: Task, onOpen: () -> Unit, onDone: () -> Unit, onStart: () -> Unit) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 16.dp)) {
            Text("UP NEXT", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                task.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            StandingLine(task)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onDone) {
                    Icon(Glyph.Check, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Done")
                }
                if (task.steps != null) {
                    OutlinedButton(onClick = onStart) {
                        Icon(Glyph.Play, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Start · ${clock(task.steps.sumOf { it.seconds })}")
                    }
                }
            }
        }
    }
}

/** One task in the list: check to finish, tap for the sheet, play to run its steps. */
@Composable
fun TaskRow(
    task: Task,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    standing: String? = detail(task),
    /** A time or day before the title, for the Later folds. */
    lead: String? = null,
    onOpen: () -> Unit,
    onDone: () -> Unit,
    onStart: () -> Unit,
) {
    val tone = tone(task)
    GroupRow(index, count, modifier.clickable(onClick = onOpen)) {
        CheckButton(task.title, hot = tone == Tone.Hot, onClick = onDone)
        lead?.let {
            Text(
                it,
                Modifier.width(52.dp),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (tone == Tone.Plain) null else FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            StandingLine(task, standing, tone)
        }
        if (task.steps != null) PlayButton(onStart)
    }
}

@Composable
private fun AllClear(later: Int, onShowLater: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(26.dp),
            colors = CardDefaults.cardColors(containerColor = LocalExtra.current.card, contentColor = MaterialTheme.colorScheme.onSurface),
        ) {
            Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Glyph.Check, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                Text("Nothing due", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (later > 0) "$later for later." else "Add a task.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (later > 0) TextButton(onClick = onShowLater) { Text("Show later") }
            }
        }
    }
}
