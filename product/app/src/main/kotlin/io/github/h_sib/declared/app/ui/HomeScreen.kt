@file:OptIn(ExperimentalLayoutApi::class)

package io.github.h_sib.declared.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.h_sib.declared.core.flow.AppState
import io.github.h_sib.declared.core.flow.Event
import io.github.h_sib.declared.core.home.Area
import io.github.h_sib.declared.core.home.AreaKind
import io.github.h_sib.declared.core.home.HashGlyph
import io.github.h_sib.declared.core.home.Home
import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.paths.PathsView
import io.github.h_sib.declared.core.report.Report
import kotlinx.coroutines.delay

/**
 * The home screen: what is known right now in one statement, how much was checked, the phone itself with a real status for each area,
 * and one button. The three main places are in the bottom bar; nothing else competes for the first look.
 */
@Composable
fun HomeScreen(state: AppState, report: Report, paths: PathsView, onEvent: (Event) -> Unit, scroll: ScrollState = rememberScrollState(), onOpenGuide: () -> Unit = {}) {
    val summary = remember(report, paths, state.history, state.changes) { Home.of(report, paths, state.history, state.changes?.items?.size) }
    val areas = remember(report, state.history) { Home.areas(report, state.history) }
    val c = summary.coverage
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DeclaredMark(Modifier.size(22.dp))
            Text("Declared", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            StatusPill(Status.CLEAR, "Works offline")
        }
        Spacer(Modifier.height(20.dp))
        if (!LocalUiPrefs.current.guideSeen) {
            GuideCard(onOpenGuide)
            Spacer(Modifier.height(12.dp))
        }
        // The status card: the headline count, the findings by level as one bar, and how much of the checking could be done.
        val first = report.findings.count { it.attention == Attention.LOOK_FIRST }
        val look = report.findings.count { it.attention == Attention.LOOK }
        val note = report.findings.count { it.attention == Attention.NOTE }
        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(summary.statement, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold))
                    summary.detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                SeverityBar(first, look, note)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (first > 0) StatusPill(Status.RISK, "$first look first")
                    if (look > 0) StatusPill(Status.LOOK, "$look look")
                    if (note > 0) StatusPill(Status.NOTE, "$note info")
                    if (first + look + note == 0) StatusPill(Status.CLEAR, "Nothing found")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${c.ran} of ${c.total} checks ran", style = MaterialTheme.typography.labelMedium.copy(fontFamily = DeclaredMono), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (c.total > 0) {
                        LinearProgressIndicator(
                            progress = { c.ran.toFloat() / c.total },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).clearAndSetSemantics { },
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outlineVariant,
                            drawStopIndicator = {},
                        )
                    }
                    if (c.couldNotRun > 0 || c.unsupported > 0) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (c.couldNotRun > 0) StatusPill(Status.UNKNOWN, "${c.couldNotRun} could not run")
                            if (c.unsupported > 0) StatusPill(Status.UNSUPPORTED, "${c.unsupported} not supported")
                        }
                    }
                }
            }
        }
        summary.changedLine?.let { line ->
            Spacer(Modifier.height(12.dp))
            Panel(onClick = { onEvent(Event.OpenWhatChanged) }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusMarker(Status.NOTE, Modifier.size(12.dp))
                    Text(line, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Chevron()
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            DeviceMap(areas) { kind ->
                onEvent(
                    when (kind) {
                        AreaKind.APPS -> Event.OpenApps
                        AreaKind.HISTORY -> Event.OpenTimeline
                        else -> Event.OpenCheck
                    },
                )
            }
        }
        Spacer(Modifier.height(20.dp))
        // Reads the phone again and opens the result.
        val haptic = LocalHapticFeedback.current
        Button(
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onEvent(Event.Refresh); onEvent(Event.OpenCheck) },
            Modifier.fillMaxWidth().heightIn(min = 54.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text("Check this phone", style = MaterialTheme.typography.labelLarge) }
        Spacer(Modifier.height(10.dp))
        Text("Nothing leaves this phone.", Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * The phone, drawn bright against the background, with one tile per area of the phone. Each tile is a real reading: its shape, color and words
 * come from what the checks found (`Home.areas`). Tapping a tile opens the place where that area is explained.
 */
@Composable
fun DeviceMap(areas: List<Area>, modifier: Modifier = Modifier, onArea: (AreaKind) -> Unit) {
    val glow = MaterialTheme.colorScheme.primary
    val body = MaterialTheme.colorScheme.surfaceContainerLow
    val tile = MaterialTheme.colorScheme.surfaceContainerHigh
    val line = MaterialTheme.colorScheme.outlineVariant
    Box(modifier.width(252.dp).height(344.dp)) {
        Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
            val inset = 12.dp.toPx()
            val topLeft = Offset(inset, inset)
            val sz = Size(size.width - 2 * inset, size.height - 2 * inset)
            val r = CornerRadius(36.dp.toPx())
            for ((w, a) in listOf(10.dp to 0.05f, 7.dp to 0.08f, 4.dp to 0.14f)) drawRoundRect(glow.copy(alpha = a), topLeft, sz, r, style = Stroke(w.toPx() * 2))
            drawRoundRect(body, topLeft, sz, r)
            drawRoundRect(glow, topLeft, sz, r, style = Stroke(2.dp.toPx()))
            drawRoundRect(glow.copy(alpha = 0.55f), Offset(size.width / 2 - 24.dp.toPx(), inset + 14.dp.toPx()), Size(48.dp.toPx(), 5.dp.toPx()), CornerRadius(3.dp.toPx()))
            drawRoundRect(line, Offset(size.width / 2 - 36.dp.toPx(), size.height - inset - 16.dp.toPx()), Size(72.dp.toPx(), 4.dp.toPx()), CornerRadius(2.dp.toPx()))
        }
        Column(Modifier.padding(start = 28.dp, end = 28.dp, top = 52.dp, bottom = 44.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((row, pair) in areas.chunked(2).withIndex()) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((col, area) in pair.withIndex()) AreaTile(area, row * 2 + col, tile, Modifier.weight(1f).fillMaxHeight()) { onArea(area.kind) }
                }
            }
        }
    }
}

@Composable
private fun AreaTile(area: Area, index: Int, fill: androidx.compose.ui.graphics.Color, modifier: Modifier, onClick: () -> Unit) {
    val status = area.status.toStatus()
    val shape = RoundedCornerShape(12.dp)
    // The tiles settle in one after another, so the phone fills up like it is being read; the readings themselves are already known.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(70L * index); shown = true }
    val p by animateFloatAsState(if (shown) 1f else 0f, tween(300), label = "tile")
    val haptic = LocalHapticFeedback.current
    Column(
        modifier.graphicsLayer { alpha = p; translationY = (1f - p) * 12.dp.toPx() }
            .clip(shape).background(fill).border(1.dp, status.color().copy(alpha = 0.45f), shape)
            .clickable(role = Role.Button, onClick = { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onClick() })
            .semantics(mergeDescendants = true) { contentDescription = "${area.title}: ${status.words}. ${area.line}" }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusMarker(status, Modifier.size(10.dp))
            Text(area.title, style = MaterialTheme.typography.labelMedium)
        }
        Text(area.line, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The mark: an H (for H-sib) made of two halves of one token, cut along a stepped line. Only the true halves fit, which is how a record used to be proved unaltered. */
@Composable
fun DeclaredMark(modifier: Modifier = Modifier) {
    val left = MaterialTheme.colorScheme.onSurface
    val right = MaterialTheme.colorScheme.primary
    Canvas(modifier.clearAndSetSemantics { }) {
        val k = size.width / 24f
        fun poly(vararg p: Pair<Float, Float>) = Path().apply {
            moveTo(p[0].first * k, p[0].second * k)
            for (i in 1 until p.size) lineTo(p[i].first * k, p[i].second * k)
            close()
        }
        drawPath(poly(0f to 0f, 7f to 0f, 7f to 9f, 13f to 9f, 13f to 12f, 11f to 12f, 11f to 15f, 7f to 15f, 7f to 24f, 0f to 24f), left)
        drawPath(poly(17f to 0f, 24f to 0f, 24f to 24f, 17f to 24f, 17f to 15f, 12.2f to 15f, 12.2f to 12f, 14.2f to 12f, 14.2f to 9f, 17f to 9f), right)
    }
}

/** The pattern drawn from the newest entry's hash (`HashGlyph`). Decorative for a screen reader; the hash itself is written next to it. */
@Composable
fun HashGlyphView(hash: String, modifier: Modifier = Modifier) {
    val on = MaterialTheme.colorScheme.primary
    val off = MaterialTheme.colorScheme.outlineVariant
    val cells = remember(hash) { HashGlyph.cells(hash) }
    Canvas(modifier.clearAndSetSemantics { }) {
        val cell = size.width / HashGlyph.SIZE
        val pad = cell * 0.12f
        for (i in cells.indices) {
            val r = i / HashGlyph.SIZE
            val c = i % HashGlyph.SIZE
            drawRect(if (cells[i]) on else off.copy(alpha = 0.5f), Offset(c * cell + pad, r * cell + pad), Size(cell - 2 * pad, cell - 2 * pad))
        }
    }
}
