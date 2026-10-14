package io.github.h_sib.declared.app

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.h_sib.declared.core.flow.AppState
import io.github.h_sib.declared.core.flow.Event
import io.github.h_sib.declared.core.flow.Load
import io.github.h_sib.declared.core.flow.reduce
import io.github.h_sib.declared.core.ledger.HeldKey
import io.github.h_sib.declared.core.ledger.History
import io.github.h_sib.declared.core.ledger.HistoryKeeper
import io.github.h_sib.declared.core.ledger.HistoryState
import io.github.h_sib.declared.core.ledger.Ledger
import io.github.h_sib.declared.core.ledger.LedgerLoad
import io.github.h_sib.declared.core.ledger.Taken
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.live.ChangeWatcher
import io.github.h_sib.declared.core.live.LiveEvent
import io.github.h_sib.declared.core.live.LiveSignal
import io.github.h_sib.declared.core.live.Watched
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.state.SavedState
import io.github.h_sib.declared.core.state.StateStore
import io.github.h_sib.declared.sensors.AndroidChangeWatcher
import io.github.h_sib.declared.sensors.AndroidLedgerSigner
import io.github.h_sib.declared.sensors.AndroidTripwireKeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.concurrent.Executors

/**
 * Holds the pure state machine from core and performs the side effects around it: reading the phone, and loading and
 * saving the baseline. All decisions live in `reduce`; this class only starts a read when the reducer says one is wanted
 * and saves whenever the saved state changed.
 */
class ReportViewModel(app: Application) : AndroidViewModel(app) {

    val store: StateStore = EncryptedFileStore(app)

    private val builder = ReportSources.builder(app)
    private val _state = MutableStateFlow(AppState())
    val state: StateFlow<AppState> = _state

    private var loading: Job? = null

    private val watcher: ChangeWatcher = AndroidChangeWatcher(app)
    private var rereadSoon: Job? = null
    private var polling: Job? = null

    /** Set when something changed while a read was running: that read may already be stale, so one more follows it. */
    private var rereadAfterLoad = false

    /** Consecutive failed reads of the phone since the last good one; a refresh that fails while the app is on screen is tried again soon, a few times. */
    private var failedReads = 0

    /** Only the latest saved state matters, so a burst of changes is written as one: conflated, one writer, in order. */
    private val saves = Channel<SavedState>(Channel.CONFLATED)

