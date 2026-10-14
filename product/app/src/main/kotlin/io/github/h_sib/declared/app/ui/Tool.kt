@file:OptIn(ExperimentalLayoutApi::class)

package io.github.h_sib.declared.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.h_sib.declared.core.home.FindingKind
import io.github.h_sib.declared.core.home.Group
import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.model.Finding
import io.github.h_sib.declared.core.report.Describe

/**
 * The tool half of the interface: icons for the kinds of thing the app looks at, readout tiles that double as filters, cards that group
 * what is alike. Everything here is drawn from real readings; there is no decoration that stands for a result.
 */
enum class Glyph { ACCESSIBILITY, NOTIFICATIONS, ADMIN, KEYBOARD, OVERLAY, USAGE, INSTALL, SMS, UNRECOGNIZED, SIGNER, SETTINGS, NETWORK, INTEGRITY, APPS, REFRESH, EYE, LIVE, PERSON }

fun FindingKind.glyph(): Glyph = when (this) {
    FindingKind.ACCESSIBILITY -> Glyph.ACCESSIBILITY
    FindingKind.NOTIFICATIONS -> Glyph.NOTIFICATIONS
    FindingKind.ADMIN -> Glyph.ADMIN
    FindingKind.KEYBOARD -> Glyph.KEYBOARD
    FindingKind.OVERLAY -> Glyph.OVERLAY
    FindingKind.USAGE -> Glyph.USAGE
    FindingKind.INSTALL -> Glyph.INSTALL
    FindingKind.SMS -> Glyph.SMS
    FindingKind.NOT_RECOGNIZED -> Glyph.UNRECOGNIZED
    FindingKind.NEW_SIGNER -> Glyph.SIGNER
    FindingKind.SETTING -> Glyph.SETTINGS
    FindingKind.NETWORK -> Glyph.NETWORK
    FindingKind.INTEGRITY -> Glyph.INTEGRITY
}

