package me.malm.realmdo

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The mark next to the app name. */
@Composable
fun AppTitle() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.mark), contentDescription = null, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(10.dp))
        Text(
            "realmdo",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A small caps label over a group: "THEN", "EVERY". */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A big screen title with an optional line under it. */
@Composable
fun ScreenTitle(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        subtitle?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** How pressing a task's second line is: due, on now, or neither. */
enum class Tone { Plain, Now, Hot }

fun tone(t: Task): Tone = when {
    t.kind == "slot" && t.today?.any { it.second == "overdue" } == true -> Tone.Hot
    t.kind == "slot" && t.today?.any { it.second == "current" } == true -> Tone.Now
    t.important -> Tone.Hot
    else -> Tone.Plain
}

@Composable
fun toneColor(tone: Tone): Color = when (tone) {
    Tone.Plain -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.Now -> MaterialTheme.colorScheme.primary
    Tone.Hot -> MaterialTheme.colorScheme.tertiary
}

/** A task's second line: the kind's glyph, then its standing. */
@Composable
fun StandingLine(task: Task, text: String? = detail(task), tone: Tone = tone(task)) {
    val label = text ?: if (task.notes != null) "has notes" else return
    val color = toneColor(tone)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(if (text == null) Glyph.Note else Glyph.kind(task.kind), null, Modifier.size(16.dp), tint = color)
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            fontWeight = if (tone == Tone.Plain) null else FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The round check in front of a task: a wide target, a small ring. */
@Composable
fun CheckButton(title: String, hot: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = "Done: $title" }) {
        Surface(
            modifier = Modifier.size(22.dp),
            shape = CircleShape,
            color = Color.Transparent,
            border = BorderStroke(
                2.dp,
                if (hot) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {}
    }
}

/** The small round play button on a task with steps. */
@Composable
fun PlayButton(onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) { Icon(Glyph.Play, "Start routine", Modifier.size(18.dp)) }
}

/** Rounded only where a row starts or ends its group. */
fun groupShape(index: Int, count: Int, radius: Int = 22): Shape {
    val top = if (index == 0) radius.dp else 0.dp
    val bottom = if (index == count - 1) radius.dp else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** A row inside a grouped card, with a hairline above all but the first. */
@Composable
fun GroupRow(
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = groupShape(index, count),
        color = LocalExtra.current.card,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column {
            if (index > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 4.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content,
            )
        }
    }
}

private val URL = Regex("""https?://\S+""")

/** Text with bare http(s) URLs made tappable. */
@Composable
fun linkedText(text: String): AnnotatedString {
    val style = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString {
        var at = 0
        for (m in URL.findAll(text)) {
            append(text.substring(at, m.range.first))
            withLink(LinkAnnotation.Url(m.value, style)) { append(m.value) }
            at = m.range.last + 1
        }
        append(text.substring(at))
    }
}
