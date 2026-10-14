package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.flow.AppState
import io.github.h_sib.declared.core.flow.Event
import io.github.h_sib.declared.core.flow.Load
import io.github.h_sib.declared.core.flow.Screen
import io.github.h_sib.declared.core.flow.StoreStatus
import io.github.h_sib.declared.core.flow.reduce
import io.github.h_sib.declared.core.ledger.History
import io.github.h_sib.declared.core.ledger.HistoryState
import io.github.h_sib.declared.core.ledger.Ledger
import io.github.h_sib.declared.core.ledger.Verifying
import io.github.h_sib.declared.core.ledger.ledgerWithOneEntry
import io.github.h_sib.declared.core.live.LiveEvent
import io.github.h_sib.declared.core.live.LiveLog
import io.github.h_sib.declared.core.live.LiveSource
import io.github.h_sib.declared.core.model.Declarations
import io.github.h_sib.declared.core.model.Profile
import io.github.h_sib.declared.core.model.Told
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.state.Answer
import io.github.h_sib.declared.core.state.Review
import io.github.h_sib.declared.core.state.SavedState
import io.github.h_sib.declared.core.state.StoreResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Random

/** The first-run gate, the review, the saved state and "what changed", as a pure state machine. */
class ReviewFlowTest {
    private val at: Instant = Instant.parse("2026-10-09T10:00:00Z")

    private fun reportWith(vararg pkgs: String, lock: Boolean = true): Report {
        val services = pkgs.map { service(it) }
        return builder(
            sources(
                accessibility = ReadResult.Ok(reading(services, services.map { it.component }.toSet())),
                packages = FakePackageFactsSource(pkgs.associateWith { ReadResult.Ok(facts(it)) }),
                device = healthyDevice(lock = ReadResult.Ok(lock)),
            ),
        ).build(34)
    }

    private fun keyOf(pkg: String) = "ACCESSIBILITY:$pkg/$pkg.Service"

    private fun firstRun(vararg pkgs: String): AppState =
        reduce(reduce(AppState(), Event.ReportLoaded(reportWith(*pkgs))), Event.StoreLoaded(StoreResult.Empty))

    private fun reviewed(vararg pkgs: String, answers: Map<String, Answer> = emptyMap()): AppState {
        val s = reduce(firstRun(*pkgs), Event.StartReview)
        val answered = answers.entries.fold(s) { acc, (k, a) -> reduce(acc, Event.AnswerHolder(k, a)) }
        return reduce(answered, Event.FinishReview(at))
    }

    // ------------------------------------------------------------ the first-run gate

    @Test
    fun aResolvedEmptyStoreSendsTheUserToTheFirstRunScreenWhicheverArrivesFirst() {
        val a = firstRun("a.b")
        val b = reduce(reduce(AppState(), Event.StoreLoaded(StoreResult.Empty)), Event.ReportLoaded(reportWith("a.b")))
        for (s in listOf(a, b)) {
            assertEquals(listOf<Screen>(Screen.FirstRun), s.stack)
            assertFalse(s.canGoBack)
            assertNull(s.saved)
        }
    }

    @Test
    fun nothingGatesTheUserBeforeBothTheReportAndTheStoreAreKnown() {
        assertEquals(listOf<Screen>(Screen.Inventory), reduce(AppState(), Event.ReportLoaded(reportWith("a.b"))).stack)
        assertEquals(listOf<Screen>(Screen.Inventory), reduce(AppState(), Event.StoreLoaded(StoreResult.Empty)).stack)
    }

    @Test
    fun aSavedBaselineSkipsTheFirstRun() {
        val saved = reviewed("a.b").saved!!
        val s = reduce(reduce(AppState(), Event.ReportLoaded(reportWith("a.b"))), Event.StoreLoaded(StoreResult.Loaded(saved)))
        assertEquals(listOf<Screen>(Screen.Inventory), s.stack)
        assertEquals(saved, s.saved)
        assertNotNull(s.changes)
        assertTrue(s.changes!!.items.isEmpty())
    }

    @Test
    fun aCorruptStoreIsToldApartAndStartsOverHonestly() {
        val s = reduce(reduce(AppState(), Event.ReportLoaded(reportWith("a.b"))), Event.StoreLoaded(StoreResult.Corrupt("authentication tag mismatch")))
        assertEquals(StoreStatus.Corrupt("authentication tag mismatch"), s.store)
        assertEquals(listOf<Screen>(Screen.FirstRun), s.stack)
    }