@Composable
fun GlyphIcon(glyph: Glyph, color: Color, modifier: Modifier = Modifier, iconSize: Dp = 22.dp, description: String? = null) {
    Canvas(modifier.size(iconSize).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier.clearAndSetSemantics { })) {
        val s = size.minDimension
        val w = 1.8.dp.toPx()
        val st = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun DrawScope.ln(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(color, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), strokeWidth = w, cap = StrokeCap.Round)
        fun DrawScope.path(build: Path.() -> Unit) = drawPath(Path().apply(build), color, style = st)
        fun DrawScope.box(x: Float, y: Float, wd: Float, ht: Float, r: Float) = drawRoundRect(color, Offset(x * s, y * s), Size(wd * s, ht * s), CornerRadius(r * s), style = st)
        fun DrawScope.dot(x: Float, y: Float, r: Float) = drawCircle(color, r * s, Offset(x * s, y * s))
        when (glyph) {
            Glyph.ACCESSIBILITY -> {
                drawCircle(color, s * 0.11f, Offset(s * 0.5f, s * 0.2f), style = st)
                ln(0.18f, 0.38f, 0.82f, 0.38f); ln(0.5f, 0.38f, 0.5f, 0.62f); ln(0.5f, 0.62f, 0.32f, 0.88f); ln(0.5f, 0.62f, 0.68f, 0.88f)
            }
            Glyph.NOTIFICATIONS -> {
                path {
                    moveTo(0.2f * s, 0.74f * s); lineTo(0.8f * s, 0.74f * s); lineTo(0.72f * s, 0.64f * s); lineTo(0.72f * s, 0.42f * s)
                    cubicTo(0.72f * s, 0.26f * s, 0.62f * s, 0.18f * s, 0.5f * s, 0.18f * s); cubicTo(0.38f * s, 0.18f * s, 0.28f * s, 0.26f * s, 0.28f * s, 0.42f * s)
                    lineTo(0.28f * s, 0.64f * s); close()
                }
                ln(0.42f, 0.88f, 0.58f, 0.88f)
            }
            Glyph.ADMIN -> path {
                moveTo(0.5f * s, 0.1f * s); lineTo(0.84f * s, 0.24f * s); lineTo(0.84f * s, 0.5f * s)
                cubicTo(0.84f * s, 0.72f * s, 0.68f * s, 0.84f * s, 0.5f * s, 0.92f * s); cubicTo(0.32f * s, 0.84f * s, 0.16f * s, 0.72f * s, 0.16f * s, 0.5f * s)
                lineTo(0.16f * s, 0.24f * s); close()
            }
            Glyph.KEYBOARD -> {
                box(0.08f, 0.26f, 0.84f, 0.48f, 0.08f)
                dot(0.28f, 0.42f, 0.04f); dot(0.5f, 0.42f, 0.04f); dot(0.72f, 0.42f, 0.04f); ln(0.3f, 0.6f, 0.7f, 0.6f)
            }
            Glyph.OVERLAY -> { box(0.1f, 0.1f, 0.55f, 0.55f, 0.08f); box(0.35f, 0.35f, 0.55f, 0.55f, 0.08f) }
            Glyph.USAGE -> { ln(0.25f, 0.85f, 0.25f, 0.55f); ln(0.5f, 0.85f, 0.5f, 0.2f); ln(0.75f, 0.85f, 0.75f, 0.42f) }
            Glyph.INSTALL -> {
                ln(0.5f, 0.12f, 0.5f, 0.6f)
                path { moveTo(0.3f * s, 0.42f * s); lineTo(0.5f * s, 0.62f * s); lineTo(0.7f * s, 0.42f * s) }
                path { moveTo(0.15f * s, 0.68f * s); lineTo(0.15f * s, 0.86f * s); lineTo(0.85f * s, 0.86f * s); lineTo(0.85f * s, 0.68f * s) }
            }
            Glyph.SMS -> path {
                moveTo(0.12f * s, 0.2f * s); lineTo(0.88f * s, 0.2f * s); lineTo(0.88f * s, 0.66f * s); lineTo(0.42f * s, 0.66f * s)
                lineTo(0.24f * s, 0.84f * s); lineTo(0.24f * s, 0.66f * s); lineTo(0.12f * s, 0.66f * s); close()
            }
            Glyph.UNRECOGNIZED -> {
                drawCircle(color, s * 0.38f, Offset(s * 0.5f, s * 0.5f), style = st)
                ln(0.5f, 0.3f, 0.5f, 0.56f); dot(0.5f, 0.72f, 0.045f)
            }
            Glyph.SIGNER -> {
                drawCircle(color, s * 0.16f, Offset(s * 0.3f, s * 0.5f), style = st)
                ln(0.46f, 0.5f, 0.9f, 0.5f); ln(0.74f, 0.5f, 0.74f, 0.66f); ln(0.86f, 0.5f, 0.86f, 0.62f)
            }
            Glyph.SETTINGS -> {
                ln(0.12f, 0.28f, 0.88f, 0.28f); ln(0.12f, 0.5f, 0.88f, 0.5f); ln(0.12f, 0.72f, 0.88f, 0.72f)
                dot(0.66f, 0.28f, 0.08f); dot(0.34f, 0.5f, 0.08f); dot(0.7f, 0.72f, 0.08f)
            }
            Glyph.NETWORK -> {
                for (r in listOf(0.6f, 0.4f, 0.2f)) {
                    drawArc(color, 225f, 90f, false, Offset((0.5f - r / 2) * s, (0.8f - r / 2) * s), Size(r * s, r * s), style = st)
                }
                dot(0.5f, 0.8f, 0.05f)
            }
            Glyph.INTEGRITY -> {
                box(0.2f, 0.46f, 0.6f, 0.4f, 0.08f)
                path { moveTo(0.32f * s, 0.46f * s); lineTo(0.32f * s, 0.34f * s); cubicTo(0.32f * s, 0.12f * s, 0.68f * s, 0.12f * s, 0.68f * s, 0.34f * s); lineTo(0.68f * s, 0.46f * s) }
            }
            Glyph.APPS -> { box(0.12f, 0.12f, 0.3f, 0.3f, 0.06f); box(0.58f, 0.12f, 0.3f, 0.3f, 0.06f); box(0.12f, 0.58f, 0.3f, 0.3f, 0.06f); box(0.58f, 0.58f, 0.3f, 0.3f, 0.06f) }
            Glyph.EYE -> {
                path {
                    moveTo(0.08f * s, 0.5f * s); cubicTo(0.28f * s, 0.2f * s, 0.72f * s, 0.2f * s, 0.92f * s, 0.5f * s)
                    cubicTo(0.72f * s, 0.8f * s, 0.28f * s, 0.8f * s, 0.08f * s, 0.5f * s); close()
                }
                drawCircle(color, s * 0.12f, Offset(s * 0.5f, s * 0.5f), style = st)
            }
            Glyph.LIVE -> path {
                moveTo(0.06f * s, 0.5f * s); lineTo(0.3f * s, 0.5f * s); lineTo(0.4f * s, 0.24f * s); lineTo(0.56f * s, 0.78f * s); lineTo(0.66f * s, 0.5f * s); lineTo(0.94f * s, 0.5f * s)
            }
            Glyph.PERSON -> {
                drawCircle(color, s * 0.16f, Offset(s * 0.5f, s * 0.32f), style = st)
                path { moveTo(0.18f * s, 0.88f * s); cubicTo(0.18f * s, 0.58f * s, 0.82f * s, 0.58f * s, 0.82f * s, 0.88f * s) }
            }
            Glyph.REFRESH -> {
                drawArc(color, 40f, 280f, false, Offset(s * 0.16f, s * 0.16f), Size(s * 0.68f, s * 0.68f), style = st)
                path { moveTo(0.66f * s, 0.06f * s); lineTo(0.8f * s, 0.2f * s); lineTo(0.62f * s, 0.28f * s) }
            }
        }
    }
}

