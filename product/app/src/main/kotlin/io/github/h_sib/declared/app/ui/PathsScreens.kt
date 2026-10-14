@file:OptIn(ExperimentalLayoutApi::class)

package io.github.h_sib.declared.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.h_sib.declared.core.flow.Event
import io.github.h_sib.declared.core.model.Declarations
import io.github.h_sib.declared.core.model.Profile
import io.github.h_sib.declared.core.model.SettingsLink
import io.github.h_sib.declared.core.model.Told
import io.github.h_sib.declared.core.paths.Lever
import io.github.h_sib.declared.core.paths.OpenPathView
import io.github.h_sib.declared.core.paths.PathView
import io.github.h_sib.declared.core.paths.PathsAnalysis
import io.github.h_sib.declared.core.paths.PathsWords
import io.github.h_sib.declared.core.paths.Severity
import io.github.h_sib.declared.core.report.Describe

private fun Severity.toStatus(): Status = when (this) {
    Severity.CRITICAL, Severity.HIGH -> Status.RISK
    Severity.MEDIUM -> Status.LOOK
    Severity.LOW -> Status.NOTE
}

/**
 * The attack paths (STAGE2_DESIGN.md section 7): what exists given what was read and what the person said, what is held open and why, and the
 * smallest set of changes with a preview that changes nothing on the phone. Each path is a title and a severity first; how it could happen and
 * what stops it are one tap away.
 */
