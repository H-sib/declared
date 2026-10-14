package io.github.h_sib.declared.core.ledger

import io.github.h_sib.declared.core.state.ChangeItem
import io.github.h_sib.declared.core.state.Changes
import io.github.h_sib.declared.core.state.Diff
import io.github.h_sib.declared.core.state.Record
import io.github.h_sib.declared.core.state.Snapshot
import java.time.Duration
import java.time.Instant

/** One line of the drift timeline, newest first. A stretch with nothing recorded is a row of its own, never smoothed over. */
sealed interface TimelineRow {
    data class Recorded(
        val seq: Long,
        val at: Instant,
        val taken: Taken,
        /** The changes the person would be told about, in the words the "what changed" screen uses. */
        val items: List<ChangeItem>,
        /** How many records differ underneath, built-in apps and unimportant settings included. */
        val rawChanges: Int,
        val events: List<String>,
        /** True for the first entry, which describes the phone as it was when the history began and changes nothing. */
        val isStart: Boolean,
    ) : TimelineRow

    data class Gap(val from: Instant, val to: Instant) : TimelineRow {
        val duration: Duration get() = Duration.between(from, to)
    }
}

object Timeline {
    /** At most [limit] recorded rows of the newest history (gaps between them come on top of that), newest first. */
    fun of(ledger: Ledger, limit: Int = 200): List<TimelineRow> {
        val recorded = ArrayList<TimelineRow.Recorded>()
        var records: Map<String, Record> = emptyMap()
        var previous: Snapshot? = null
        for (e in ledger.entries) {
            records = try { Diff.apply(records, e.changes) } catch (ex: IllegalArgumentException) { break }
            val snapshot = Snapshot(e.at, e.androidApi, records)
            val first = previous == null
            val summary = previous?.let { Changes.summarize(it, snapshot) }
            recorded += TimelineRow.Recorded(
                seq = e.seq, at = e.at, taken = e.taken,
                items = summary?.items.orEmpty(),
                rawChanges = if (first) records.size else e.changes.size,
                events = e.events, isStart = first,
            )
            previous = snapshot
        }
        val newest = recorded.asReversed().take(limit)
        val rows = ArrayList<TimelineRow>()
        newest.forEachIndexed { i, r ->
            rows += r
            val older = newest.getOrNull(i + 1)
            if (older != null && Duration.between(older.at, r.at) > LedgerPolicy.GAP_AFTER) rows += TimelineRow.Gap(older.at, r.at)
        }
        return rows
    }
}

/** What each event code means, in plain words. An unknown code is shown as such rather than dropped. */
object LedgerEvents {
    const val ARMED_BIOMETRIC = "tripwire.armed.biometric"
    const val ARMED_CREDENTIAL = "tripwire.armed.credential"
    const val FINGERPRINT_ADDED = "tripwire.fingerprint-added"
    const val LOCK_REMOVED = "tripwire.lock-removed"
    const val CANNOT_TELL = "tripwire.cannot-tell"
    const val KEY_CHANGED = "signing-key.changed"

    fun words(code: String): String = when (code) {
        ARMED_BIOMETRIC -> "A tripwire was set that notices when a fingerprint is added."
        ARMED_CREDENTIAL -> "A tripwire was set that notices when the screen lock is removed."
        FINGERPRINT_ADDED -> "A fingerprint was added since this app last looked. It may have been you."
        LOCK_REMOVED -> "The screen lock was removed since this app last looked. It may have been you."
        CANNOT_TELL -> "The tripwire keys were destroyed while a screen lock exists, so this app cannot tell why: the lock may have been removed and set again, or the app was reinstalled or its data cleared."
        KEY_CHANGED -> "The key that signs this history is not the one it began with, so later entries are not signed."
        else -> "An event this version does not know: $code"
    }
}
