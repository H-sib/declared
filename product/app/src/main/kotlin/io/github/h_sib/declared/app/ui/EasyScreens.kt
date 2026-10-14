package io.github.h_sib.declared.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.h_sib.declared.core.flow.AppState
import io.github.h_sib.declared.core.flow.Event
import io.github.h_sib.declared.core.home.Easy
import io.github.h_sib.declared.core.home.Grouping
import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.model.Finding
import io.github.h_sib.declared.core.model.SettingsLink
import io.github.h_sib.declared.core.report.Describe
import io.github.h_sib.declared.core.report.Report
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val readAtFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

/**
 * The simple view: for a person who is new to this. Bigger text (the theme scales it), three places instead of five, and for each thing one
 * card with what it means in one sentence and what to do. The detail is still there, one tap behind "Technical details".
 */
@Composable
fun EasyHomeScreen(state: AppState, report: Report, onEvent: (Event) -> Unit, onOpenGuide: () -> Unit, scroll: ScrollState = rememberScrollState()) {
    val prefs = LocalUiPrefs.current
    val urgent = remember(report) { Easy.urgentFirst(report.findings) { it.attention } }
    val haptic = LocalHapticFeedback.current
    // The one button stays at the bottom, above the bar, so it is never behind a scroll.
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).padding(top = 12.dp, bottom = 8.dp)) {
        Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeclaredMark(Modifier.size(24.dp))
                Text("Declared", style = MaterialTheme.typography.labelLarge)
            }
            Panel {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Easy.headline(urgent.size), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
                    Text(Easy.subline(urgent.size), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!prefs.guideSeen) GuideCard(onOpenGuide)
            for (f in urgent.take(3)) EasyCard(f) { onEvent(Event.OpenFinding(f.id)) }
            if (urgent.size > 3) OutlinedButton(onClick = { onEvent(Event.OpenCheck) }, Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text("See all ${urgent.size}") }
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onEvent(Event.Refresh) },
            Modifier.fillMaxWidth().heightIn(min = 56.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text(if (state.refreshing) "Checking..." else "Check my phone again", style = MaterialTheme.typography.labelLarge) }
        Text("Nothing leaves this phone.", Modifier.fillMaxWidth().padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Everything found, in three plain groups. The same cards, the same groups of alike apps, nothing about filters or sources. */
@Composable
fun EasyCheckScreen(state: AppState, report: Report, ui: CheckUi, listState: LazyListState, onEvent: (Event) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val expandAll = LocalExpandAll.current
    val sections = remember(report) {
        listOf(
            Triple(Attention.LOOK_FIRST, "Do these first", report.findings.filter { it.attention == Attention.LOOK_FIRST }),
            Triple(Attention.LOOK, "Worth a look", report.findings.filter { it.attention == Attention.LOOK }),
            Triple(Attention.NOTE, "Good to know", report.findings.filter { it.attention == Attention.NOTE }),
        ).map { (attention, label, list) -> Triple(attention, label, Grouping.of(list, { it.id }, { it.attention })) }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("To do", subtitle = "Read at ${readAtFormat.format(report.takenAt)}") }
        item {
            Button(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onEvent(Event.Refresh) },
                Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = RoundedCornerShape(14.dp),
            ) { Text(if (state.refreshing) "Checking..." else "Check my phone again", style = MaterialTheme.typography.labelLarge) }
        }
        if (sections.all { it.third.isEmpty() }) item { Panel { Text("Nothing found by these checks.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodyLarge) } }
        for ((_, label, groups) in sections) {
            if (groups.isEmpty()) continue
            item(key = "h-$label") { SectionHeader(label, groups.sumOf { it.members.size }, Modifier.padding(top = 8.dp)) }
            items(groups, key = { g -> if (g.isGroup) "g-" + g.key else "f-" + g.members.first().id }) { g ->
                if (g.isGroup) {
                    GroupCard(g, open = expandAll || g.key in ui.open, onToggle = { ui.toggle(g.key) }) { onEvent(Event.OpenFinding(it.id)) }
                } else {
                    EasyCard(g.members.first()) { onEvent(Event.OpenFinding(g.members.first().id)) }
                }
            }
        }
    }
}

/** One thing as a big card: what it is, and in one sentence why it matters. */
@Composable
fun EasyCard(f: Finding, onClick: () -> Unit) {
    Panel(Modifier.testTag("easy-card"), onClick = onClick) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconChip(Grouping.kindOf(f.id).glyph(), f.attention.toStatus())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(f.title, style = MaterialTheme.typography.titleMedium)
                Text(Easy.why(f.meaning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Chevron()
        }
    }
}

/** A finding for a person new to this: what it means, what to do with one big button. The full layers are behind "Technical details". */
@Composable
fun EasyFindingDetail(f: Finding, onOpenSettings: (SettingsLink) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StatusPill(f.attention.toStatus(), Describe.attention(f.attention))
        Text(emphasize(f.title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold))
        Decoder(listOf(f.title, f.meaning), max = 2)
        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("What this means")
                Text(emphasize(Easy.why(f.meaning)), style = MaterialTheme.typography.bodyLarge)
            }
        }
        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionLabel("What to do")
                f.actions.firstOrNull()?.let { a ->
                    Text(emphasize(Easy.why(a.text)), style = MaterialTheme.typography.bodyLarge)
                    Text("Good to know: ${Easy.why(a.tradeOff)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { onOpenSettings(f.settings) }, Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(14.dp)) { Text(f.settings.target.buttonLabel) }
            }
        }
        Expandable("Technical details", summary = "For people who want the full story") { FindingLayers(f) }
    }
}

/** The offer of the tour, on the home screen until it is taken or turned down. */
@Composable
fun GuideCard(onOpen: () -> Unit) {
    val prefs = LocalUiPrefs.current
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconChip(Glyph.EYE, Status.NOTE)
                Column {
                    Text("New here?", style = MaterialTheme.typography.titleSmall)
                    Text("A one minute tour shows how to use Declared.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpen, Modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(12.dp)) { Text("Take the tour") }
                TextButton(onClick = prefs::markGuideSeen) { Text("Not now") }
            }
        }
    }
}

/** Hides the app from screenshots, screen recordings and the recent apps list. Off unless the person turns it on. */
@Composable
fun BlockScreenshotsSwitch(modifier: Modifier = Modifier) {
    val prefs = LocalUiPrefs.current
    Panel(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = prefs.blockScreenshots, role = Role.Switch, onValueChange = prefs::chooseBlockScreenshots).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Block screenshots", style = MaterialTheme.typography.titleSmall)
                Text("Hides this app from screenshots, recordings and the recent apps list.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = prefs.blockScreenshots, onCheckedChange = null)
        }
    }
}

/** The switch between the simple view and the full one. Used under More and on the intro pages, so it can be chosen from the first screen. */
@Composable
fun ViewModeSwitch(modifier: Modifier = Modifier) {
    val prefs = LocalUiPrefs.current
    Panel(modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = prefs.easy, role = Role.Switch, onValueChange = prefs::chooseEasy).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Simple view", style = MaterialTheme.typography.titleSmall)
                Text("Bigger text, fewer things, plain words.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = prefs.easy, onCheckedChange = null)
        }
    }
}
