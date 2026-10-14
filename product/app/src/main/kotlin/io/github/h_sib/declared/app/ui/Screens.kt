@file:OptIn(ExperimentalLayoutApi::class)

package io.github.h_sib.declared.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.h_sib.declared.core.flow.AppState
import io.github.h_sib.declared.core.flow.Event
import io.github.h_sib.declared.core.flow.StoreStatus
import io.github.h_sib.declared.core.home.Grouping
import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.model.FactGroup
import io.github.h_sib.declared.core.model.Finding
import io.github.h_sib.declared.core.model.Observation
import io.github.h_sib.declared.core.model.SettingsLink
import io.github.h_sib.declared.core.paths.PathsView
import io.github.h_sib.declared.core.report.AppRow
import io.github.h_sib.declared.core.report.Describe
import io.github.h_sib.declared.core.report.Holder
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.state.Review
import io.github.h_sib.declared.core.tier.Tier
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val timeFormat = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

private fun FactGroup.short(): String = when (this) {
    FactGroup.DEVICE -> "Settings"
    FactGroup.NETWORK -> "Network"
    FactGroup.INTEGRITY -> "Integrity"
}

/**
 * The Check tab, as a console: a readout of how many findings there are at each level (a tap filters to one), then the findings worst first.
 * Findings of one kind held by several apps are one card that opens in place. The reasons, the quiet readings and the apps are further down.
 */