    @Test
    fun anUnavailableStoreIsNotAFirstRunBecauseNothingCouldBeKept() {
        val s = reduce(reduce(AppState(), Event.ReportLoaded(reportWith("a.b"))), Event.StoreLoaded(StoreResult.Unavailable("key store refused")))
        assertEquals(listOf<Screen>(Screen.Inventory), s.stack)
        assertEquals(StoreStatus.Unavailable("key store refused"), s.store)
        assertEquals(s, reduce(s, Event.OpenReview))
        assertEquals(s, reduce(s, Event.OpenWhatChanged))
    }

    // ------------------------------------------------------------ the first review

    @Test
    fun theFirstReviewAnswersOnlyWhatIsPendingAndSavesOnFinish() {
        val s = reduce(firstRun("a.b", "c.d"), Event.StartReview)
        assertEquals(listOf<Screen>(Screen.ReviewHolders(first = true)), s.stack)
        assertEquals(setOf(keyOf("a.b"), keyOf("c.d")), s.pending.map { Review.key(it) }.toSet())
        val one = reduce(s, Event.AnswerHolder(keyOf("a.b"), Answer.RECOGNIZED))
        assertEquals(mapOf(keyOf("a.b") to Answer.RECOGNIZED), one.draft)
        assertEquals(one, reduce(one, Event.AnswerHolder("nope", Answer.RECOGNIZED))) // not up for review: ignored
        val changed = reduce(one, Event.AnswerHolder(keyOf("a.b"), Answer.NOT_RECOGNIZED))
        assertEquals(Answer.NOT_RECOGNIZED, changed.draft.getValue(keyOf("a.b")))

        val done = reduce(changed, Event.FinishReview(at))
        assertEquals(listOf<Screen>(Screen.Inventory), done.stack)
        assertTrue(done.draft.isEmpty())
        val saved = done.saved!!
        assertEquals(Answer.NOT_RECOGNIZED, saved.answers.getValue(keyOf("a.b")).answer)
        assertEquals(Answer.UNSURE, saved.answers.getValue(keyOf("c.d")).answer)
        assertEquals(at, saved.baselineAt)
        assertTrue(done.changes!!.items.isEmpty())
    }

    @Test
    fun aNotRecognizedAnswerBecomesAFindingTheUserCanOpen() {
        val done = reviewed("a.b", answers = mapOf(keyOf("a.b") to Answer.NOT_RECOGNIZED))
        val id = "review:not-recognized:${keyOf("a.b")}"
        assertNotNull(done.report!!.finding(id))
        val open = reduce(done, Event.OpenFinding(id))
        assertEquals(Screen.FindingDetail(id), open.screen)
    }

    @Test
    fun answersAreIgnoredOutsideAReviewAndFinishWithoutOneDoesNothing() {
        val s = firstRun("a.b")
        assertEquals(s, reduce(s, Event.AnswerHolder(keyOf("a.b"), Answer.RECOGNIZED)))
        assertEquals(s, reduce(s, Event.FinishReview(at)))
        assertEquals(s, reduce(s, Event.AcknowledgeChanges))
    }

    // ------------------------------------------------------------ later reviews and "what changed"

    @Test
    fun somethingNewAfterTheBaselineIsPendingAndShowsAsAChange() {
        val base = reviewed("a.b")
        val later = reduce(base, Event.ReportLoaded(reportWith("a.b", "c.d", lock = false)))
        assertEquals(listOf(keyOf("c.d")), later.pending.map { Review.key(it) })
        val changes = later.changes!!
        assertTrue(changes.needsAttention)
        assertTrue(changes.items.any { it.title.contains("New app") })
        assertTrue(changes.items.any { it.title.contains("No screen lock") })
    }

    @Test
    fun aLaterReviewPushesAndPopsAndKeepsEarlierAnswers() {
        val base = reviewed("a.b", answers = mapOf(keyOf("a.b") to Answer.RECOGNIZED))
        val later = reduce(base, Event.ReportLoaded(reportWith("a.b", "c.d")))
        val open = reduce(later, Event.OpenReview)
        assertEquals(listOf(Screen.Inventory, Screen.ReviewHolders(first = false)), open.stack)
        assertEquals(open, reduce(open, Event.OpenReview))
        val answered = reduce(open, Event.AnswerHolder(keyOf("c.d"), Answer.RECOGNIZED))
        val done = reduce(answered, Event.FinishReview(at.plusSeconds(60)))
        assertEquals(listOf<Screen>(Screen.Inventory), done.stack)
        assertEquals(2, done.saved!!.answers.size)
        assertEquals(Answer.RECOGNIZED, done.saved.answers.getValue(keyOf("a.b")).answer)
        assertEquals(base.saved!!.baselineAt, done.saved.baselineAt)
        assertTrue(done.pending.isEmpty())
    }

