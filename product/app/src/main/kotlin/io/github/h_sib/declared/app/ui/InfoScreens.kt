package io.github.h_sib.declared.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.h_sib.declared.core.blindspots.BlindSpot
import io.github.h_sib.declared.core.blindspots.BlindSpotKind
import io.github.h_sib.declared.core.blindspots.BlindSpots
import io.github.h_sib.declared.core.live.LiveLog
import io.github.h_sib.declared.core.live.Watched
import io.github.h_sib.declared.core.report.Report
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault())

/**
 * The map of what this app cannot see (DESIGN.md section 7). A security tool that hides its limits teaches people to trust it too much, so every
 * limit is listed; each opens on its own, so the screen is a list of places and not a wall of text.
 */
@Composable
fun BlindSpotsScreen(report: Report) {
    val spots = remember(report) { BlindSpots.of(report) }
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp)) {
        item { ScreenTitle("What this app cannot see", subtitle = "A quiet screen never means a clear phone.") }
        item { SectionLabel("On this phone", Modifier.padding(top = 16.dp)) }
        items(spots.filter { it.kind == BlindSpotKind.THIS_PHONE }, key = { it.id }) { SpotRow(it) }
        item { SectionLabel("On every phone", Modifier.padding(top = 20.dp)) }
        items(spots.filter { it.kind == BlindSpotKind.ALWAYS }, key = { it.id }) { SpotRow(it) }
    }
}

@Composable
private fun SpotRow(spot: BlindSpot) {
    val first = spot.text.substringBefore(". ").let { if (it.endsWith(".")) it else "$it." }
    Expandable(spot.title, summary = first) {
        Body(spot.text)
        spot.measured?.let { FactRow("Evidence", it) }
    }
}

/**
 * What the platform announced while the app was on screen. What was heard comes first; what this view can and cannot do (the measured delivery
 * times and the limits) is one tap away, in full, because a live view that over-promises is worse than none.
 */
@Composable
fun LiveViewScreen(live: LiveLog) {
    LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ScreenTitle("Live view")
                if (live.watching) StatusPill(Status.NOTE, "Watching while this app is on screen") else StatusPill(Status.UNKNOWN, "Not watching right now")
            }
        }
        item { SectionLabel("Heard since the app was opened", Modifier.padding(top = 16.dp)) }
        if (live.events.isEmpty()) {
            item { Text("Nothing yet. Change a setting or install an app and it appears here.", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        items(live.events, key = { it.at.toString() + it.what }) { e ->
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(e.what, style = MaterialTheme.typography.titleSmall)
                Text("${clock.format(e.at)}, ${e.source.label.lowercase()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Expandable("What this view can and cannot do", Modifier.padding(top = 16.dp)) {
                val reread = "After each announcement the phone is read again, and anything that is different shows in the Check tab."
                for (t in listOf(reread, Watched.MEASURED, Watched.LIMIT_BACKGROUND, Watched.LIMIT_HIDDEN_APPS, Watched.REREAD_NOTE)) Body(t, decode = false)
                Decoder(listOf(reread, Watched.MEASURED, Watched.LIMIT_BACKGROUND, Watched.LIMIT_HIDDEN_APPS, Watched.REREAD_NOTE), max = 4)
            }
        }
    }
}
