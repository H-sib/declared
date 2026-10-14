package io.github.h_sib.declared.core.ledger

import java.time.Instant

data class HeadInfo(val seq: Long, val hash: String, val at: Instant)

/** The newest entry in which a tripwire fired (or could not tell), so the news does not vanish with the next look. */
data class LastTripwire(val code: String, val at: Instant)

sealed interface HistoryState {
    data object Loading : HistoryState

    data object Ready : HistoryState

    /** The file exists and cannot be read as a history (changed, damaged, or its key is gone). It is never replaced silently. */
    data class Damaged(val reason: String) : HistoryState

    /** The place to keep it could not be used at all. */
    data class Unavailable(val reason: String) : HistoryState
}

sealed interface Verifying {
    data object NotRun : Verifying

    data object Running : Verifying

    data class Done(val result: Verification, val at: Instant) : Verifying
}

/**
 * What the screens show of the history: a value, so the reducer stays pure and the screens never touch the ledger itself. The
 * timeline is the newest rows only; the ledger keeps everything.
 */
data class History(
    val state: HistoryState,
    val entries: Int,
    val since: Instant?,
    val head: HeadInfo?,
    val keyHeld: KeyHeld?,
    val timeline: List<TimelineRow>,
    val tripwire: TripwireStatus?,
    val verifying: Verifying,
    val lastTripwire: LastTripwire? = null,
) {
    companion object {
        val LOADING = History(HistoryState.Loading, 0, null, null, null, emptyList(), null, Verifying.NotRun)

        fun damaged(reason: String) = LOADING.copy(state = HistoryState.Damaged(reason))

        fun unavailable(reason: String) = LOADING.copy(state = HistoryState.Unavailable(reason))

        /** A verification stays on screen only while the history it checked is the one shown: a new entry makes it stale. */
        fun of(ledger: Ledger, tripwire: TripwireStatus?, previous: History? = null): History {
            val head = ledger.head?.let { HeadInfo(it.seq, it.hash, it.at) }
            val keep = if (previous != null && previous.verifying is Verifying.Done && previous.head == head) previous.verifying else null
            val fired = setOf(LedgerEvents.FINGERPRINT_ADDED, LedgerEvents.LOCK_REMOVED, LedgerEvents.CANNOT_TELL)
            val last = ledger.entries.asReversed().firstNotNullOfOrNull { e -> e.events.firstOrNull { it in fired }?.let { LastTripwire(it, e.at) } }
            return History(
                state = HistoryState.Ready,
                entries = ledger.entries.size,
                since = ledger.header.createdAt,
                head = head,
                keyHeld = ledger.header.keyHeld,
                timeline = Timeline.of(ledger),
                tripwire = tripwire ?: previous?.tripwire,
                verifying = keep ?: Verifying.NotRun,
                lastTripwire = last,
            )
        }
    }
}
