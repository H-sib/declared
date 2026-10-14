package io.github.h_sib.declared.core.flow

import io.github.h_sib.declared.core.ledger.History
import io.github.h_sib.declared.core.ledger.Verification
import io.github.h_sib.declared.core.ledger.Verifying
import io.github.h_sib.declared.core.live.LiveEvent
import io.github.h_sib.declared.core.live.LiveLog
import io.github.h_sib.declared.core.model.Declarations
import io.github.h_sib.declared.core.report.Holder
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.state.Answer
import io.github.h_sib.declared.core.state.ChangeSummary
import io.github.h_sib.declared.core.state.Changes
import io.github.h_sib.declared.core.state.Review
import io.github.h_sib.declared.core.state.SavedState
import io.github.h_sib.declared.core.state.SnapshotBuilder
import io.github.h_sib.declared.core.state.StoreResult
import java.time.Instant

/**
 * Navigation, loading and review state as a pure reducer: `(state, event) -> state` (DESIGN.md section 8).
 * It lives in core, not in the app module, because it has no Android type in it and a property test can
 * then run thousands of event sequences on the JVM in milliseconds. Saving to disk is the one side effect and
 * it is the app's job: it persists whenever `saved` changes.
 */
sealed interface Screen {
    /** The home screen: one statement and the doors. The detailed result is [Check]. */
    data object Inventory : Screen

    /** The detailed result of looking at the phone: findings, notes, the apps. The first door on the home screen. */
    data object Check : Screen

    /** The last tab: everything that is not one of the three main places. */
    data object More : Screen

    /** Every app this app can see, grouped by where it came from. */
    data object Apps : Screen

    data class FindingDetail(val findingId: String) : Screen

    /** The explanation shown once, before the first baseline review. */
    data object FirstRun : Screen

    /** The baseline review. `first` is true when it is the one that creates the baseline. */
    data class ReviewHolders(val first: Boolean) : Screen

    data object WhatChanged : Screen

    /** What this app cannot see, on every phone and on this one. */
    data object BlindSpots : Screen

    /** What the platform announced while the app was on screen. */
    data object LiveView : Screen

    /** The attack paths this app can see, what is held open, and the smallest set of changes (STAGE2_DESIGN.md section 7). */
    data object Paths : Screen

    /** The few things only the person can say (STAGE2_DESIGN.md section 5). */
    data object AboutYou : Screen

    /** The drift timeline, the tripwires and the check of the history itself (STAGE3_DESIGN.md). */
    data object Timeline : Screen
}

sealed interface Load {
    data object Loading : Load

    /** [report] already includes the findings that come from the user's own review (see `Review.apply`). */
    data class Ready(val report: Report) : Load

    data class Failed(val reason: String) : Load
}

/** Where the saved baseline stands. Corrupt and Unavailable are different problems and are told apart on screen. */
sealed interface StoreStatus {
    data object Loading : StoreStatus

    /** Resolved: either a saved state was found ([AppState.saved]) or this is a first run. */
    data object Ready : StoreStatus

    data class Corrupt(val reason: String) : StoreStatus

    data class Unavailable(val reason: String) : StoreStatus
}