    // ---- the history (STAGE3_DESIGN.md): everything below runs on one thread, so a step never overlaps another or a check
    private val historyThread = Executors.newSingleThreadExecutor { Thread(it, "declared-history") }.asCoroutineDispatcher()
    private val ledgerStore = EncryptedLedgerStore(app)
    private val signer = AndroidLedgerSigner()
    private val tripwire = AndroidTripwireKeys(app)
    private var ledger: Ledger? = null
    private var historyLoaded = false
    private var historyBlocked = false
    private var firstStepOfProcess = true
    private var reportWaitingForHistory: Report? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            for (saved in saves) {
                val reason = when (val result = store.save(saved)) {
                    is ReadResult.Failed -> result.reason
                    is ReadResult.Refused -> result.reason
                    is ReadResult.Ok -> null
                }
                if (reason != null) dispatch(Event.SaveFailed(reason))
            }
        }
        viewModelScope.launch(Dispatchers.IO) { dispatch(Event.StoreLoaded(store.load())) }
        viewModelScope.launch(historyThread) { loadHistory() }
        startLoad()
    }

    private fun loadHistory() {
        when (val r = ledgerStore.load()) {
            LedgerLoad.Empty -> dispatch(Event.HistoryUpdated(History.LOADING.copy(state = HistoryState.Ready)))
            is LedgerLoad.Loaded -> {
                ledger = r.ledger
                dispatch(Event.HistoryUpdated(History.of(r.ledger, null)))
            }
            is LedgerLoad.Corrupt -> {
                historyBlocked = true
                dispatch(Event.HistoryUpdated(History.damaged(r.reason)))
            }
            is LedgerLoad.Unavailable -> {
                historyBlocked = true
                dispatch(Event.HistoryUpdated(History.unavailable(r.reason)))
            }
        }
        historyLoaded = true
        reportWaitingForHistory?.let { reportWaitingForHistory = null; step(it) }
    }

    /** One turn of keeping the history for a report of the phone. Only once the person has a baseline: the first review comes first. */
    private fun recordHistory(report: Report) {
        viewModelScope.launch(historyThread) {
            if (!historyLoaded) reportWaitingForHistory = report else step(report)
        }
    }

    private fun step(report: Report) {
        if (historyBlocked) return
        val taken = if (firstStepOfProcess) Taken.OPENED else Taken.ON_SCREEN
        val result = HistoryKeeper(signer, tripwire, BuildConfig.VERSION_NAME).step(ledger, report, Instant.now(), taken)
        if (result.appended != null) {
            when (val saved = ledgerStore.save(result.ledger)) {
                is ReadResult.Ok -> ledger = result.ledger
                is ReadResult.Failed -> { dispatch(Event.HistoryUpdated(_state.value.history.copy(state = HistoryState.Unavailable(saved.reason)))); return }
                is ReadResult.Refused -> { dispatch(Event.HistoryUpdated(_state.value.history.copy(state = HistoryState.Unavailable(saved.reason)))); return }
            }
        }
        firstStepOfProcess = false
        dispatch(Event.HistoryUpdated(History.of(ledger ?: result.ledger, result.tripwire, _state.value.history)))
    }

    private fun verifyHistory() {
        viewModelScope.launch(historyThread) {
            val current = ledger
            if (current == null) dispatch(Event.HistoryUpdated(_state.value.history.copy(verifying = io.github.h_sib.declared.core.ledger.Verifying.NotRun)))
            else dispatch(Event.HistoryVerified(current.verify(HeldKey.Spki(signer.publicKeySpki)), Instant.now()))
        }
    }

    private fun startNewHistory() {
        viewModelScope.launch(historyThread) {
            ledgerStore.moveAside()
            ledger = null
            historyBlocked = false
            _state.value.raw?.let { step(it) }
        }
    }

    fun dispatch(event: Event) {
        val before = _state.getAndUpdate { reduce(it, event) }
        val after = reduce(before, event)
        val readWanted = event is Event.Refresh &&
            ((after.refreshing && !before.refreshing) || (after.load is Load.Loading && before.load !is Load.Loading))
        if (readWanted) startLoad()
        if (event is Event.VerifyHistory && after.history.verifying == io.github.h_sib.declared.core.ledger.Verifying.Running && before.history.verifying != io.github.h_sib.declared.core.ledger.Verifying.Running) verifyHistory()
        if (event is Event.StartNewHistory && before.history.state is HistoryState.Damaged) startNewHistory()
        // The history begins with the baseline and follows it: right after the first review, and whenever the saved state is read.
        if ((event is Event.FinishReview || event is Event.StoreLoaded) && after.saved != null) after.raw?.let { recordHistory(it) }
        val saved = after.saved
        // Only the user's own decisions are written. Loading what was already saved must not rewrite the file.
        if (saved != null && saved != before.saved && (event is Event.FinishReview || event is Event.AcknowledgeChanges || event is Event.SetDeclarations)) saves.trySend(saved)
    }

    /**
     * The app came on screen: listen for changes and re-read now and then. Everything stops in [screenStopped], because a
     * measurement (FINDINGS.md, Q7) shows nothing is delivered to a background app, and no service is run to change that.
     */
    fun screenStarted() {
        if (watcher.running) return
        watcher.start(::heard)
        dispatch(Event.WatchingChanged(true))
        polling = viewModelScope.launch {
            while (true) {
                delay(Watched.POLL_SECONDS * 1_000)
                readAgain()
            }
        }
    }

    fun screenStopped() {
        watcher.stop()
        rereadSoon?.cancel()
        polling?.cancel()
        dispatch(Event.WatchingChanged(false))
    }

    private fun heard(signal: LiveSignal) {
        dispatch(Event.LiveSeen(LiveEvent(Instant.now(), signal.source, signal.what)))
        // A burst of announcements (one install is several) is read once, shortly after the last of them.
        rereadSoon?.cancel()
        rereadSoon = viewModelScope.launch {
            delay(REREAD_DELAY_MS)
            readAgain()
        }
    }

    private fun readAgain() {
        val now = _state.value
        if (now.refreshing || now.load is Load.Loading) rereadAfterLoad = true else dispatch(Event.Refresh)
    }

    override fun onCleared() {
        watcher.stop()
        historyThread.close()
    }

    private companion object {
        const val REREAD_DELAY_MS = 300L
        const val RETRY_DELAY_MS = 2_000L
        const val MAX_RETRIES = 3
    }

    private fun startLoad() {
        loading?.cancel()
        loading = viewModelScope.launch {
            val event = try {
                Event.ReportLoaded(withContext(Dispatchers.Default) { builder.build(Build.VERSION.SDK_INT) })
            } catch (e: Exception) {
                Event.LoadFailed("${e.javaClass.simpleName}: ${e.message.orEmpty().take(160)}")
            }
            dispatch(event)
            if (event is Event.ReportLoaded && _state.value.saved != null) recordHistory(event.report)
            if (event is Event.LoadFailed) {
                // The package manager can be busy for a moment while an app is being installed. Waiting for the next 30 second read would
                // leave the screen stale for no reason, so a failed read is tried again after two seconds, up to three times in a row.
                failedReads += 1
                if (failedReads <= MAX_RETRIES && watcher.running) rereadSoon = viewModelScope.launch { delay(RETRY_DELAY_MS); readAgain() }
            } else {
                failedReads = 0
            }
            if (rereadAfterLoad) {
                rereadAfterLoad = false
                dispatch(Event.Refresh)
            }
        }
    }
}
