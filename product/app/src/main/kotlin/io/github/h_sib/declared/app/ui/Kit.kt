@file:OptIn(ExperimentalLayoutApi::class)

package io.github.h_sib.declared.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.h_sib.declared.core.home.AreaStatus
import io.github.h_sib.declared.core.model.Attention

/**
 * The parts every screen is built from. A status is always a shape, a word and a color together, so it reads without color: a diamond is a risk,
 * a triangle is a thing to look at, a filled dot is a note, a check is "nothing found", a dashed ring is "not read", a struck ring is "not supported".
 */
enum class Status { RISK, LOOK, NOTE, CLEAR, UNKNOWN, UNSUPPORTED }

fun Attention.toStatus(): Status = when (this) {
    Attention.LOOK_FIRST -> Status.RISK
    Attention.LOOK -> Status.LOOK
    Attention.NOTE -> Status.NOTE
}

fun AreaStatus.toStatus(): Status = when (this) {
    AreaStatus.RISK -> Status.RISK
    AreaStatus.LOOK -> Status.LOOK
    AreaStatus.NOTE -> Status.NOTE
    AreaStatus.CLEAR -> Status.CLEAR
    AreaStatus.UNKNOWN -> Status.UNKNOWN
}

val Status.words: String
    get() = when (this) {
        Status.RISK -> "Look first"
        Status.LOOK -> "Look"
        Status.NOTE -> "Note"
        Status.CLEAR -> "Nothing found"
        Status.UNKNOWN -> "Not read"
        Status.UNSUPPORTED -> "Not supported"
    }

@Composable
fun Status.color(): Color {
    val c = LocalStatusColors.current
    return when (this) {
        Status.RISK -> c.risk
        Status.LOOK -> c.look
        Status.NOTE -> c.note
        Status.CLEAR -> c.ok
        Status.UNKNOWN, Status.UNSUPPORTED -> c.unknown
    }
}