data class AppState(
    val load: Load = Load.Loading,
    /** Never empty. The bottom entry is the Inventory, or the first-run screens while no baseline exists. */
    val stack: List<Screen> = listOf(Screen.Inventory),
    val refreshing: Boolean = false,
    /** Set when a refresh failed but an older report is still on screen. The old report stays visible, labelled as old. */
    val refreshError: String? = null,
    val store: StoreStatus = StoreStatus.Loading,
    val saved: SavedState? = null,
    /** Answers given in the review that is open now and not yet saved. */
    val draft: Map<String, Answer> = emptyMap(),
    val saveError: String? = null,
    /** The report as the sensors gave it, before the user's own review findings are added. */
    val raw: Report? = null,
    /** What changed since the user last acknowledged. Null until both the report and the saved state are known. */
    val changes: ChangeSummary? = null,
    /** What the live watcher heard. Kept in memory only, and only while the app is on screen. */
    val live: LiveLog = LiveLog(),
    /** Changes the person is previewing on the paths screen. Never saved, and cleared whenever that screen is left. */
    val whatIf: Set<String> = emptySet(),
    /** What the history holds, as the screens show it. Kept encrypted on the phone; this is only the view of it. */
    val history: History = History.LOADING,
) {
    val screen: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    val report: Report? get() = (load as? Load.Ready)?.report

    /** What the person has said about their own life; the empty answers until a baseline exists. */
    val declarations: Declarations get() = saved?.declarations ?: Declarations()

    /** Holders the user has not yet been asked about (everything at first, later only what appeared since). */
    val pending: List<Holder>
        get() = raw?.let { Review.pending(it, saved) }.orEmpty()
}

sealed interface Event {
    data class ReportLoaded(val report: Report) : Event

    data class LoadFailed(val reason: String) : Event

    data class StoreLoaded(val result: StoreResult) : Event

    data class SaveFailed(val reason: String) : Event

    data class OpenFinding(val findingId: String) : Event

    data object OpenApps : Event

    data object OpenWhatChanged : Event

    /** Open the review from the inventory (to answer for things that appeared since the last one). */
    data object OpenReview : Event

    data object OpenBlindSpots : Event

    data object OpenLiveView : Event

    data object OpenPaths : Event

    /** The first door: the detailed result. */
    data object OpenCheck : Event

    /** The tab with the rarer places: all apps, what the app cannot see, About you, Live view. */
    data object OpenMore : Event

    /** Back to the home screen from any tab. */
    data object GoHome : Event

    data object OpenAboutYou : Event

    data object OpenTimeline : Event

    /** The history changed (an entry was added, the file was read, a tripwire was checked). */
    data class HistoryUpdated(val history: History) : Event

    /** Check the history itself: its hashes, replay and signatures. The check runs off the main thread and ends in [HistoryVerified]. */
    data object VerifyHistory : Event

    /** The history file is damaged: set it aside and begin a new one. Never done without being asked. */
    data object StartNewHistory : Event

    data class HistoryVerified(val result: Verification, val at: java.time.Instant) : Event

    /** The person answered the questions on the "About you" screen. Saved with the baseline. */
    data class SetDeclarations(val declarations: Declarations) : Event

    /** Preview (or stop previewing) a change on the paths screen. */
    data class ToggleLever(val id: String) : Event

    data object ClearWhatIf : Event

    /** The watcher started or stopped (the app came on screen or left it). */
    data class WatchingChanged(val watching: Boolean) : Event

    data class LiveSeen(val event: LiveEvent) : Event

    /** From the first-run explanation into the first review. */
    data object StartReview : Event

    data class AnswerHolder(val key: String, val answer: Answer) : Event

    /** Save the answers. `at` is passed in so the reducer never reads a clock. */
    data class FinishReview(val at: Instant) : Event

    /** "I have seen these changes": measure from now on. */
    data object AcknowledgeChanges : Event

    data object Back : Event

    data object Refresh : Event
}

fun reduce(state: AppState, event: Event): AppState = settle(step(state, event))

/** While the first review is pending nothing else can be opened: the baseline comes before anything else on screen. */
/** The main places replace one another: from any of them the person is one step from the home screen, never in a pile of tabs. */
private fun tab(state: AppState, screen: Screen): AppState =
    if (!state.gated && state.report != null && state.screen != screen) state.copy(stack = listOf(Screen.Inventory, screen)) else state

private val AppState.gated: Boolean get() = stack.first() == Screen.FirstRun || stack.first() == Screen.ReviewHolders(first = true)