@Composable
fun PathsScreen(
    analysis: PathsAnalysis,
    whatIf: Set<String>,
    onEvent: (Event) -> Unit,
    onOpenSettings: (SettingsLink) -> Unit,
    listState: LazyListState = rememberLazyListState(),
) {
    val view = analysis.view
    val preview = analysis.preview(whatIf)
    val stoppedByPreview = preview.stopped.map { it.node.value }.toSet()
    LazyColumn(state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Column {
                ScreenTitle("Attack paths", subtitle = if (view.pathCount == 0) PathsWords.NONE_FOUND else PathsWords.pathCount(view.pathCount))
                Row(Modifier.fillMaxWidth().padding(top = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Readout(view.pathCount.toString(), "Paths", Modifier.weight(1f).fillMaxHeight())
                    Readout((view.plan?.levers?.size ?: 0).toString(), "Changes in the plan", Modifier.weight(1f).fillMaxHeight())
                    Readout(view.notRuledOut.size.toString(), "Not ruled out", Modifier.weight(1f).fillMaxHeight())
                }
                Expandable("How to read this", Modifier.padding(top = 4.dp)) { Body(PathsWords.INTRO) }
            }
        }

        view.plan?.let { plan ->
            item {
                Panel {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SectionLabel(PathsWords.PLAN_TITLE)
                        Text(PathsWords.planSummary(plan.levers.size, plan.cut.size, view.pathCount), style = MaterialTheme.typography.titleSmall)
                        for (l in plan.levers) LeverRow(l, selected = l.id in whatIf, onToggle = { onEvent(Event.ToggleLever(l.id)) }, onOpenSettings = onOpenSettings)
                        if (whatIf.isNotEmpty()) {
                            Text(PathsWords.remaining(preview.after.size), style = MaterialTheme.typography.titleSmall)
                            OutlinedButton(onClick = { onEvent(Event.ClearWhatIf) }) { Text("Clear the preview") }
                        }
                        Expandable("About this plan") {
                            Text(if (plan.exact) PathsWords.PLAN_EXACT else PathsWords.PLAN_APPROXIMATE, style = MaterialTheme.typography.bodySmall)
                            if (plan.unstoppable.isNotEmpty()) Text("No change known here would stop: " + plan.unstoppable.joinToString("; ") { it.title }, style = MaterialTheme.typography.bodySmall)
                            Text(PathsWords.PREVIEW_HINT, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        items(view.paths, key = { it.id }) { PathCard(it, it.id in stoppedByPreview, whatIf, onEvent, onOpenSettings) }

        if (view.notRuledOut.isNotEmpty()) {
            item {
                Column {
                    SectionLabel("Not ruled out")
                    Expandable("What this means", Modifier.padding(top = 4.dp)) { Body(PathsWords.NOT_RULED_OUT_INTRO) }
                }
            }
            items(view.notRuledOut, key = { "open-" + it.id }) { OpenPathCard(it) }
        }

        item { ListRow("Answer the questions about you", subtitle = "Some paths depend on your answers", onClick = { onEvent(Event.OpenAboutYou) }) }
    }
}

@Composable
private fun PathCard(
    p: PathView,
    stoppedByPreview: Boolean,
    whatIf: Set<String>,
    onEvent: (Event) -> Unit,
    onOpenSettings: (SettingsLink) -> Unit,
) {
    Panel {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusPill(p.severity.toStatus(), PathsWords.severity(p.severity))
                InfoChip(p.asset.label)
            }
            Text(emphasize(p.title), Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium)
            if (stoppedByPreview) Text("The changes you ticked would stop this path.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.titleSmall, color = Status.CLEAR.color())
            Expandable("How it could happen", Modifier.padding(top = 6.dp)) {
                for (s in p.steps) {
                    Text(emphasize(s.text), style = MaterialTheme.typography.bodyMedium)
                    Text("${PathsWords.provenance(s.provenance)}. ${Describe.confidence(s.confidence)}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                FactRow("Path sureness", "${Describe.confidence(p.confidence)}. The weakest step sets it.")
                if (p.restsOnWhatYouSaid) FactRow("Based on", "What you told this app.")
            }
            Expandable("What this app cannot see here") { Bullets(p.cannotSee, Status.UNKNOWN) }
            Expandable("What stops it", startOpen = false) {
                if (p.stoppedBy.isEmpty()) {
                    Body(PathsWords.NOTHING_CAN_STOP_THIS)
                } else {
                    for (l in p.stoppedBy) LeverRow(l, selected = l.id in whatIf, onToggle = { onEvent(Event.ToggleLever(l.id)) }, onOpenSettings = onOpenSettings)
                }
            }
        }
    }
}

@Composable
private fun OpenPathCard(p: OpenPathView) {
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(p.severity.toStatus(), PathsWords.severity(p.severity))
            Text(emphasize(p.title), style = MaterialTheme.typography.titleMedium)
            Expandable("Why it is open") { Bullets(p.because, Status.NOTE) }
        }
    }
}

@Composable
private fun LeverRow(l: Lever, selected: Boolean, onToggle: () -> Unit, onOpenSettings: (SettingsLink) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(emphasize(l.text), style = MaterialTheme.typography.titleSmall)
        Text("Cost: ${l.tradeOff}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = selected, role = Role.Switch, onValueChange = { onToggle() }), verticalAlignment = Alignment.CenterVertically) {
            Text("Preview this change", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = selected, onCheckedChange = null)
        }
        val link = l.settings
        if (link != null && !l.outsidePhone) OutlinedButton(onClick = { onOpenSettings(link) }, Modifier.heightIn(min = 40.dp)) { Text(link.target.buttonLabel) }
    }
}

/** The questions only the person can answer. "Not sure" is stored as itself and keeps the dependent paths open. */
@Composable
fun AboutYouScreen(declarations: Declarations, onEvent: (Event) -> Unit) {
    Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenTitle("About you")
        Expandable("Why these questions") { Body(PathsWords.ABOUT_INTRO) }
        Question(PathsWords.ASK_SMS_CODES, Told.entries, declarations.smsCodes, PathsWords::told) { onEvent(Event.SetDeclarations(declarations.copy(smsCodes = it))) }
        Question(PathsWords.ASK_PUBLIC_WIFI, Told.entries, declarations.publicWifi, PathsWords::told) { onEvent(Event.SetDeclarations(declarations.copy(publicWifi = it))) }
        Question(PathsWords.ASK_PROFILE, Profile.entries, declarations.profile, PathsWords::profile) { onEvent(Event.SetDeclarations(declarations.copy(profile = it))) }
        Button(onClick = { onEvent(Event.Back) }, Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(14.dp)) { Text("Done") }
    }
}

@Composable
private fun <T> Question(text: String, options: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text, style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (o in options) FilterChip(selected = o == selected, onClick = { onPick(o) }, label = { Text(label(o)) })
            }
        }
    }
}
