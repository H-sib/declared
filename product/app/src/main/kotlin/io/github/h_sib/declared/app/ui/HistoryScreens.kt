@file:OptIn(ExperimentalLayoutApi::class)

package io.github.h_sib.declared.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.h_sib.declared.core.flow.Event
import io.github.h_sib.declared.core.ledger.History
import io.github.h_sib.declared.core.ledger.HistoryState
import io.github.h_sib.declared.core.ledger.HistoryWords
import io.github.h_sib.declared.core.ledger.LedgerEvents
import io.github.h_sib.declared.core.ledger.Severity
import io.github.h_sib.declared.core.ledger.TimelineRow
import io.github.h_sib.declared.core.ledger.Tripwires
import io.github.h_sib.declared.core.ledger.Verdict
import io.github.h_sib.declared.core.ledger.Verifying
import io.github.h_sib.declared.core.report.Describe

private fun Verdict.toStatus(): Status = when (this) {
    Verdict.NothingSeen -> Status.CLEAR
    Verdict.FingerprintAdded, Verdict.LockRemoved, is Verdict.CannotTell -> Status.LOOK
    is Verdict.NotPossible, Verdict.NotSetUpYet -> Status.UNKNOWN
}

private fun Verdict.short(): String = when (this) {
    Verdict.NothingSeen -> "Nothing seen"
    Verdict.FingerprintAdded -> "Fingerprint added"
    Verdict.LockRemoved -> "Screen lock removed"
    is Verdict.CannotTell -> "Cannot tell"
    is Verdict.NotPossible -> "Not possible here"
    Verdict.NotSetUpYet -> "Setting up"
}

/** The History tab: the signed record, the tripwires, a check of the record, and the timeline. The explanations are one tap away. */
@Composable
fun TimelineScreen(history: History, onEvent: (Event) -> Unit, listState: LazyListState = rememberLazyListState()) {
    LazyColumn(state = listState, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { ScreenTitle("History", subtitle = if (history.state == HistoryState.Ready) (if (history.entries == 1) "1 entry" else "${history.entries} entries") + (history.since?.let { ", since ${formatMoment(it)}" } ?: "") else null) }
        when (val s = history.state) {
            HistoryState.Loading -> item { Text("Reading the history...", style = MaterialTheme.typography.bodyMedium) }
            is HistoryState.Damaged -> item {
                Panel {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusPill(Status.RISK, "The history could not be read")
                        Text(s.reason, style = MaterialTheme.typography.bodyMedium)
                        Expandable("What happens next") { Body(HistoryWords.DAMAGED) }
                        Button(onClick = { onEvent(Event.StartNewHistory) }, Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(12.dp)) { Text("Start a new history") }
                    }
                }
            }
            is HistoryState.Unavailable -> item { StatusPill(Status.UNKNOWN, "The history could not be kept: ${s.reason}") }
            HistoryState.Ready -> readyItems(history, onEvent)
        }
    }
}

private fun LazyListScope.readyItems(history: History, onEvent: (Event) -> Unit) {
    history.head?.let { head ->
        item {
            Panel {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    HashGlyphView(head.hash, Modifier.size(48.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Latest entry ${head.seq}", style = MaterialTheme.typography.titleSmall)
                        SelectionContainer { Text(head.hash.chunked(8).take(4).joinToString(" "), style = MaterialTheme.typography.bodySmall.copy(fontFamily = DeclaredMono), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Text(formatMoment(head.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Expandable("About this history", Modifier.padding(horizontal = 16.dp)) {
                    Body(HistoryWords.INTRO, decode = false)
                    Body(HistoryWords.NOT_WATCHING, decode = false)
                    Body(HistoryWords.key(history.keyHeld), decode = false)
                    Decoder(listOf(HistoryWords.INTRO, HistoryWords.NOT_WATCHING, HistoryWords.key(history.keyHeld)), max = 4)
                    Text("The full fingerprint, to write down: " + head.hash.chunked(8).joinToString(" "), style = MaterialTheme.typography.bodySmall.copy(fontFamily = DeclaredMono))
                }
            }
        }
    }
    item {
        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Tripwires")
                val t = history.tripwire
                if (t == null) {
                    StatusPill(Status.UNKNOWN, "Not checked yet")
                } else {
                    StatusPill(t.verdict.toStatus(), t.verdict.short())
                    Body(Tripwires.words(t.verdict))
                }
                history.lastTripwire?.let {
                    Text("${HistoryWords.LAST_TRIPWIRE} ${LedgerEvents.words(it.code)} (${formatMoment(it.at)})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Expandable("What a tripwire is") {
                    Body(HistoryWords.TRIPWIRE_EXPLAIN)
                    t?.notPossible?.forEach { (_, why) -> Text("Not possible: $why.", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
    item {
        Panel {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectionLabel("Check the history")
                when (val v = history.verifying) {
                    Verifying.NotRun -> Body(HistoryWords.VERIFY_IDLE)
                    Verifying.Running -> Text("Checking...", style = MaterialTheme.typography.bodyMedium)
                    is Verifying.Done -> {
                        StatusPill(if (v.result.authentic) Status.CLEAR else if (v.result.intact) Status.UNKNOWN else Status.RISK, if (v.result.authentic) "Holds together, signed" else if (v.result.intact) "Holds together, not fully signed" else "Changed after it was written")
                        Body(HistoryWords.summary(v.result))
                        Text("Checked ${formatMoment(v.at)}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        for (p in v.result.problems.take(5)) Text((if (p.severity == Severity.BROKEN) "Changed: " else "Note: ") + p.text, style = MaterialTheme.typography.bodySmall)
                        Expandable("What was checked") { Bullets(v.result.checked, Status.CLEAR) }
                        Expandable("What this cannot see") { Bullets(v.result.notChecked, Status.UNKNOWN) }
                    }
                }
                if (history.verifying != Verifying.Running) OutlinedButton(onClick = { onEvent(Event.VerifyHistory) }) { Text("Check the history now") }
            }
        }
    }
    item { SectionLabel("Timeline", Modifier.padding(top = 8.dp)) }
    if (history.timeline.isEmpty()) item { Text("Nothing here yet. Your first check starts the story.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    items(history.timeline, key = { row -> if (row is TimelineRow.Recorded) "r${row.seq}" else "g${(row as TimelineRow.Gap).from}" }) { row ->
        when (row) {
            is TimelineRow.Gap -> Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusMarker(Status.UNKNOWN, Modifier.size(12.dp))
                Text(HistoryWords.gap(row), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            is TimelineRow.Recorded -> RecordedRow(row)
        }
    }
}

@Composable
private fun RecordedRow(r: TimelineRow.Recorded) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${formatMoment(r.at)}, ${HistoryWords.how(r.taken)}", style = MaterialTheme.typography.titleSmall)
        if (r.isStart) Text("The history began here: ${r.rawChanges} facts noted as the starting point.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (e in r.events) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            StatusMarker(Status.LOOK, Modifier.padding(top = 4.dp).size(10.dp))
            Text(LedgerEvents.words(e), style = MaterialTheme.typography.bodyMedium)
        }
        for (i in r.items) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            StatusMarker(i.attention.toStatus(), Modifier.padding(top = 4.dp).size(10.dp))
            Text("${Describe.attention(i.attention)}: ${i.title}", style = MaterialTheme.typography.bodyMedium)
        }
        if (!r.isStart && r.items.isEmpty() && r.events.isEmpty()) {
            Text(if (r.rawChanges == 0) "Looked, nothing had changed." else "${r.rawChanges} details differed, none that matter.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