/** A square tile with the icon of a kind, tinted by how serious the finding is. The shape and the words next to it carry the same meaning. */
@Composable
fun IconChip(glyph: Glyph, status: Status, modifier: Modifier = Modifier) {
    val c = status.color()
    val shape = RoundedCornerShape(10.dp)
    Box(modifier.size(40.dp).clip(shape).background(c.copy(alpha = 0.14f)).border(1.dp, c.copy(alpha = 0.4f), shape), contentAlignment = Alignment.Center) { GlyphIcon(glyph, c) }
}

/** A readout and a filter in one: how many findings of a level, and a tap shows only those. */
@Composable
fun StatTile(count: Int, label: String, status: Status, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = status.color()
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape)
            .background(if (selected) c.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceContainerLow)
            .border(if (selected) 2.dp else 1.dp, if (selected) c else MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClickLabel = if (selected) "Show all" else "Show only these", role = Role.Button, onClick = onClick)
            .semantics { stateDescription = if (selected) "Showing only these" else "Showing all" }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusMarker(status, Modifier.size(10.dp))
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall.copy(fontFamily = DeclaredMono, fontWeight = FontWeight.Bold))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The findings by level as one bar. Every segment's width is its real count; the words are in the legend beside it. */
@Composable
fun SeverityBar(first: Int, look: Int, note: Int, modifier: Modifier = Modifier) {
    val none = first + look + note == 0
    Row(
        modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.outlineVariant).clearAndSetSemantics { },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (none) Box(Modifier.weight(1f).fillMaxHeight().background(Status.CLEAR.color()))
        for ((n, status) in listOf(first to Status.RISK, look to Status.LOOK, note to Status.NOTE)) if (n > 0) Box(Modifier.weight(n.toFloat()).fillMaxHeight().background(status.color()))
    }
}

/** A single finding as a card: what kind it is, what it says, how sure the app is. */
@Composable
fun FindingCard(f: Finding, kind: FindingKind, onClick: () -> Unit) {
    val status = f.attention.toStatus()
    Panel(onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconChip(kind.glyph(), status)
            Column(Modifier.weight(1f)) {
                Text(f.title, style = MaterialTheme.typography.titleSmall)
                Text("${f.section}. ${Describe.confidence(f.confidence)} confidence", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Chevron()
        }
    }
}

/** Several findings of one kind and level as one card that opens in place, so a long list of near-identical rows is one row. */
@Composable
fun GroupCard(group: Group<Finding>, open: Boolean, onToggle: () -> Unit, onOpen: (Finding) -> Unit) {
    val status = group.attention.toStatus()
    Panel {
        Row(
            Modifier.fillMaxWidth()
                .clickable(onClickLabel = if (open) "Collapse" else "Expand", role = Role.Button, onClick = onToggle)
                .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconChip(group.kind.glyph(), status)
            Column(Modifier.weight(1f)) {
                Text(group.kind.title, style = MaterialTheme.typography.titleSmall)
                Text("${group.members.size} ${group.kind.noun}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusMarker(status, Modifier.size(12.dp))
            Chevron(up = open, down = !open)
        }
        if (open) for (m in group.members) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(m) }.padding(start = 64.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(m.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
                    Text("${Describe.confidence(m.confidence)} confidence", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Chevron()
            }
        }
    }
}

/** A plain destination as a card with an icon, for the places that are not findings. */
@Composable
fun NavCard(glyph: Glyph, title: String, subtitle: String, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                GlyphIcon(glyph, MaterialTheme.colorScheme.primary)
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Chevron()
        }
    }
}

/** A number and what it counts, with no action: a readout. */
@Composable
fun Readout(value: String, label: String, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow).border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = DeclaredMono, fontWeight = FontWeight.Bold), color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A section's name with how many are in it, in the console's small print. */
@Composable
fun SectionHeader(label: String, count: Int, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(label)
        Text(count.toString(), style = MaterialTheme.typography.labelMedium.copy(fontFamily = DeclaredMono), color = MaterialTheme.colorScheme.primary)
    }
}

/** Reads the phone again. While it does, a ring turns: the ring is shown only for a real read. */
@Composable
fun RefreshButton(refreshing: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        if (refreshing) {
            CircularProgressIndicator(Modifier.size(22.dp).semantics { contentDescription = "Check again" }, strokeWidth = 2.dp)
        } else {
            GlyphIcon(Glyph.REFRESH, MaterialTheme.colorScheme.primary, description = "Check again")
        }
    }
}

/** What the Check tab remembers while a finding is open on top of it: the level filter and the groups that were opened. */
@Stable
class CheckUi {
    var filter by mutableStateOf<Attention?>(null)
    var open by mutableStateOf(emptySet<String>())

    fun toggle(key: String) {
        open = if (key in open) open - key else open + key
    }

    companion object {
        @Suppress("UNCHECKED_CAST")
        val Saver = listSaver<CheckUi, Any?>(
            save = { listOf(it.filter?.name, it.open.toList()) },
            restore = { CheckUi().apply { filter = (it[0] as String?)?.let(Attention::valueOf); open = (it[1] as List<String>).toSet() } },
        )
    }
}