    @Test
    fun changesStayUntilAcknowledgedAndOpeningTheScreenAloneDoesNotClearThem() {
        val base = reviewed("a.b")
        val later = reduce(base, Event.ReportLoaded(reportWith("a.b", "c.d")))
        val screen = reduce(later, Event.OpenWhatChanged)
        assertEquals(Screen.WhatChanged, screen.stack.last())
        val closed = reduce(screen, Event.Back)
        assertTrue(closed.changes!!.items.isNotEmpty())
        // A refresh keeps them too.
        assertTrue(reduce(closed, Event.ReportLoaded(reportWith("a.b", "c.d"))).changes!!.items.isNotEmpty())
        val acked = reduce(closed, Event.AcknowledgeChanges)
        assertTrue(acked.changes!!.items.isEmpty())
        assertEquals(base.saved!!.answers, acked.saved!!.answers)
    }

    @Test
    fun theWhatChangedScreenGoesWhenThereIsNothingSavedToCompareWith() {
        val s = reviewed("a.b")
        val open = reduce(s, Event.OpenWhatChanged)
        val wiped = reduce(open, Event.StoreLoaded(StoreResult.Empty))
        assertFalse(wiped.stack.contains(Screen.WhatChanged))
        assertEquals(listOf<Screen>(Screen.FirstRun), wiped.stack)
    }

    @Test
    fun aSaveFailureIsShownAndClearedByTheNextSuccessfulFinish() {
        val failed = reduce(firstRun("a.b"), Event.SaveFailed("disk full"))
        assertEquals("disk full", failed.saveError)
        val done = reduce(reduce(failed, Event.StartReview), Event.FinishReview(at))
        assertNull(done.saveError)
        assertNotNull(done.saved)
    }

    // ------------------------------------------------------------ the property

