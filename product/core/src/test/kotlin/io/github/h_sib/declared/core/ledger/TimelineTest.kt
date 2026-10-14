package io.github.h_sib.declared.core.ledger

import io.github.h_sib.declared.core.healthyDevice
import io.github.h_sib.declared.core.network
import io.github.h_sib.declared.core.paths.FROM_A_FILE
import io.github.h_sib.declared.core.paths.phone
import io.github.h_sib.declared.core.paths.sideloaded
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.state.ChangeKind
import io.github.h_sib.declared.core.state.SnapshotBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class TimelineTest {
    private val t0 = Instant.parse("2026-10-09T10:00:00Z")
    private val signer = SoftwareSigner()

    private fun snapshotOf(report: io.github.h_sib.declared.core.report.Report, at: Instant) = SnapshotBuilder.of(report).let { io.github.h_sib.declared.core.state.Snapshot(at, it.androidApi, it.records) }

    private fun add(l: Ledger, report: io.github.h_sib.declared.core.report.Report, at: Instant, events: List<String> = emptyList(), taken: Taken = Taken.OPENED): Ledger =
        (l.append(snapshotOf(report, at), at, taken, "0.3.0", events, signer) as AppendResult.Appended).ledger

    private val quiet = phone()
    private val withProxy = phone(network = network(proxy = "10.0.0.2:3128"))
    private val withSideloaded = phone(packages = listOf(sideloaded("evil.app")), a11y = listOf("evil.app"))

    @Test
    fun theNewestEntryIsFirstTheStartIsLastAndNothingIsHiddenOrReordered() {
        var l = Ledger.begin(t0, signer)
        l = add(l, quiet, t0)
        l = add(l, withProxy, t0.plusSeconds(3600), taken = Taken.ON_SCREEN)
        l = add(l, withSideloaded, t0.plusSeconds(7200))
        val rows = Timeline.of(l).filterIsInstance<TimelineRow.Recorded>()
        assertEquals(listOf(3L, 2L, 1L), rows.map { it.seq })
        assertTrue(rows.last().isStart && rows.last().items.isEmpty() && rows.last().rawChanges > 10)
        assertFalse(rows.first().isStart)
        assertEquals(Taken.ON_SCREEN, rows[1].taken)
    }

    @Test
    fun eachRowSaysWhatChangedInTheWordsOfTheWhatChangedScreen() {
        var l = Ledger.begin(t0, signer)
        l = add(l, quiet, t0)
        l = add(l, withSideloaded, t0.plusSeconds(60))
        val row = Timeline.of(l).filterIsInstance<TimelineRow.Recorded>().first()
        assertTrue(row.items.toString(), row.items.any { it.kind == ChangeKind.NEW_FINDING && it.attention == Attention.LOOK_FIRST || it.kind == ChangeKind.NEW_FINDING })
        assertTrue(row.items.any { it.title.contains("evil.app") })
        assertTrue(row.rawChanges >= row.items.size)
    }

    @Test
    fun aLongStretchWithNothingRecordedIsARowOfItsOwn() {
        var l = Ledger.begin(t0, signer)
        l = add(l, quiet, t0)
        l = add(l, withProxy, t0.plus(Duration.ofHours(10)))
        l = add(l, quiet, t0.plus(Duration.ofDays(5)))
        val rows = Timeline.of(l)
        val gaps = rows.filterIsInstance<TimelineRow.Gap>()
        assertEquals(1, gaps.size)
        assertEquals(t0.plus(Duration.ofHours(10)), gaps.single().from)
        assertEquals(t0.plus(Duration.ofDays(5)), gaps.single().to)
        assertTrue(gaps.single().duration > LedgerPolicy.GAP_AFTER)
        // the gap sits between the two rows it separates
        assertEquals(listOf("Recorded", "Gap", "Recorded", "Recorded"), rows.map { it::class.simpleName })
    }

    @Test
    fun onlyTheNewestRowsAreShownButTheStartingStateIsStillReplayedFromTheBeginning() {
        var l = Ledger.begin(t0, signer)
        l = add(l, quiet, t0)
        repeat(30) { i -> l = add(l, if (i % 2 == 0) withProxy else quiet, t0.plusSeconds((i + 1) * 3600L)) }
        val rows = Timeline.of(l, limit = 5).filterIsInstance<TimelineRow.Recorded>()
        assertEquals(5, rows.size)
        assertEquals(31L, rows.first().seq)
        assertTrue("every shown row still knows what changed, which needs the replay of the rows before it", rows.all { it.items.isNotEmpty() })
    }

    @Test
    fun eventsAreCarriedAndEveryCodeHasWords() {
        var l = Ledger.begin(t0, signer)
        l = add(l, quiet, t0, events = listOf(LedgerEvents.ARMED_BIOMETRIC))
        l = add(l, quiet, t0.plusSeconds(60), events = listOf(LedgerEvents.FINGERPRINT_ADDED))
        val rows = Timeline.of(l).filterIsInstance<TimelineRow.Recorded>()
        assertEquals(listOf(LedgerEvents.FINGERPRINT_ADDED), rows.first().events)
        for (code in listOf(LedgerEvents.ARMED_BIOMETRIC, LedgerEvents.ARMED_CREDENTIAL, LedgerEvents.FINGERPRINT_ADDED, LedgerEvents.LOCK_REMOVED, LedgerEvents.CANNOT_TELL, LedgerEvents.KEY_CHANGED)) {
            val w = LedgerEvents.words(code)
            assertTrue(code, w.length > 20 && !w.contains(code))
        }
        assertTrue(LedgerEvents.words("future.code").contains("future.code"))
    }

    @Test
    fun noWordOnTheTimelineIsBannedOrADash() {
        val banned = listOf("malicious", "malware", "virus", "infected", "suspicious", "trojan", "spyware", "hacked", "compromised", "danger", "safe", "secure", "score", "rating", "stolen", "break in", "broke in", "attacker")
        val texts = listOf(
            LedgerEvents.ARMED_BIOMETRIC, LedgerEvents.ARMED_CREDENTIAL, LedgerEvents.FINGERPRINT_ADDED, LedgerEvents.LOCK_REMOVED, LedgerEvents.CANNOT_TELL, LedgerEvents.KEY_CHANGED,
        ).map(LedgerEvents::words) + LedgerVerifier.CHECKED + LedgerVerifier.NOT_CHECKED +
            listOf(Verdict.NothingSeen, Verdict.FingerprintAdded, Verdict.LockRemoved, Verdict.CannotTell("x"), Verdict.NotPossible("no screen lock"), Verdict.NotSetUpYet).map(Tripwires::words) +
            listOf(HistoryWords.INTRO, HistoryWords.NOT_WATCHING, HistoryWords.DAMAGED, HistoryWords.TRIPWIRE_EXPLAIN, HistoryWords.VERIFY_IDLE, HistoryWords.LAST_TRIPWIRE) +
            (KeyHeld.entries.map(HistoryWords::key) + HistoryWords.key(null)) + Taken.entries.map(HistoryWords::how) +
            listOf(Duration.ofHours(5), Duration.ofHours(72)).map { HistoryWords.gap(TimelineRow.Gap(t0, t0.plus(it))) } +
            listOf(Verification(3, 3, emptyList(), emptyList(), emptyList()), Verification(3, 2, emptyList(), emptyList(), emptyList()),
                Verification(3, 3, emptyList(), emptyList(), emptyList(), keyTied = false), Verification(3, 3, listOf(Problem(1, Severity.BROKEN, "x")), emptyList(), emptyList())).map(HistoryWords::summary)
        for (t in texts) {
            val lower = t.lowercase()
            for (w in banned) assertFalse("'$w' in: $t", Regex("(?<![a-z])" + Regex.escape(w) + "(?![a-z])").containsMatchIn(lower))
            assertFalse("dash in: $t", t.any { it.code == 0x2014 || it.code == 0x2013 })
        }
    }

    @Test
    fun aPhoneChangedBackAndForthIsWhatTheHistoryIsFor() {
        // Between two openings a setting changed and changed back: the baseline comparison cannot see it, the ledger can.
        var l = Ledger.begin(t0, signer)
        l = add(l, quiet, t0)
        l = add(l, withProxy, t0.plusSeconds(600), taken = Taken.ON_SCREEN)
        l = add(l, quiet, t0.plusSeconds(1200), taken = Taken.ON_SCREEN)
        val rows = Timeline.of(l).filterIsInstance<TimelineRow.Recorded>()
        assertEquals(listOf(3L, 2L, 1L), rows.map { it.seq })
        assertTrue(rows[0].items.isNotEmpty() && rows[1].items.isNotEmpty())
        assertEquals("the phone is as it was at the start", l.entries.first().snapshotDigest, l.head!!.snapshotDigest)
        val reportDevice = phone(device = healthyDevice(lock = ReadResult.Ok(false)))
        assertTrue(add(l, reportDevice, t0.plusSeconds(1800)).verify().intact)
        assertEquals(FROM_A_FILE, "com.google.android.packageinstaller")
    }
}
