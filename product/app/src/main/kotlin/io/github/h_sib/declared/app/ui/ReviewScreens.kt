@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package io.github.h_sib.declared.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.h_sib.declared.core.flow.StoreStatus
import io.github.h_sib.declared.core.home.Area
import io.github.h_sib.declared.core.home.AreaKind
import io.github.h_sib.declared.core.home.AreaStatus
import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.report.Holder
import io.github.h_sib.declared.core.report.Describe
import io.github.h_sib.declared.core.state.Answer
import io.github.h_sib.declared.core.state.ChangeSummary
import io.github.h_sib.declared.core.state.Review
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The first launch: three short pages, with a skip. What it is, why it can be trusted, and the one thing to do. Nothing is read from the phone
 * until the person starts the review, so the picture of the phone here says "not read yet" on every tile and does not pretend otherwise.
 */
@Composable
fun FirstRunScreen(store: StoreStatus, onStart: () -> Unit) {
    val pager = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == 2
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeclaredMark(Modifier.size(22.dp))
                Text("Declared", style = MaterialTheme.typography.labelLarge)
            }
            if (!last) TextButton(onClick = { scope.launch { pager.animateScrollToPage(2) } }) { Text("Skip") } else Spacer(Modifier.height(48.dp))
        }
        HorizontalPager(pager, Modifier.weight(1f)) { page ->
            // Scrolls: at large text sizes the content would otherwise be pushed off the screen.
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when (page) {
                    0 -> {
                        val tiles = AreaKind.entries.map { Area(it, it.name.lowercase().replaceFirstChar { c -> c.uppercase() }, AreaStatus.UNKNOWN, "Not read yet.") }
                        DeviceMap(tiles) { }
                        Spacer(Modifier.height(20.dp))
                        OnboardingText("See what your phone allows", "Which apps hold powerful abilities, and what changes.")
                    }
                    1 -> {
                        Spacer(Modifier.height(24.dp))
                        Panel {
                            Column(Modifier.padding(horizontal = 16.dp)) {
                                for (line in listOf("No internet permission", "Kept encrypted on this phone", "No scores, no guesses")) {
                                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                        StatusMarker(Status.CLEAR, Modifier.size(16.dp))
                                        Text(line, style = MaterialTheme.typography.titleSmall)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        OnboardingText("Private by design", "Nothing leaves this phone.")
                    }
                    else -> {
                        Spacer(Modifier.height(40.dp))
                        DeclaredMark(Modifier.size(96.dp))
                        Spacer(Modifier.height(32.dp))
                        OnboardingText("Start with one review", "Say which powerful apps you recognize. Declared remembers.")
                    }
                }
            }
        }
        if (store is StoreStatus.Corrupt) {
            Text("A saved review exists but could not be read, so a new one is needed.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        ViewModeSwitch(Modifier.padding(top = 8.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
            for (i in 0 until 3) {
                Box(Modifier.padding(horizontal = 4.dp).size(if (i == pager.currentPage) 22.dp else 8.dp, 8.dp).clip(CircleShape)
                    .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
            }
        }
        Button(
            onClick = { if (last) onStart() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            Modifier.fillMaxWidth().heightIn(min = 54.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text(if (last) "Start the review" else "Next", style = MaterialTheme.typography.labelLarge) }
    }
}

@Composable
private fun OnboardingText(title: String, line: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
fun ReviewScreen(first: Boolean, pending: List<Holder>, draft: Map<String, Answer>, onAnswer: (String, Answer) -> Unit, onFinish: () -> Unit) {
    // One card per app: an app that holds several things is asked about once, and the answer is for all of them.
    val apps = remember(pending) { Review.groupByApp(pending, { it.pkg }, { it.label }) }
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            ScreenTitle(
                if (first) "Quick review" else "New to review",
                subtitle = if (apps.isEmpty()) "Nothing powerful was found outside the phone's own software." else "${apps.size} ${if (apps.size == 1) "app" else "apps"} to answer. Unanswered is saved as not sure.",
            )
        }
        items(apps, key = { it.pkg }) { app -> ReviewCard(app, draft, onAnswer) }
        item { Button(onClick = onFinish, Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(14.dp)) { Text(if (first) "Finish the review" else "Save my answers") } }
    }
}

@Composable
private fun ReviewCard(app: Review.AppGroup<Holder>, draft: Map<String, Answer>, onAnswer: (String, Answer) -> Unit) {
    val chosen = app.items.map { draft[Review.key(it)] }.distinct().singleOrNull()
    val kinds = app.items.map { it.kind }.distinct()
    Panel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in kinds) InfoChip(k.shortName.replaceFirstChar { it.uppercase() })
                InfoChip(Describe.tier(app.items.first().tier.tier))
            }
            Expandable("Where it came from, what it can do") {
                FactRow("Came from", app.items.first().tier.reason)
                for (h in app.items) if (h.powers.powers.isNotEmpty()) FactRow(h.kind.shortName, h.powers.powers.joinToString("\n") { it.plain })
                Decoder(kinds.map { it.shortName } + app.items.flatMap { h -> h.powers.powers.map { it.plain } })
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, label) in listOf(Answer.RECOGNIZED to "I recognize this", Answer.NOT_RECOGNIZED to "I do not recognize this", Answer.UNSURE to "Not sure")) {
                    FilterChip(selected = chosen == value, onClick = { for (h in app.items) onAnswer(Review.key(h), value) }, label = { Text(label) })
                }
            }
        }
    }
}

@Composable
fun WhatChangedScreen(changes: ChangeSummary, since: Instant, onAcknowledge: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { ScreenTitle("What changed", subtitle = "Since ${formatMoment(since)}") }
        if (changes.items.isEmpty()) item { Text("Nothing that this app would mention.", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium) }
        for (attention in listOf(Attention.LOOK_FIRST, Attention.LOOK, Attention.NOTE)) {
            val group = changes.items.filter { it.attention == attention }
            if (group.isEmpty()) continue
            item { SectionLabel(Describe.attention(attention), Modifier.padding(top = 16.dp)) }
            items(group, key = { it.kind.name + it.keys.joinToString("|") + it.title }) { ListRow(it.title, status = attention.toStatus()) }
        }
        item {
            Expandable("About this list", Modifier.padding(top = 12.dp)) {
                Body("${changes.rawChanges} records differ underneath. Built-in apps and settings that did not matter are counted there but not listed. This list stays until you press the button.")
            }
        }
        item { Button(onClick = onAcknowledge, Modifier.fillMaxWidth().heightIn(min = 54.dp), shape = RoundedCornerShape(14.dp)) { Text("I have seen these changes") } }
    }
}
