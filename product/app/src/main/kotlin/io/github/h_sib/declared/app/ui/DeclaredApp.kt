package io.github.h_sib.declared.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.h_sib.declared.core.flow.AppState
import io.github.h_sib.declared.core.flow.Event
import io.github.h_sib.declared.core.flow.Load
import io.github.h_sib.declared.core.flow.Screen
import io.github.h_sib.declared.core.model.SettingsLink
import io.github.h_sib.declared.core.paths.PathsAnalysis
import io.github.h_sib.declared.core.report.Report

@Composable
fun DeclaredApp(
    state: AppState,
    onEvent: (Event) -> Unit,
    onOpenSettings: (SettingsLink) -> Unit,
) {
    val prefs = LocalUiPrefs.current
    var guideOpen by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        AppScaffold(state, onEvent, onOpenSettings, onOpenGuide = { guideOpen = true })
        if (guideOpen) GuideScreen(onClose = { guideOpen = false; prefs.markGuideSeen() })
        if (prefs.introOpen) IntroScreen(onDone = prefs::closeIntro)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(
    state: AppState,
    onEvent: (Event) -> Unit,
    onOpenSettings: (SettingsLink) -> Unit,
    onOpenGuide: () -> Unit,
) {
    BackHandler(enabled = state.canGoBack) { onEvent(Event.Back) }

    Scaffold(
        topBar = {
            if (state.screen !in Tabs && state.screen != Screen.FirstRun) TopAppBar(
                title = {
                    Text(
                        when (state.screen) {
                            is Screen.FindingDetail -> "Details"
                            Screen.Apps -> "Apps"
                            Screen.Inventory -> "Declared"
                            Screen.Check -> "Check"
                            Screen.More -> "More"
                            Screen.FirstRun -> "Welcome"
                            is Screen.ReviewHolders -> "Review"
                            Screen.WhatChanged -> "What changed"
                            Screen.BlindSpots -> "What I cannot see"
                            Screen.LiveView -> "Live view"
                            Screen.Paths -> "Attack paths"
                            Screen.AboutYou -> "About you"
                            Screen.Timeline -> "History"
                        },
                    )
                },
                navigationIcon = {
                    if (state.canGoBack) IconButton(onClick = { onEvent(Event.Back) }) { BackArrow(description = "Back") }
                },
            )
        },
        bottomBar = { if (state.screen in Tabs && state.load is Load.Ready) DeclaredBottomBar(state.screen, onEvent, LocalUiPrefs.current.easy) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val load = state.load) {
                Load.Loading -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(Modifier.padding(top = 48.dp))
                    Text("Reading this phone...", Modifier.padding(16.dp))
                }

                is Load.Failed -> Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Text("This app could not read the phone.", style = MaterialTheme.typography.titleMedium)
                    Text(load.reason, Modifier.padding(top = 8.dp))
                    Text(
                        "That is a failure of this app, not a sign that the phone is fine. Nothing is shown as clear when it could not be read.",
                        Modifier.padding(top = 8.dp),
                    )
                    TextButton(onClick = { onEvent(Event.Refresh) }) { Text("Try again") }
                }

                is Load.Ready -> {
                    // Worked out once per reading of the phone and per answer, and shared by the first screen's card and the paths screen.
                    val analysis = remember(load.report.takenAt, state.saved?.answers, state.declarations) {
                        PathsAnalysis.of(load.report, state.saved, state.declarations)
                    }
                    ReadyContent(state, load.report, analysis, onEvent, onOpenSettings, onOpenGuide)
                }
            }
        }
    }
}

@Composable
private fun ReadyContent(
    state: AppState,
    report: Report,
    analysis: PathsAnalysis,
    onEvent: (Event) -> Unit,
    onOpenSettings: (SettingsLink) -> Unit,
    onOpenGuide: () -> Unit,
) {
    val easy = LocalUiPrefs.current.easy
    // Each tab's place is kept here, above the screens, so opening a finding and coming back finds the list where it was left.
    val homeScroll = rememberScrollState()
    val moreScroll = rememberScrollState()
    val checkList = rememberLazyListState()
    val pathsList = rememberLazyListState()
    val timelineList = rememberLazyListState()
    val checkUi = rememberSaveable(saver = CheckUi.Saver) { CheckUi() }
    when (val screen = state.screen) {
        Screen.Inventory -> if (easy) EasyHomeScreen(state, report, onEvent, onOpenGuide, homeScroll) else HomeScreen(state, report, analysis.view, onEvent, homeScroll, onOpenGuide)
        Screen.Check -> if (easy) EasyCheckScreen(state, report, checkUi, checkList, onEvent) else InventoryScreen(state, report, analysis.view, checkUi, checkList, onEvent)
        Screen.More -> MoreScreen(state, onEvent, moreScroll, onOpenGuide)
        Screen.Apps -> AppsScreen(report)
        Screen.BlindSpots -> BlindSpotsScreen(report)
        Screen.LiveView -> LiveViewScreen(state.live)
        Screen.Paths -> PathsScreen(analysis, state.whatIf, onEvent, onOpenSettings, pathsList)
        Screen.AboutYou -> AboutYouScreen(state.declarations, onEvent)
        Screen.Timeline -> TimelineScreen(state.history, onEvent, timelineList)
        Screen.FirstRun -> FirstRunScreen(state.store) { onEvent(Event.StartReview) }
        is Screen.ReviewHolders -> ReviewScreen(
            first = screen.first,
            pending = state.pending,
            draft = state.draft,
            onAnswer = { key, answer -> onEvent(Event.AnswerHolder(key, answer)) },
            onFinish = { onEvent(Event.FinishReview(java.time.Instant.now())) },
        )
        Screen.WhatChanged -> state.changes?.let { changes ->
            WhatChangedScreen(changes, state.saved?.reviewed?.takenAt ?: report.takenAt) { onEvent(Event.AcknowledgeChanges) }
        }
        is Screen.FindingDetail -> {
            val finding = report.finding(screen.findingId)
            if (finding != null) {
                if (easy) EasyFindingDetail(finding, onOpenSettings) else FindingDetailScreen(finding, onOpenSettings)
            }
        }
    }
}

/** The main places. The home screen is the bottom of the stack; the other four replace one another, so the bar always tells where the person is. */
private val Tabs = setOf(Screen.Inventory, Screen.Check, Screen.Paths, Screen.Timeline, Screen.More)

@Composable
private fun DeclaredBottomBar(current: Screen, onEvent: (Event) -> Unit, easy: Boolean) {
    // The simple view has three places: Home, the to-do list (the Check tab), and Help (the More tab).
    val items = if (easy) {
        listOf(
            Triple(Tab.Home to "Home", Screen.Inventory, Event.GoHome),
            Triple(Tab.Check to "To do", Screen.Check, Event.OpenCheck),
            Triple(Tab.More to "Help", Screen.More, Event.OpenMore),
        )
    } else {
        listOf(
            Triple(Tab.Home to "Home", Screen.Inventory, Event.GoHome),
            Triple(Tab.Check to "Check", Screen.Check, Event.OpenCheck),
            Triple(Tab.Paths to "Paths", Screen.Paths, Event.OpenPaths),
            Triple(Tab.History to "History", Screen.Timeline, Event.OpenTimeline),
            Triple(Tab.More to "More", Screen.More, Event.OpenMore),
        )
    }
    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        for ((tabAndLabel, screen, event) in items) {
            val (tab, label) = tabAndLabel
            val selected = current == screen
            NavigationBarItem(
                selected = selected,
                onClick = { onEvent(event) },
                icon = { TabIcon(tab, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
