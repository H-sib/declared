package io.github.h_sib.declared.core.home

import io.github.h_sib.declared.core.ledger.History
import io.github.h_sib.declared.core.ledger.HistoryState
import io.github.h_sib.declared.core.ledger.LastTripwire
import io.github.h_sib.declared.core.model.Availability
import io.github.h_sib.declared.core.model.Declarations
import io.github.h_sib.declared.core.model.SignalStatus
import io.github.h_sib.declared.core.model.SourceInfo
import io.github.h_sib.declared.core.paths.PathsAnalysis
import io.github.h_sib.declared.core.paths.phone
import io.github.h_sib.declared.core.paths.sideloaded
import io.github.h_sib.declared.core.report.Report
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class HomeTest {
    private val quiet: Report = phone()
    private val watched: Report = phone(packages = listOf(sideloaded("evil.app")), a11y = listOf("evil.app"))

    private fun summary(report: Report, history: History = History.LOADING.copy(state = HistoryState.Ready), changes: Int? = null) =
        Home.of(report, PathsAnalysis.of(report, null, Declarations()).view, history, changes)

    private fun status(available: Availability, minApi: Int = 1) =
        SignalStatus(io.github.h_sib.declared.core.model.SignalId("x"), available, SourceInfo(api = "test", minApi = minApi))

    @Test
    fun coverageCountsWhatRanWhatCouldNotAndWhatThisAndroidCannotGive() {
        val report = quiet.copy(
            androidApi = 29,
            signals = listOf(status(Availability.Available), status(Availability.Degraded("x")), status(Availability.Unavailable("refused")), status(Availability.Unavailable("needs 31"), minApi = 31)),
        )
        val c = Home.coverage(report)
        assertEquals(Coverage(ran = 2, couldNotRun = 1, unsupported = 1), c)
        assertEquals("2 of 4 checks ran. 1 could not run. 1 is not supported on this Android version.", c.words())
        assertEquals("3 of 3 checks ran.", Coverage(3, 0, 0).words())
        assertTrue(Coverage(1, 0, 2).words().contains("2 are not supported"))
    }

    @Test
    fun theStatementNamesWhatWasFoundAndNeverSaysThePhoneIsFine() {
        val found = summary(watched)
        assertEquals("One thing to look at first.", found.statement)
        assertTrue(found.detail.orEmpty(), found.detail.orEmpty().contains("evil.app") && found.detail.orEmpty().endsWith("."))
        assertNull("nothing to look at, nothing to quote", summary(quiet).detail)
        val none = summary(quiet).statement
        assertTrue(none, none.startsWith("Nothing was found by ") && none.endsWith(" checks."))
        assertFalse(none.lowercase().contains("safe") || none.lowercase().contains("secure"))
    }

    @Test
    fun theDoorsSayWhatIsBehindThemInPlainWords() {
        assertEquals("Nothing to look at.", summary(quiet).checkLine)
        assertTrue(summary(watched).checkLine.contains("to look at first"))
        assertEquals("Empty so far.", summary(quiet).historyLine)
        assertEquals("The history could not be read.", summary(quiet, History.damaged("x")).historyLine)
        assertEquals("Reading the history.", summary(quiet, History.LOADING).historyLine)
        val withEvent = History.LOADING.copy(state = HistoryState.Ready, entries = 14, lastTripwire = LastTripwire("tripwire.lock-removed", Instant.EPOCH))
        assertEquals("14 entries. A tripwire event is recorded.", summary(quiet, withEvent).historyLine)
        assertEquals("1 entry. No tripwire event.", summary(quiet, withEvent.copy(entries = 1, lastTripwire = null)).historyLine)
    }

    @Test
    fun everyAreaGetsOneRowAndAFindingLandsInTheAreaItsIdNames() {
        val areas = Home.areas(watched, History.LOADING.copy(state = HistoryState.Ready, entries = 2))
        assertEquals(listOf(AreaKind.POWERS, AreaKind.APPS, AreaKind.SETTINGS, AreaKind.NETWORK, AreaKind.INTEGRITY, AreaKind.HISTORY), areas.map { it.kind })
        assertEquals("the accessibility finding is a power", AreaStatus.RISK, areas.first { it.kind == AreaKind.POWERS }.status)
        assertEquals("2 entries.", areas.last().line)
        val quiet = Home.areas(quiet, History.LOADING.copy(state = HistoryState.Ready, entries = 1))
        assertTrue(quiet.none { it.status == AreaStatus.RISK })
        assertEquals("a quiet area says only that nothing was found", "Nothing found.", quiet.first { it.kind == AreaKind.POWERS }.line)
    }

    @Test
    fun aTripwireEventOrADamagedHistoryIsNotShownAsClear() {
        val withEvent = History.LOADING.copy(state = HistoryState.Ready, entries = 3, lastTripwire = LastTripwire("tripwire.lock-removed", Instant.EPOCH))
        assertEquals(AreaStatus.LOOK, Home.areas(quiet, withEvent).last().status)
        assertEquals(AreaStatus.RISK, Home.areas(quiet, History.damaged("x")).last().status)
        assertEquals(AreaStatus.UNKNOWN, Home.areas(quiet, History.LOADING.copy(state = HistoryState.Ready)).last().status)
    }

    @Test
    fun theChangedLineAppearsOnlyWhenSomethingChanged() {
        assertNull(summary(quiet, changes = null).changedLine)
        assertNull(summary(quiet, changes = 0).changedLine)
        assertEquals("One change since you last looked.", summary(quiet, changes = 1).changedLine)
        assertEquals("3 changes since you last looked.", summary(quiet, changes = 3).changedLine)
    }

    @Test
    fun noWordOnTheHomeScreenIsBannedOrADash() {
        val banned = listOf("safe", "secure", "score", "rating", "malicious", "malware", "virus", "protected", "hacked", "danger")
        val texts = listOf(summary(quiet), summary(watched), summary(quiet, changes = 2)).flatMap { listOf(it.statement, it.detail.orEmpty(), it.coverage.words(), it.checkLine, it.pathsLine, it.historyLine, it.changedLine.orEmpty()) }
        for (t in texts) {
            val lower = t.lowercase()
            for (w in banned) assertFalse("'$w' in: $t", Regex("(?<![a-z])$w(?![a-z])").containsMatchIn(lower))
            assertFalse("dash in: $t", t.any { it.code == 0x2014 || it.code == 0x2013 })
        }
    }

    @Test
    fun theGlyphIsTheSameForTheSameHashMirroredAndDifferentForAnother() {
        val a = HashGlyph.cells("d07c107cb69aae38b5e76319f8145979")
        assertEquals(25, a.size)
        assertTrue(a.contentEquals(HashGlyph.cells("d07c107cb69aae38b5e76319f8145979")))
        for (r in 0 until 5) for (c in 0..1) assertEquals("row $r", a[r * 5 + c], a[r * 5 + 4 - c])
        assertNotEquals(a.toList(), HashGlyph.cells("8ccc0b84ac9ce49fb84c48efc25e308c").toList())
        assertTrue(a.any { it })
        assertTrue(HashGlyph.cells("").none { it })
    }
}