@Composable
fun InventoryScreen(
    state: AppState,
    report: Report,
    paths: PathsView,
    ui: CheckUi,
    listState: LazyListState,
    onEvent: (Event) -> Unit,
) {
    val first = report.findings.filter { it.attention == Attention.LOOK_FIRST }
    val look = report.findings.filter { it.attention == Attention.LOOK }
    val notes = report.findings.filter { it.attention == Attention.NOTE }
    val idle = report.holders.filter { !it.active && it.kind.isComponent }
    val expandAll = LocalExpandAll.current
    val sections = remember(report) {
        listOf(
            Triple(Attention.LOOK_FIRST, "Look first", first), Triple(Attention.LOOK, "Look", look), Triple(Attention.NOTE, "For your information", notes),
        ).map { (attention, label, list) -> Triple(attention, label, Grouping.of(list, { it.id }, { it.attention })) }
    }
    val tileLabels = mapOf(Attention.LOOK_FIRST to "Look first", Attention.LOOK to "Look", Attention.NOTE to "Info")

    LazyColumn(state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle("Check", Modifier.weight(1f), subtitle = "Read at ${timeFormat.format(report.takenAt)}" + if (state.refreshing) ". Reading again." else "")
                RefreshButton(state.refreshing) { onEvent(Event.Refresh) }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((attention, _, groups) in sections) {
                    val count = groups.sumOf { it.members.size }
                    StatTile(count, tileLabels.getValue(attention), attention.toStatus(), ui.filter == attention, Modifier.weight(1f).fillMaxHeight()) {
                        ui.filter = if (ui.filter == attention) null else attention
                    }
                }
            }
        }
        if (first.isEmpty() && look.isEmpty() && notes.isEmpty()) item { StatusPill(Status.CLEAR, "Nothing found by these checks") }
        state.refreshError?.let { item { Notice("The last refresh failed, so this is an older reading.", error = true) } }
        item { ChangesAndReview(state, onEvent) }

        for ((attention, label, groups) in sections) {
            if (groups.isEmpty() || (ui.filter != null && ui.filter != attention)) continue
            item(key = "h-$label") { SectionHeader(label, groups.sumOf { it.members.size }, Modifier.padding(top = 8.dp)) }
            items(groups, key = { g -> if (g.isGroup) "g-" + g.key else "f-" + g.members.first().id }) { g ->
                if (g.isGroup) {
                    GroupCard(g, open = expandAll || g.key in ui.open, onToggle = { ui.toggle(g.key) }) { onEvent(Event.OpenFinding(it.id)) }
                } else {
                    FindingCard(g.members.first(), g.kind) { onEvent(Event.OpenFinding(g.members.first().id)) }
                }
            }
        }

        if (ui.filter == null) {
            item { SectionLabel("More", Modifier.padding(top = 12.dp)) }
            if (idle.isNotEmpty()) {
                item {
                    Expandable("Installed, not switched on", summary = "${idle.size} apps hold an ability that is off") {
                        for (h in idle) IdleRow(h)
                    }
                }
            }
            item {
                Expandable("Phone and network readings", summary = "Settings, network, integrity") {
                    for (group in FactGroup.entries) {
                        val rows = report.facts.filter { it.kind.group == group }
                        if (rows.isEmpty()) continue
                        SectionLabel(group.short())
                        for (r in rows) Text(emphasize(r.summary), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            item { NavCard(Glyph.APPS, "All apps", "${report.apps.size} visible to this app") { onEvent(Event.OpenApps) } }
            item {
                Expandable("What this checks", summary = "Nine things, no score") {
                    val checked = listOf(
                        "Accessibility services", "Notification access", "Device administrators", "Keyboards", "Drawing over other apps",
                        "Usage access", "Installing other apps", "Text-message access", "Phone and network settings",
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { for (c in checked) InfoChip(c) }
                    Bullets(listOf("There is no score. A score would claim more than a reading can know."), Status.NOTE)
                    Decoder(checked)
                }
            }
        }
    }
}

@Composable
private fun Notice(text: String, error: Boolean = false) {
    Panel {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusMarker(if (error) Status.RISK else Status.NOTE, Modifier.size(12.dp))
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The two prompts that follow from the saved baseline: what changed since the person last looked, and what is new to review. */
@Composable
private fun ChangesAndReview(state: AppState, onEvent: (Event) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.saveError?.let { Notice("The baseline could not be saved: $it", error = true) }
        (state.store as? StoreStatus.Unavailable)?.let { Notice("This phone would not let the app keep a baseline, so changes cannot be tracked.", error = true) }
        val changes = state.changes
        if (state.saved != null && changes != null && changes.items.isNotEmpty()) {
            val urgent = changes.items.count { it.attention != Attention.NOTE }
            Panel(onClick = { onEvent(Event.OpenWhatChanged) }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusMarker(if (urgent > 0) Status.LOOK else Status.NOTE, Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${changes.items.size} changes since you last looked", style = MaterialTheme.typography.titleSmall)
                        Text(if (urgent > 0) "$urgent worth a look" else "None need attention", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Chevron()
                }
            }
        }
        if (state.pending.isNotEmpty()) {
            Panel(onClick = { onEvent(Event.OpenReview) }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusMarker(Status.LOOK, Modifier.size(12.dp))
                    Text("${Review.groupByApp(state.pending, { it.pkg }, { it.label }).size} new to review", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Chevron()
                }
            }
        }
    }
}

@Composable
private fun IdleRow(h: Holder) {
    Column(Modifier.fillMaxWidth()) {
        Text(listOfNotNull(h.label, h.subLabel?.takeIf { it != h.label }).joinToString(": "), style = MaterialTheme.typography.titleSmall)
        Text(h.kind.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Every visible app, most notable origin first, with what each holds. A lazy list: a thousand apps cost nothing until scrolled to. */
@Composable
fun AppsScreen(report: Report) {
    val grouped = remember(report) { report.apps.groupBy { it.tier.tier } }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item { ScreenTitle("${report.apps.size} apps", subtitle = "Grouped by where they came from, the unusual first.") }
        // The report is already sorted most notable origin first, so the groups come out in that order.
        for ((tier, rows) in grouped) {
            item(key = "header-${tier.name}") { SectionLabel("${Describe.tier(tier)} (${rows.size})", Modifier.padding(top = 18.dp, bottom = 4.dp)) }
            items(rows, key = { it.pkg }) { AppRowView(it, unusual = tier != Tier.STORE && tier != Tier.SYSTEM) }
        }
    }
}

@Composable
private fun AppRowView(a: AppRow, unusual: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (a.kinds.isNotEmpty()) StatusMarker(if (unusual) Status.LOOK else Status.NOTE, Modifier.size(12.dp)) else StatusMarker(Status.UNKNOWN, Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(a.label, style = MaterialTheme.typography.titleSmall)
            val detail = buildList {
                if (a.kinds.isNotEmpty()) add("On: " + a.kinds.sortedBy { it.ordinal }.joinToString(", ") { it.shortName })
                if (a.grants.isNotEmpty()) add("Allowed: " + a.grants.joinToString(", ") { Describe.grant(it) })
                if (!a.hasLauncherIcon) add("No launcher icon")
            }
            if (detail.isNotEmpty()) Text(detail.joinToString(". "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Layer one is what it is, how serious, how sure, and what to do. Layer two is why it matters and what was seen. Layer three is the evidence,
 * where each reading came from, and what this cannot tell. Nothing is removed; the layers are opened on purpose.
 */
@Composable
fun FindingDetailScreen(f: Finding, onOpenSettings: (SettingsLink) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(f.attention.toStatus(), Describe.attention(f.attention))
            InfoChip(Describe.confidence(f.confidence))
            InfoChip(f.section)
        }
        Text(emphasize(f.title), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold))
        Decoder(listOf(f.title))
        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("What you can do")
                f.actions.firstOrNull()?.let { a ->
                    Body(a.text, decode = false)
                    Text("Cost: ${a.tradeOff}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { onOpenSettings(f.settings) }, Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(12.dp)) { Text(f.settings.target.buttonLabel) }
            }
        }
        FindingLayers(f)
    }
}

/** Layers two and three of a finding: why it matters, what was seen, other options, what it cannot tell, and the nerd info. */
@Composable
fun FindingLayers(f: Finding) {
    Column {
        Expandable("Why you should care", summary = "The short version") { Body(f.meaning) }
        Expandable("What the app saw", summary = if (f.claim.size == 1) "1 reading" else "${f.claim.size} readings") { f.claim.forEach { ObservationBlock(it) } }
        if (f.actions.size > 1) {
            Expandable("Other moves", summary = "${f.actions.size - 1} more") {
                f.actions.drop(1).forEach { a ->
                    Text(emphasize(a.text), style = MaterialTheme.typography.bodyMedium)
                    Text("Cost: ${a.tradeOff}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Expandable("What it can't tell you", summary = "Its blind spots") { Bullets(f.whatItCannotTell, Status.UNKNOWN) }
        Expandable("Nerd info", summary = "Sources, sureness, limits") {
            FactRow("Sureness", Describe.confidence(f.confidence))
            FactRow("Rule", "The weakest reading sets it.")
            FactRow("Read from", (f.claim + f.context).map { Describe.source(it.source) }.distinct().joinToString("\n"))
            if (f.context.isNotEmpty()) {
                FactRow("Origin", "Context only, with its own sureness.")
                f.context.forEach { ObservationBlock(it) }
            }
        }
    }
}

@Composable
private fun ObservationBlock(o: Observation) {
    Column(Modifier.padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(emphasize(Describe.observation(o)), style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoChip(Describe.provenance(o.provenance).substringBefore(":"))
            InfoChip(Describe.confidence(o.confidence) + " sureness")
        }
        if (o.limits.isNotEmpty()) Bullets(o.limits, Status.UNKNOWN)
    }
}
