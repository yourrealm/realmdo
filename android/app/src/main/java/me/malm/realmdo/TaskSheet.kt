package me.malm.realmdo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** How many steps the sheet lists before "N more steps". */
private const val STEPS_SHOWN = 5

/**
 * A task over the list: its standing, notes and routine, with Start, Edit and
 * Done. Swiping down or tapping outside closes it, so no button is a big
 * target for closing; Done sits beside Edit, never where a thumb lands to
 * dismiss.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskSheet(task: Task, onDone: () -> Unit, onStart: () -> Unit, onEdit: () -> Unit, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val close = { then: () -> Unit ->
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            onDismiss()
            then()
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = LocalExtra.current.card,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(task.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                StandingLine(task, hiddenLabel(task) ?: detail(task))
            }
            task.notes?.let { Text(linkedText(it), style = MaterialTheme.typography.bodyLarge) }
            task.steps?.let { steps -> StepsPreview(steps) }
            Spacer(Modifier.height(4.dp))
            task.steps?.let { steps ->
                Button(onClick = { close(onStart) }, Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Glyph.Play, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start routine · ${clock(steps.sumOf { it.seconds })}")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { close(onEdit) }, Modifier.weight(1f).height(48.dp)) {
                    Icon(Glyph.Edit, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Edit")
                }
                OutlinedButton(onClick = { close(onDone) }, Modifier.weight(1f).height(48.dp)) {
                    Icon(Glyph.Check, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun StepsPreview(steps: List<Step>) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
            steps.take(STEPS_SHOWN).forEachIndexed { i, step ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(
                        "${i + 1}",
                        Modifier.width(24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(step.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        clock(step.seconds),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                    )
                }
            }
            if (steps.size > STEPS_SHOWN) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val more = steps.size - STEPS_SHOWN
                Text(
                    if (more == 1) "1 more step" else "$more more steps",
                    Modifier.padding(start = 24.dp, top = 10.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
