package me.malm.realmdo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A routine running: the step, a ring counting it down, what to read, what
 * comes next and the controls under the thumb. The screen stays on while it
 * is shown.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(run: RoutineState, vm: RoutineViewModel, onDone: (Task) -> Unit) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(onBack = vm::stop)
    val left = run.steps.drop(run.index + 1).sumOf { it.seconds } + ((run.leftMs + 999) / 1000).toInt()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        run.task.title + if (run.preview) " · trying it" else "",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                navigationIcon = { IconButton(onClick = vm::stop) { Icon(Glyph.Close, "Stop") } },
                actions = {
                    if (!run.finished) {
                        Text(
                            "${clock(left)} left",
                            Modifier.padding(end = 16.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(horizontal = 24.dp).padding(bottom = 24.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Progress(run)
            if (run.finished) Finished(run, vm, onDone) else Running(run, vm)
        }
    }
}

/** One bar per step: done, current (wide) and to come. */
@Composable
private fun Progress(run: RoutineState) {
    Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        run.steps.indices.forEach { i ->
            val done = run.finished || i < run.index
            val current = !run.finished && i == run.index
            Surface(
                modifier = Modifier.height(4.dp).width(if (current) 28.dp else if (run.steps.size > 12) 8.dp else 16.dp),
                shape = RoundedCornerShape(2.dp),
                color = if (done || current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            ) {}
        }
    }
}

@Composable
private fun ColumnScope.Running(run: RoutineState, vm: RoutineViewModel) {
    val step = run.step
    Spacer(Modifier.height(24.dp))
    Text(
        "STEP ${run.index + 1} OF ${run.steps.size}",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        step.title,
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 6.dp),
    )
    Spacer(Modifier.height(20.dp))
    val track = MaterialTheme.colorScheme.surfaceVariant
    val bar = MaterialTheme.colorScheme.primary
    val done = 1f - run.leftMs / (step.seconds * 1000f)
    Box(Modifier.size(260.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 14.dp.toPx()
            val inset = w / 2
            val arc = Size(size.width - w, size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(w))
            drawArc(bar, -90f, 360f * (1f - done), false, Offset(inset, inset), arc, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // Rounded up: a step reads 0:01 until its last second is gone.
            Text(
                clock(((run.leftMs + 999) / 1000).toInt()),
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Light,
            )
            Text("of ${clock(step.seconds)}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Column(Modifier.weight(1f).fillMaxWidth().padding(top = 20.dp).verticalScroll(rememberScrollState())) {
        step.details?.let {
            Text(
                linkedText(it),
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
    Text(
        run.next?.let { "Next: ${it.title} · ${clock(it.seconds)}" } ?: "Last step",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 16.dp),
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        FilledTonalIconButton(onClick = vm::previous, Modifier.size(64.dp)) {
            Icon(Glyph.Previous, "Previous", Modifier.size(28.dp))
        }
        FilledIconButton(
            onClick = if (run.running) vm::pause else vm::resume,
            modifier = Modifier.size(96.dp),
            shape = RoundedCornerShape(32.dp),
        ) {
            if (run.running) Icon(Glyph.Pause, "Pause", Modifier.size(40.dp))
            else Icon(Glyph.Play, "Resume", Modifier.size(40.dp))
        }
        FilledTonalIconButton(onClick = vm::next, Modifier.size(64.dp)) {
            Icon(Glyph.Next, "Next", Modifier.size(28.dp))
        }
    }
}

@Composable
private fun ColumnScope.Finished(run: RoutineState, vm: RoutineViewModel, onDone: (Task) -> Unit) {
    Spacer(Modifier.weight(1f))
    Icon(Glyph.Check, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
    Text("All done", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
    Text(
        "${run.steps.size} steps · ${clock(run.steps.sumOf { it.seconds })}",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = vm::previous, Modifier.height(52.dp)) { Text("Back") }
        if (run.preview) {
            Button(onClick = vm::stop, Modifier.weight(1f).height(52.dp)) { Text("Back to editing") }
        } else {
            Button(
                onClick = {
                    onDone(run.task)
                    vm.stop()
                },
                Modifier.weight(1f).height(52.dp),
            ) {
                Icon(Glyph.Check, null)
                Spacer(Modifier.width(8.dp))
                Text("Mark done")
            }
        }
    }
    if (!run.preview) {
        OutlinedButton(onClick = vm::stop, Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp)) { Text("Close") }
    }
}