    @Test
    fun randomSequencesOfEveryEventKeepTheInvariants() {
        val reports = listOf(reportWith(), reportWith("a.b"), reportWith("a.b", "c.d"), reportWith("c.d", lock = false), reportWith("a.b", "c.d", "e.f"))
        val savedA = reviewed("a.b").saved!!
        val keys = listOf(keyOf("a.b"), keyOf("c.d"), keyOf("e.f"), "bogus")
        val ids = listOf("accessibility:a.b/a.b.Service", "accessibility:c.d/c.d.Service", "review:not-recognized:${keyOf("a.b")}", "missing")
        val random = Random(20261010)
        repeat(3000) { run ->
            var s = AppState()
            repeat(80) { step ->
                val event: Event = when (random.nextInt(32)) {
                    0, 1 -> Event.ReportLoaded(reports[random.nextInt(reports.size)])
                    2 -> Event.LoadFailed("e")
                    3 -> Event.StoreLoaded(listOf(StoreResult.Empty, StoreResult.Loaded(savedA), StoreResult.Corrupt("c"), StoreResult.Unavailable("u"))[random.nextInt(4)])
                    4, 5 -> Event.OpenFinding(ids[random.nextInt(ids.size)])
                    6 -> Event.Back
                    7 -> Event.OpenApps
                    8 -> Event.OpenWhatChanged
                    9 -> Event.OpenReview
                    10 -> Event.StartReview
                    11, 12 -> Event.AnswerHolder(keys[random.nextInt(keys.size)], Answer.entries[random.nextInt(3)])
                    13 -> Event.FinishReview(at)
                    14 -> Event.AcknowledgeChanges
                    15 -> Event.OpenBlindSpots
                    16 -> Event.OpenLiveView
                    17 -> if (random.nextBoolean()) Event.WatchingChanged(random.nextBoolean()) else Event.LiveSeen(LiveEvent(at.plusMillis(step * 2_000L), LiveSource.SETTING, "e${random.nextInt(4)}"))
                    18 -> Event.OpenPaths
                    19 -> Event.OpenAboutYou
                    20 -> Event.SetDeclarations(Declarations(Told.entries[random.nextInt(3)], Told.entries[random.nextInt(3)], Profile.entries[random.nextInt(2)]))
                    21 -> Event.ToggleLever(listOf("a", "b", "c")[random.nextInt(3)])
                    22 -> Event.ClearWhatIf
                    23 -> Event.OpenTimeline
                    24 -> Event.HistoryUpdated(listOf(History.LOADING, History.damaged("x"), oneEntryHistory)[random.nextInt(3)])
                    25 -> Event.VerifyHistory
                    26 -> Event.HistoryVerified(oneEntryVerification, at)
                    27 -> Event.StartNewHistory
                    28 -> Event.OpenCheck
                    29 -> Event.OpenMore
                    30 -> Event.GoHome
                    else -> if (random.nextBoolean()) Event.Refresh else Event.SaveFailed("x")
                }
                s = reduce(s, event)
                val where = "run $run step $step after $event: $s"
                assertTrue(where, s.stack.isNotEmpty())
                val bottom = s.stack.first()
                assertTrue(where, bottom == Screen.Inventory || bottom == Screen.FirstRun || bottom == Screen.ReviewHolders(first = true))
                if (bottom != Screen.Inventory) assertNull(where, s.saved)
                if (s.saved != null) assertTrue(where, s.stack.none { it == Screen.FirstRun || it == Screen.ReviewHolders(first = true) })
                assertTrue(where, s.stack.drop(1).all { it is Screen.FindingDetail || it == Screen.Apps || it == Screen.WhatChanged || it == Screen.BlindSpots || it == Screen.LiveView || it == Screen.Paths || it == Screen.AboutYou || it == Screen.Timeline || it == Screen.Check || it == Screen.More || it == Screen.ReviewHolders(first = false) })
                if (bottom != Screen.Inventory) assertTrue(where, s.stack.none { it == Screen.BlindSpots || it == Screen.LiveView || it == Screen.Paths || it == Screen.AboutYou || it == Screen.Timeline || it == Screen.Check || it == Screen.More })
                if (s.stack.contains(Screen.AboutYou)) assertNotNull(where, s.saved)
                if (s.whatIf.isNotEmpty()) assertEquals("a preview outside the paths screen: $where", Screen.Paths, s.screen)
                assertEquals(where, s.saved?.declarations ?: Declarations(), s.declarations)
                if (s.history.verifying == Verifying.Running) assertTrue("a check of nothing: $where", s.history.entries > 0)
                assertTrue(where, s.live.events.size <= LiveLog.MAX_EVENTS)
                if (s.stack.contains(Screen.WhatChanged)) assertNotNull(where, s.saved)
                val report = (s.load as? Load.Ready)?.report
                for (screen in s.stack) if (screen is Screen.FindingDetail) assertNotNull(where, report?.finding(screen.findingId))
                assertEquals(where, s.raw != null && s.saved != null, s.changes != null)
                assertTrue(where, s.draft.keys.all { k -> s.pending.any { Review.key(it) == k } || s.screen !is Screen.ReviewHolders })
                if (s.saved != null) assertEquals(where, SavedState.CURRENT_VERSION, s.saved.version)
            }
        }
    }

    private val oneEntryLedger = ledgerWithOneEntry()
    private val oneEntryHistory = History.of(oneEntryLedger, null)
    private val oneEntryVerification = oneEntryLedger.verify()

    // ------------------------------------------------------------ the history screens

    @Test
    fun theTimelineOpensOnceAndNeverDuringTheFirstReview() {
        var s = reviewed("a.b")
        s = reduce(s, Event.OpenTimeline)
        assertEquals(listOf(Screen.Inventory, Screen.Timeline), s.stack)
        assertEquals(s, reduce(s, Event.OpenTimeline))
        assertEquals(listOf(Screen.FirstRun), reduce(firstRun("a.b"), Event.OpenTimeline).stack)
    }

    @Test
    fun aCheckOfTheHistoryNeedsAHistoryAndItsResultStaysOnlyWhileTheHistoryIsTheSame() {
        var s = reviewed("a.b")
        assertEquals("nothing to check yet", Verifying.NotRun, reduce(s, Event.VerifyHistory).history.verifying)
        s = reduce(s, Event.HistoryUpdated(oneEntryHistory))
        s = reduce(s, Event.VerifyHistory)
        assertEquals(Verifying.Running, s.history.verifying)
        assertEquals("asking twice does not start two", s, reduce(s, Event.VerifyHistory))
        s = reduce(s, Event.HistoryVerified(oneEntryVerification, at))
        assertTrue(s.history.verifying is Verifying.Done)
        val same = History.of(oneEntryLedger, null, s.history)
        assertTrue("the same history keeps its result", same.verifying is Verifying.Done)
        val longer = History.of(
            (oneEntryLedger.append(io.github.h_sib.declared.core.state.Snapshot(at.plusSeconds(99999), 34, mapOf("app:a" to io.github.h_sib.declared.core.state.Record.of("k" to "z"))), at.plusSeconds(99999), io.github.h_sib.declared.core.ledger.Taken.OPENED, "1", emptyList(), null) as io.github.h_sib.declared.core.ledger.AppendResult.Appended).ledger,
            null, s.history,
        )
        assertEquals("a new entry makes the old result stale", Verifying.NotRun, longer.verifying)
    }