@Composable
fun StatusMarker(status: Status, modifier: Modifier = Modifier) {
    val color = status.color()
    Canvas(modifier.clearAndSetSemantics { }) {
        val s = size.minDimension
        val w = s * 0.14f
        when (status) {
            Status.RISK -> drawPath(Path().apply { moveTo(s / 2, 0f); lineTo(s, s / 2); lineTo(s / 2, s); lineTo(0f, s / 2); close() }, color)
            Status.LOOK -> drawPath(Path().apply { moveTo(s / 2, 0f); lineTo(s, s); lineTo(0f, s); close() }, color)
            Status.NOTE -> drawCircle(color, radius = s / 2)
            Status.CLEAR -> drawPath(
                Path().apply { moveTo(s * 0.08f, s * 0.55f); lineTo(s * 0.4f, s * 0.88f); lineTo(s * 0.95f, s * 0.15f) },
                color, style = Stroke(width = w * 1.7f, cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            Status.UNKNOWN -> drawCircle(color, radius = s / 2 - w / 2, style = Stroke(width = w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(s * 0.22f, s * 0.18f))))
            Status.UNSUPPORTED -> {
                drawCircle(color, radius = s / 2 - w / 2, style = Stroke(width = w))
                drawLine(color, Offset(s * 0.2f, s * 0.8f), Offset(s * 0.8f, s * 0.2f), strokeWidth = w)
            }
        }
    }
}

@Composable
fun StatusPill(status: Status, text: String, modifier: Modifier = Modifier) {
    val c = status.color()
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier.clip(shape).background(c.copy(alpha = 0.14f)).border(1.dp, c.copy(alpha = 0.5f), shape).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusMarker(status, Modifier.size(10.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = c)
    }
}

/** A plain label with no status, for facts such as how many checks ran. */
@Composable
fun InfoChip(text: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Text(
        text,
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 9.dp, vertical = 5.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Set by a debug-build lab flag so a script can read every section; false for people, always. */
val LocalExpandAll = compositionLocalOf { false }

/** True inside a [Panel]: an Expandable there draws its line above itself, so the last one does not double the panel's own border. */
val LocalInPanel = compositionLocalOf { false }

@Composable
fun Panel(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val base = modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
    CompositionLocalProvider(LocalInPanel provides true) {
        Column(if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base, content = content)
    }
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = MaterialTheme.typography.labelMedium.copy(fontFamily = DeclaredMono, letterSpacing = 0.6.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun Chevron(modifier: Modifier = Modifier, up: Boolean = false, down: Boolean = false, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Canvas(modifier.size(16.dp).clearAndSetSemantics { }) {
        val s = size.minDimension
        val p = Path().apply {
            when {
                up -> { moveTo(s * 0.18f, s * 0.65f); lineTo(s * 0.5f, s * 0.33f); lineTo(s * 0.82f, s * 0.65f) }
                down -> { moveTo(s * 0.18f, s * 0.35f); lineTo(s * 0.5f, s * 0.67f); lineTo(s * 0.82f, s * 0.35f) }
                else -> { moveTo(s * 0.35f, s * 0.18f); lineTo(s * 0.67f, s * 0.5f); lineTo(s * 0.35f, s * 0.82f) }
            }
        }
        drawPath(p, color, style = Stroke(width = 1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Layer two: a heading that opens to the longer explanation. Closed by default, so the screen shows what matters and the rest is one tap away. */
@Composable
fun Expandable(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    startOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val expandAll = LocalExpandAll.current
    var open by rememberSaveable(title) { mutableStateOf(startOpen || expandAll) }
    val inPanel = LocalInPanel.current
    Column(modifier.fillMaxWidth().animateContentSize()) {
        if (inPanel) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClickLabel = if (open) "Collapse" else "Expand", role = Role.Button) { open = !open }
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (summary != null && !open) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Chevron(up = open, down = !open)
        }
        if (open) Column(Modifier.padding(bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
        if (!inPanel) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** A tappable row: a marker or nothing at the start, a title and one short line, a chevron. */
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    status: Status? = null,
    onClick: (() -> Unit)? = null,
) {
    val base = modifier.fillMaxWidth()
    Column {
        Row(
            (if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base).padding(vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (status != null) StatusMarker(status, Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (onClick != null) Chevron()
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
fun BackArrow(color: Color = MaterialTheme.colorScheme.onSurface, description: String? = null) {
    // The words sit on the icon itself, inside the button, so the button's own node carries them.
    Canvas(Modifier.size(22.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier.clearAndSetSemantics { })) {
        val s = size.minDimension
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawPath(Path().apply { moveTo(s * 0.9f, s * 0.5f); lineTo(s * 0.12f, s * 0.5f) }, color, style = stroke)
        drawPath(Path().apply { moveTo(s * 0.45f, s * 0.15f); lineTo(s * 0.1f, s * 0.5f); lineTo(s * 0.45f, s * 0.85f) }, color, style = stroke)
    }
}

/** The five icons of the bottom bar, drawn as lines so they match the rest of the interface. */
enum class Tab(val label: String) { Home("Home"), Check("Check"), Paths("Paths"), History("History"), More("More") }

@Composable
fun TabIcon(tab: Tab, color: Color, size: Dp = 24.dp) {
    Canvas(Modifier.size(size).clearAndSetSemantics { }) {
        val s = this.size.minDimension
        val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun DrawScope.line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), strokeWidth = stroke.width, cap = StrokeCap.Round)
        when (tab) {
            Tab.Home -> drawPath(
                Path().apply { moveTo(0.14f * s, 0.48f * s); lineTo(0.5f * s, 0.16f * s); lineTo(0.86f * s, 0.48f * s); moveTo(0.24f * s, 0.42f * s); lineTo(0.24f * s, 0.84f * s); lineTo(0.76f * s, 0.84f * s); lineTo(0.76f * s, 0.42f * s) },
                color, style = stroke,
            )
            Tab.Check -> {
                drawCircle(color, radius = s * 0.38f, center = Offset(s / 2, s / 2), style = stroke)
                drawPath(Path().apply { moveTo(0.33f * s, 0.52f * s); lineTo(0.46f * s, 0.65f * s); lineTo(0.68f * s, 0.38f * s) }, color, style = stroke)
            }
            Tab.Paths -> {
                drawCircle(color, radius = s * 0.09f, center = Offset(0.22f * s, 0.5f * s), style = stroke)
                drawCircle(color, radius = s * 0.09f, center = Offset(0.78f * s, 0.24f * s), style = stroke)
                drawCircle(color, radius = s * 0.09f, center = Offset(0.78f * s, 0.76f * s), style = stroke)
                line(0.31f, 0.5f, 0.45f, 0.5f); line(0.45f, 0.5f, 0.69f, 0.28f); line(0.45f, 0.5f, 0.69f, 0.72f)
            }
            Tab.History -> {
                line(0.2f, 0.28f, 0.8f, 0.28f); line(0.2f, 0.5f, 0.64f, 0.5f); line(0.2f, 0.72f, 0.5f, 0.72f)
                drawCircle(color, radius = s * 0.06f, center = Offset(0.8f * s, 0.72f * s))
            }
            Tab.More -> for (x in listOf(0.2f, 0.5f, 0.8f)) drawCircle(color, radius = s * 0.07f, center = Offset(x * s, 0.5f * s))
        }
    }
}