private fun step(state: AppState, event: Event): AppState = when (event) {
    is Event.ReportLoaded -> state.copy(raw = event.report, refreshing = false, refreshError = null)

    is Event.LoadFailed -> when (state.load) {
        is Load.Ready -> state.copy(refreshing = false, refreshError = event.reason)
        else -> state.copy(load = Load.Failed(event.reason), refreshing = false)
    }

    is Event.StoreLoaded -> when (val r = event.result) {
        StoreResult.Empty -> state.copy(store = StoreStatus.Ready, saved = null)
        is StoreResult.Loaded -> state.copy(store = StoreStatus.Ready, saved = r.saved)
        is StoreResult.Corrupt -> state.copy(store = StoreStatus.Corrupt(r.reason), saved = null)
        is StoreResult.Unavailable -> state.copy(store = StoreStatus.Unavailable(r.reason), saved = null)
    }

    is Event.SaveFailed -> state.copy(saveError = event.reason)

    is Event.OpenFinding -> {
        val known = !state.gated && state.report?.finding(event.findingId) != null
        val alreadyThere = (state.screen as? Screen.FindingDetail)?.findingId == event.findingId
        if (known && !alreadyThere) state.copy(stack = state.stack + Screen.FindingDetail(event.findingId)) else state
    }

    Event.OpenApps -> if (!state.gated && state.report != null && state.screen != Screen.Apps) state.copy(stack = state.stack + Screen.Apps) else state

    Event.OpenWhatChanged ->
        if (!state.gated && state.saved != null && state.screen != Screen.WhatChanged) state.copy(stack = state.stack + Screen.WhatChanged) else state

    Event.OpenBlindSpots -> if (!state.gated && state.report != null && state.screen != Screen.BlindSpots) state.copy(stack = state.stack + Screen.BlindSpots) else state

    Event.OpenLiveView -> if (!state.gated && state.report != null && state.screen != Screen.LiveView) state.copy(stack = state.stack + Screen.LiveView) else state

    Event.OpenCheck -> tab(state, Screen.Check)
    Event.OpenMore -> tab(state, Screen.More)
    Event.GoHome -> if (!state.gated && state.stack.size > 1) state.copy(stack = listOf(Screen.Inventory)) else state
    Event.OpenPaths -> tab(state, Screen.Paths)

    Event.OpenTimeline -> tab(state, Screen.Timeline)

    is Event.HistoryUpdated -> state.copy(history = event.history)

    Event.VerifyHistory ->
        if (state.history.entries > 0 && state.history.verifying != Verifying.Running) state.copy(history = state.history.copy(verifying = Verifying.Running)) else state

    Event.StartNewHistory -> if (state.history.state is io.github.h_sib.declared.core.ledger.HistoryState.Damaged) state.copy(history = History.LOADING.copy(state = io.github.h_sib.declared.core.ledger.HistoryState.Ready)) else state

    is Event.HistoryVerified -> state.copy(history = state.history.copy(verifying = Verifying.Done(event.result, event.at)))

    Event.OpenAboutYou -> if (!state.gated && state.saved != null && state.screen != Screen.AboutYou) state.copy(stack = state.stack + Screen.AboutYou) else state

    is Event.SetDeclarations -> state.saved?.let { state.copy(saved = it.copy(declarations = event.declarations), saveError = null) } ?: state

    is Event.ToggleLever -> if (state.screen == Screen.Paths) state.copy(whatIf = if (event.id in state.whatIf) state.whatIf - event.id else state.whatIf + event.id) else state

    Event.ClearWhatIf -> state.copy(whatIf = emptySet())

    is Event.WatchingChanged -> state.copy(live = state.live.copy(watching = event.watching))

    is Event.LiveSeen -> state.copy(live = state.live.record(event.event))

    Event.OpenReview ->
        if (!state.gated && state.raw != null && state.saved != null && state.screen !is Screen.ReviewHolders) {
            state.copy(stack = state.stack + Screen.ReviewHolders(first = false), draft = emptyMap())
        } else {
            state
        }

    Event.StartReview -> if (state.screen == Screen.FirstRun) state.copy(stack = listOf(Screen.ReviewHolders(first = true)), draft = emptyMap()) else state

    is Event.AnswerHolder ->
        // Only holders that are really up for review can be answered; anything else is ignored rather than stored.
        if (state.screen is Screen.ReviewHolders && state.pending.any { Review.key(it) == event.key }) {
            state.copy(draft = state.draft + (event.key to event.answer))
        } else {
            state
        }

    is Event.FinishReview -> {
        val raw = state.raw
        val screen = state.screen
        if (raw == null || screen !is Screen.ReviewHolders) {
            state
        } else {
            val saved = Review.establish(raw, state.draft, state.saved, event.at)
            state.copy(
                saved = saved,
                draft = emptyMap(),
                saveError = null,
                stack = if (screen.first) listOf(Screen.Inventory) else state.stack.dropLast(1),
            )
        }
    }

    Event.AcknowledgeChanges -> {
        val raw = state.raw
        val saved = state.saved
        if (raw == null || saved == null) state else state.copy(saved = Review.acknowledge(saved, raw), saveError = null)
    }

    Event.Back -> if (state.canGoBack) state.copy(stack = state.stack.dropLast(1)) else state

    Event.Refresh -> when {
        state.refreshing || state.load is Load.Loading -> state
        state.load is Load.Failed -> state.copy(load = Load.Loading, refreshError = null)
        else -> state.copy(refreshing = true, refreshError = null)
    }
}