    @Test
    fun aDamagedHistoryIsOnlyReplacedWhenAskedAndThenStartsEmpty() {
        var s = reduce(reviewed("a.b"), Event.HistoryUpdated(History.damaged("the history failed its integrity check")))
        assertTrue(s.history.state is HistoryState.Damaged)
        assertEquals("a refresh does not replace it", s.history, reduce(s, Event.Refresh).history)
        s = reduce(s, Event.StartNewHistory)
        assertEquals(HistoryState.Ready, s.history.state)
        assertEquals(0, s.history.entries)
        assertEquals("asking when it is not damaged does nothing", s, reduce(s, Event.StartNewHistory))
    }

    // ------------------------------------------------------------ the paths screens

    @Test
    fun thePathsScreenOpensOnceAndNeverDuringTheFirstReview() {
        var s = reviewed("a.b")
        s = reduce(s, Event.OpenPaths)
        assertEquals(listOf(Screen.Inventory, Screen.Paths), s.stack)
        assertEquals(s, reduce(s, Event.OpenPaths))
        assertEquals(Screen.Inventory, reduce(s, Event.Back).screen)
        assertEquals(listOf(Screen.FirstRun), reduce(firstRun("a.b"), Event.OpenPaths).stack)
    }

    @Test
    fun aboutYouNeedsASavedBaselineBecauseThatIsWhereTheAnswersLive() {
        assertEquals(listOf(Screen.Inventory), reduce(reduce(AppState(), Event.ReportLoaded(reportWith("a.b"))), Event.OpenAboutYou).stack)
        val s = reduce(reviewed("a.b"), Event.OpenAboutYou)
        assertEquals(listOf(Screen.Inventory, Screen.AboutYou), s.stack)
    }

    @Test
    fun declarationsAreKeptWithTheBaselineAndSurviveAnAcknowledgementAndALaterReview() {
        var s = reviewed("a.b")
        assertEquals(Declarations(), s.declarations)
        val said = Declarations(Told.YES, Told.NO, Profile.HIGH_RISK)
        s = reduce(s, Event.SetDeclarations(said))
        assertEquals(said, s.declarations)
        assertEquals(said, s.saved!!.declarations)
        s = reduce(s, Event.AcknowledgeChanges)
        assertEquals(said, s.declarations)
        s = reduce(reduce(s, Event.ReportLoaded(reportWith("a.b", "c.d"))), Event.OpenReview)
        s = reduce(s, Event.FinishReview(at))
        assertEquals("a later review must not forget what the person said", said, s.saved!!.declarations)
    }

    @Test
    fun declarationsCannotBeSetBeforeThereIsABaselineToKeepThemIn() {
        val s = reduce(reduce(AppState(), Event.ReportLoaded(reportWith("a.b"))), Event.SetDeclarations(Declarations(Told.YES)))
        assertNull(s.saved)
        assertEquals(Declarations(), s.declarations)
    }

    @Test
    fun aPreviewBelongsToThePathsScreenAndIsForgottenWhenItIsLeft() {
        var s = reviewed("a.b")
        assertEquals("a preview on the wrong screen is ignored", emptySet<String>(), reduce(s, Event.ToggleLever("fix")).whatIf)
        s = reduce(s, Event.OpenPaths)
        s = reduce(reduce(s, Event.ToggleLever("fix")), Event.ToggleLever("other"))
        assertEquals(setOf("fix", "other"), s.whatIf)
        s = reduce(s, Event.ToggleLever("fix"))
        assertEquals(setOf("other"), s.whatIf)
        assertEquals(emptySet<String>(), reduce(s, Event.ClearWhatIf).whatIf)
        assertEquals("leaving the screen forgets the preview", emptySet<String>(), reduce(s, Event.Back).whatIf)
        assertEquals("a refresh keeps it while the screen is open", setOf("other"), reduce(s, Event.Refresh).whatIf)
    }
}