/**
 * The part of the state that follows from the rest: the report with the user's own findings, the changes since the last
 * acknowledgement, the first-run gate, and a navigation stack that never points at something that is gone.
 */
private fun settle(s: AppState): AppState {
    val raw = s.raw
    val report = raw?.let { Review.apply(it, s.saved, it.takenAt) }
    val load = when {
        report != null -> Load.Ready(report)
        else -> s.load
    }
    val changes = if (raw != null && s.saved != null) Changes.summarize(s.saved.reviewed, SnapshotBuilder.of(raw)) else null

    var stack = s.stack.filter { screen ->
        when (screen) {
            is Screen.FindingDetail -> report?.finding(screen.findingId) != null
            Screen.WhatChanged, Screen.AboutYou -> s.saved != null
            else -> true
        }
    }
    // First-run gate: a resolved store with nothing saved means the first review comes before anything else.
    val firstRunNeeded = report != null && s.saved == null && (s.store == StoreStatus.Ready || s.store is StoreStatus.Corrupt)
    if (firstRunNeeded && stack.none { it == Screen.FirstRun || it == Screen.ReviewHolders(first = true) }) stack = listOf(Screen.FirstRun)
    // Once a baseline exists the first-run screens have no place in the stack.
    if (s.saved != null) stack = stack.filter { it != Screen.FirstRun && it != Screen.ReviewHolders(first = true) }
    if (stack.isEmpty()) stack = listOf(Screen.Inventory)
    // The bottom is always the Inventory, or the first-run screens while no baseline exists.
    val bottom = stack.first()
    if (bottom != Screen.Inventory && bottom != Screen.FirstRun && bottom != Screen.ReviewHolders(first = true)) stack = listOf(Screen.Inventory) + stack

    // An answer to something that is no longer up for review (it was answered elsewhere, or it went away) is dropped, not kept.
    val pendingKeys = raw?.let { r -> Review.pending(r, s.saved).map(Review::key).toSet() }.orEmpty()
    val draft = s.draft.filterKeys { it in pendingKeys }

    // A preview belongs to the paths screen: leaving it forgets it, so nothing hypothetical is ever shown as real.
    val whatIf = if (stack.last() == Screen.Paths) s.whatIf else emptySet()

    return s.copy(load = load, changes = changes, stack = stack, draft = draft, whatIf = whatIf)
}
