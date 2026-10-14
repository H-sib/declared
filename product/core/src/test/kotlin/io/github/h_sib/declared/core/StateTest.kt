package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.sensor.PackageFacts
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.WatchedPermissions
import io.github.h_sib.declared.core.state.Answer
import io.github.h_sib.declared.core.state.Change
import io.github.h_sib.declared.core.state.ChangeKind
import io.github.h_sib.declared.core.state.Changes
import io.github.h_sib.declared.core.state.Diff
import io.github.h_sib.declared.core.state.Record
import io.github.h_sib.declared.core.state.Review
import io.github.h_sib.declared.core.state.SavedState
import io.github.h_sib.declared.core.state.Snapshot
import io.github.h_sib.declared.core.state.SnapshotBuilder
import io.github.h_sib.declared.core.state.StateCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Random

private val T0: Instant = Instant.parse("2026-10-09T10:00:00Z")
private val T1: Instant = Instant.parse("2026-10-10T10:00:00Z")

/** A phone in a few lines: the packages, which accessibility services are on, and the device settings that matter here. */
private fun phone(
    packages: List<PackageFacts>,
    a11yOn: Set<String> = emptySet(),
    lock: Boolean = true,
    patchDaysOld: Long = 20,
): Report {
    val services = packages.map { service(it.pkg) }
    val enabled = services.filter { it.component.pkg in a11yOn }.map { it.component }.toSet()
    val patch = ReadResult.Ok(java.time.LocalDate.parse("2026-10-09").minusDays(patchDaysOld).toString())
    return builder(
        sources(
            accessibility = ReadResult.Ok(reading(services, enabled)),
            packages = FakePackageFactsSource(factsMap(*packages.toTypedArray())),
            device = healthyDevice(patch = patch, lock = ReadResult.Ok(lock)),
        ),
    ).build(34)
}

private fun snap(report: Report) = SnapshotBuilder.of(report)

class DiffTest {
    private val random = Random(20261009)
    private val keys = listOf("app:a", "app:b", "app:c", "holder:x", "holder:y", "fact:P", "finding:f1", "finding:f2")
    private val values = listOf("", "a", "b", "true", "false", "x,y", "\"q\"")

    private fun randomRecords(): Map<String, Record> {
        val m = LinkedHashMap<String, Record>()
        for (k in keys) if (random.nextInt(3) != 0) {
            val fields = (0 until random.nextInt(4)).associate { "f${random.nextInt(4)}" to values[random.nextInt(values.size)] }
            m[k] = Record(fields.toSortedMap())
        }
        return m
    }

    @Test
    fun applyingTheDiffToTheOldGivesTheNewForThousandsOfRandomPairs() {
        repeat(5000) {
            val old = randomRecords()
            val new = randomRecords()
            val changes = Diff.of(old, new)
            assertEquals("old=$old new=$new", new, Diff.apply(old, changes))
        }
    }

    @Test
    fun theInverseDiffGoesBackAndNothingChangesWhenNothingDiffers() {
        repeat(3000) {
            val old = randomRecords()
            val new = randomRecords()
            val changes = Diff.of(old, new)
            assertEquals(old, Diff.apply(new, Diff.invert(changes)))
            assertTrue(Diff.of(old, old).isEmpty())
        }
    }

    @Test
    fun theDiffIsMinimalAndSorted() {
        repeat(2000) {
            val old = randomRecords()
            val new = randomRecords()
            val changes = Diff.of(old, new)
            assertEquals(changes.map { it.key }, changes.map { it.key }.sorted())
            assertEquals(changes.size, changes.map { it.key }.toSet().size)
            for (c in changes) when (c) {
                is Change.Added -> assertTrue(c.key !in old && c.key in new)
                is Change.Removed -> assertTrue(c.key in old && c.key !in new)
                is Change.Modified -> {
                    assertNotEquals(c.before, c.after)
                    assertTrue(c.changedFields.isNotEmpty())
                }
            }
            assertEquals((old.keys + new.keys).count { old[it] != new[it] }, changes.size)
        }
    }

    @Test
    fun aChangeThatDoesNotFitIsRefusedNotApplied() {
        val r = Record.of("a" to "1")
        for (bad in listOf(
            listOf(Change.Added("k", r)), // already there
            listOf(Change.Removed("k", Record.of("a" to "2"))), // not what it expects
            listOf(Change.Modified("k", Record.of("a" to "9"), r)),
            listOf(Change.Removed("missing", r)),
        )) {
            try {
                Diff.apply(mapOf("k" to r), bad)
                org.junit.Assert.fail("applied $bad")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}

class SnapshotTest {
    @Test
    fun aSnapshotHoldsStateNotSentencesAndIsDeterministic() {
        val a = snap(phone(listOf(facts("a.store", label = "Store App"))))
        val b = snap(phone(listOf(facts("a.store", label = "Store App"))))
        assertEquals(a.records, b.records)
        val app = a.records.getValue("app:a.store").fields
        assertEquals("Store App", app["label"])
        assertEquals("STORE", app["tier"])
        assertTrue(a.records.keys.any { it.startsWith("fact:") })
    }

    @Test
    fun theSameStateMadeTwiceDiffersByNothing() {
        val r = phone(listOf(facts("a.store")), a11yOn = setOf("a.store"))
        assertTrue(Diff.of(snap(r).records, snap(r).records).isEmpty())
    }
}

class ChangesTest {
    private val store = facts("a.store", label = "Store App")

    private fun items(old: Report, new: Report) = Changes.summarize(snap(old), snap(new))

    @Test
    fun nothingChangedMeansNothingToSay() {
        val r = phone(listOf(store))
        val s = items(r, r)
        assertTrue(s.items.isEmpty())
        assertEquals(0, s.rawChanges)
        assertFalse(s.needsAttention)
    }

    @Test
    fun aNewAppWithAPowerAndALostScreenLockAreTheTopItems() {
        val old = phone(listOf(store))
        val intruder = facts("n.new", label = "New Thing", installing = null, initiating = "com.android.shell")
        val new = phone(listOf(store, intruder), a11yOn = setOf("n.new"), lock = false)
        val s = items(old, new)
        assertTrue(s.needsAttention)
        val kinds = s.items.map { it.kind }
        assertTrue(kinds.toString(), ChangeKind.APP_ADDED in kinds && ChangeKind.NEW_FINDING in kinds && ChangeKind.SETTING_CHANGED in kinds)
        assertEquals(Attention.LOOK_FIRST, s.items.first().attention)
        assertTrue(s.items.any { it.title == "New app: New Thing (Installed from a computer)" && it.attention == Attention.LOOK })
        assertTrue(s.items.any { it.title == "Screen lock: set, now none" })
        assertTrue(s.items.any { it.kind == ChangeKind.NEW_FINDING && it.title.contains("No screen lock") })
        // The ordering is by attention first.
        assertEquals(s.items.map { it.attention }, s.items.map { it.attention }.sortedDescending())
    }

    @Test
    fun aNewStoreAppIsOnlyANoteAndRemovalIsANote() {
        val old = phone(listOf(store))
        val added = items(old, phone(listOf(store, facts("b.store", label = "Another"))))
        assertEquals(listOf(Attention.NOTE), added.items.map { it.attention })
        val removed = items(phone(listOf(store, facts("b.store", label = "Another"))), old)
        assertEquals(listOf("Another was removed"), removed.items.map { it.title })
    }

    @Test
    fun aDifferentSigningCertificateIsLookFirst() {
        val old = phone(listOf(facts("a.store", label = "Store App", signers = listOf("aa".repeat(32)))))
        val new = phone(listOf(facts("a.store", label = "Store App", signers = listOf("bb".repeat(32)))))
        val s = items(old, new)
        assertEquals(listOf(ChangeKind.SIGNER_CHANGED), s.items.map { it.kind })
        assertEquals(Attention.LOOK_FIRST, s.items.single().attention)
        assertEquals("Store App is now signed by a different certificate", s.items.single().title)
    }

    @Test
    fun newSensitiveGrantsAreNamedInPlainWords() {
        val old = phone(listOf(facts("s.side", label = "Side", installing = null, initiating = null)))
        val new = phone(listOf(facts("s.side", label = "Side", installing = null, initiating = null, granted = setOf("android.permission.RECORD_AUDIO", "android.permission.CAMERA"))))
        val item = items(old, new).items.single()
        assertEquals(ChangeKind.GRANTS_ADDED, item.kind)
        assertEquals(Attention.LOOK, item.attention)
        assertTrue(item.title, item.title.contains("microphone") && item.title.contains("camera"))
    }

    @Test
    fun builtInAppsAreCountedButNotItemized() {
        val old = phone(listOf(store))
        val new = phone(listOf(store, facts("sys.new", system = true), facts("plat.new", platformSigned = true)))
        val s = items(old, new)
        assertTrue(s.items.isEmpty())
        assertTrue(s.rawChanges >= 2)
    }

    @Test
    fun aFindingThatGotWorseIsEscalatedAndOneThatWentAwayIsNoted() {
        val a = items(phone(listOf(store), patchDaysOld = 200), phone(listOf(store), patchDaysOld = 400))
        assertTrue(a.items.toString(), a.items.any { it.kind == ChangeKind.ESCALATED_FINDING && it.attention == Attention.LOOK_FIRST })
        val gone = items(phone(listOf(store), lock = false), phone(listOf(store), lock = true))
        assertTrue(gone.items.any { it.kind == ChangeKind.FINDING_GONE && it.attention == Attention.NOTE })
        assertFalse(gone.needsAttention)
    }

    @Test
    fun aSwitchedOffAbilityIsANoteForAnOutsideApp() {
        val side = facts("s.side", label = "Side", installing = null, initiating = null)
        val s = items(phone(listOf(side), a11yOn = setOf("s.side")), phone(listOf(side)))
        assertTrue(s.items.toString(), s.items.any { it.kind == ChangeKind.SWITCHED_OFF && it.title == "Side no longer has accessibility service switched on" })
    }

    @Test
    fun aChangeOfInstallerIsReportedWithBothWords() {
        val old = phone(listOf(facts("a.app", label = "App")))
        val new = phone(listOf(facts("a.app", label = "App", installing = null, initiating = "com.android.shell")))
        assertTrue(items(old, new).items.any { it.kind == ChangeKind.TIER_CHANGED && it.title == "App: installed from a computer (was from a store)" })
    }
}

class ReviewTest {
    private val side = facts("s.side", label = "Side", installing = null, initiating = null, signers = listOf("aa".repeat(32)))
    private val store = facts("a.store", label = "Store App")
    private val sys = facts("sys.app", label = "Built In", system = true)

    @Test
    fun onlyActiveHoldersFromOutsideThePhonesOwnSoftwareAreAskedAbout() {
        val r = phone(listOf(side, store, sys, facts("idle.app", label = "Idle")), a11yOn = setOf("s.side", "a.store", "sys.app"))
        assertEquals(setOf("s.side", "a.store"), Review.pending(r, null).map { it.pkg }.toSet())
    }

    @Test
    fun establishingStoresAnswersWithSignersAndDefaultsToUnsure() {
        val r = phone(listOf(side, store), a11yOn = setOf("s.side", "a.store"))
        val key = Review.key(r.holders.first { it.pkg == "s.side" })
        val saved = Review.establish(r, mapOf(key to Answer.RECOGNIZED), null, T0)
        assertEquals(Answer.RECOGNIZED, saved.answers.getValue(key).answer)
        assertEquals(listOf("aa".repeat(32)), saved.answers.getValue(key).signerSha256)
        assertEquals(Answer.UNSURE, saved.answers.getValue(Review.key(r.holders.first { it.pkg == "a.store" })).answer)
        assertEquals(T0, saved.baselineAt)
        assertEquals(SavedState.CURRENT_VERSION, saved.version)
    }

    @Test
    fun laterReviewsAskOnlyAboutWhatIsNewAndKeepEarlierAnswers() {
        val first = phone(listOf(side), a11yOn = setOf("s.side"))
        val saved = Review.establish(first, mapOf(Review.key(first.holders.single { it.active }) to Answer.RECOGNIZED), null, T0)
        val later = phone(listOf(side, store), a11yOn = setOf("s.side", "a.store"))
        assertEquals(listOf("a.store"), Review.pending(later, saved).map { it.pkg })
        val again = Review.establish(later, emptyMap(), saved, T1)
        assertEquals(T0, again.baselineAt)
        assertEquals(2, again.answers.size)
        assertEquals(Answer.RECOGNIZED, again.answers.getValue(Review.key(first.holders.single { it.active })).answer)
        assertTrue(Review.pending(later, again).isEmpty())
    }

    @Test
    fun aHolderYouDoNotRecognizeBecomesAFindingAtOnceAndOnlyWhileItIsStillThere() {
        val r = phone(listOf(side), a11yOn = setOf("s.side"))
        val key = Review.key(r.holders.single { it.active })
        val saved = Review.establish(r, mapOf(key to Answer.NOT_RECOGNIZED), null, T0)
        val with = Review.apply(r, saved, T0)
        val f = with.findings.first { it.id == "review:not-recognized:$key" }
        assertEquals(Attention.LOOK_FIRST, f.attention)
        assertEquals("Your own review", f.section)
        assertTrue(f.meaning.contains("does not mean that"))
        assertEquals(with.findings.first().attention, with.findings.maxOf { it.attention })
        // Switched off again: nothing to say.
        val off = Review.apply(phone(listOf(side)), saved, T0)
        assertTrue(off.findings.none { it.id.startsWith("review:") })
        // Recognized or unsure: no finding of its own.
        for (answer in listOf(Answer.RECOGNIZED, Answer.UNSURE)) {
            val s2 = Review.establish(r, mapOf(key to answer), null, T0)
            assertTrue(Review.apply(r, s2, T0).findings.none { it.id.startsWith("review:") })
        }
    }

    @Test
    fun aSigningChangeAfterReviewIsItsOwnFinding() {
        val r = phone(listOf(side), a11yOn = setOf("s.side"))
        val key = Review.key(r.holders.single { it.active })
        val saved = Review.establish(r, mapOf(key to Answer.RECOGNIZED), null, T0)
        val resigned = phone(listOf(side.copy(signerSha256 = listOf("bb".repeat(32)))), a11yOn = setOf("s.side"))
        val f = Review.apply(resigned, saved, T1).findings.first { it.id == "review:signer-changed:$key" }
        assertEquals(Attention.LOOK_FIRST, f.attention)
        assertTrue(f.title.contains("a different signature than when you reviewed it"))
        assertEquals(1, Review.signerChanges(resigned, saved).size)
        assertTrue(Review.signerChanges(r, saved).isEmpty())
    }

    @Test
    fun withNothingToCompareThereIsNoSignerFinding() {
        val noSigners = side.copy(signerSha256 = emptyList())
        val r = phone(listOf(noSigners), a11yOn = setOf("s.side"))
        val key = Review.key(r.holders.single { it.active })
        val saved = Review.establish(r, mapOf(key to Answer.RECOGNIZED), null, T0)
        assertTrue(Review.signerChanges(phone(listOf(side), a11yOn = setOf("s.side")), saved).isEmpty())
    }

    @Test
    fun acknowledgingMovesTheReferenceButKeepsTheAnswers() {
        val r = phone(listOf(side), a11yOn = setOf("s.side"))
        val key = Review.key(r.holders.single { it.active })
        val saved = Review.establish(r, mapOf(key to Answer.RECOGNIZED), null, T0)
        val later = phone(listOf(side, store), a11yOn = setOf("s.side", "a.store"), lock = false)
        assertTrue(Changes.summarize(saved.reviewed, snap(later)).items.isNotEmpty())
        val acked = Review.acknowledge(saved, later)
        assertTrue(Changes.summarize(acked.reviewed, snap(later)).items.isEmpty())
        assertEquals(saved.answers, acked.answers)
    }

    @Test
    fun theBaselineItselfShowsNoChanges() {
        val r = phone(listOf(side, store), a11yOn = setOf("s.side"))
        val saved = Review.establish(r, emptyMap(), null, T0)
        assertTrue(Changes.summarize(saved.reviewed, snap(r)).items.isEmpty())
        assertEquals(0, Changes.summarize(saved.reviewed, snap(r)).rawChanges)
    }

    @Test
    fun applyWithoutAnythingSavedLeavesTheReportAlone() {
        val r = phone(listOf(side), a11yOn = setOf("s.side"))
        assertEquals(r, Review.apply(r, null, T0))
    }
}

class StateCodecTest {
    private fun sampleState(): SavedState {
        val r = phone(listOf(facts("s.side", label = "Side \"Quoted\" \\ back", installing = null, initiating = null), facts("a.store")), a11yOn = setOf("s.side"))
        return Review.establish(r, mapOf(Review.key(r.holders.single { it.active }) to Answer.NOT_RECOGNIZED), null, T0)
    }

    @Test
    fun aRealStateSurvivesTheRoundTripExactly() {
        val s = sampleState()
        val back = StateCodec.decode(StateCodec.encode(s))
        assertEquals(ReadResult.Ok(s), back)
    }

    @Test
    fun anyTextSurvivesIncludingEscapesControlCharactersAndUnicode() {
        val random = Random(7)
        val alphabet = "ab\"\\/\n\r\t\u0001\u001f é中😀{}[],:"
        repeat(2000) {
            val value = (0 until random.nextInt(12)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            val label = (0 until random.nextInt(8)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            val s = SavedState(
                1, T0, mapOf("K:$label" to io.github.h_sib.declared.core.state.HolderAnswer(Answer.UNSURE, listOf(value), T1)),
                Snapshot(T0, 34, mapOf("app:$label" to Record.of("label" to value, "z" to ""))),
            )
            assertEquals("for '$value' / '$label'", ReadResult.Ok(s), StateCodec.decode(StateCodec.encode(s)))
        }
    }

    @Test
    fun aStateForAHugePhoneRoundTripsInReasonableTimeAndSize() {
        // 5,000 apps, each with its holder, in the saved snapshot: far beyond any real phone.
        val records = LinkedHashMap<String, Record>()
        for (i in 0 until 5000) {
            records["app:com.example.app$i"] = Record.of("label" to "App number $i", "tier" to "STORE", "signers" to "ab".repeat(32), "grants" to "CAMERA,RECORD_AUDIO", "kinds" to "", "launcher" to "true")
            if (i % 5 == 0) records["holder:ACCESSIBILITY:com.example.app$i/com.example.Service"] = Record.of("kind" to "ACCESSIBILITY", "pkg" to "com.example.app$i", "label" to "App number $i", "sub" to "", "active" to "true", "tier" to "STORE", "powers" to "gestures,window-content")
        }
        val answers = (0 until 1000).associate { "ACCESSIBILITY:com.example.app$it/com.example.Service" to io.github.h_sib.declared.core.state.HolderAnswer(Answer.RECOGNIZED, listOf("ab".repeat(32)), T0) }
        val state = SavedState(1, T0, answers, Snapshot(T0, 34, records))
        val t0 = System.nanoTime()
        val text = StateCodec.encode(state)
        val back = StateCodec.decode(text)
        val millis = (System.nanoTime() - t0) / 1e6
        assertEquals(ReadResult.Ok(state), back)
        assertTrue("encode and decode took $millis ms", millis < 5000)
        assertTrue("the state is ${text.length} characters", text.length < 3_000_000)
    }

    @Test
    fun aVersionOneFileIsStillReadAndItsMissingDeclarationsReadAsUnanswered() {
        val v1 = StateCodec.encode(sampleState().copy(version = 1)).replace(Regex(",\"declarations\":\\{[^}]*\\}"), "")
        assertTrue("the declarations were removed", !v1.contains("declarations") && v1.contains("\"version\":1"))
        val read = StateCodec.decode(v1)
        assertTrue(read.toString(), read is ReadResult.Ok)
        val saved = (read as ReadResult.Ok).value
        assertEquals(io.github.h_sib.declared.core.model.Declarations(), saved.declarations)
        assertEquals(1, saved.version)
        assertEquals(sampleState().answers, saved.answers)
    }

    @Test
    fun declarationsRoundTripAndEveryAnswerHasItsOwnWords() {
        for (sms in io.github.h_sib.declared.core.model.Told.entries) for (wifi in io.github.h_sib.declared.core.model.Told.entries) for (profile in io.github.h_sib.declared.core.model.Profile.entries) {
            val d = io.github.h_sib.declared.core.model.Declarations(sms, wifi, profile)
            val s = sampleState().copy(declarations = d)
            assertEquals(ReadResult.Ok(s), StateCodec.decode(StateCodec.encode(s)))
        }
    }

    @Test
    fun everyProperPrefixOfAValidStateIsRefused() {
        val text = StateCodec.encode(sampleState())
        for (n in 0 until text.length) {
            assertTrue("prefix of $n characters was accepted", StateCodec.decode(text.substring(0, n)) is ReadResult.Failed)
        }
        assertTrue(StateCodec.decode(text) is ReadResult.Ok)
    }

    @Test
    fun wrongVersionsMissingFieldsAndUnknownAnswersAreRefusedWithAReason() {
        val good = StateCodec.encode(sampleState())
        val broken = listOf(
            good.replace("\"version\":2", "\"version\":3"),
            good.replace("\"version\":2", "\"version\":0"),
            good.replace("\"version\":2,", ""),
            good.replace("HIGH_RISK", "EXTREME").replace("\"profile\":\"GENERAL\"", "\"profile\":\"EXTREME\""),
            good.replace("\"smsCodes\":\"UNSURE\"", "\"smsCodes\":\"MAYBE\""),
            good.replace("\"declarations\":{", "\"declared\":{"),
            good.replace("NOT_RECOGNIZED", "MAYBE"),
            good.replace("\"baselineAt\":\"2026-10-09T10:00:00Z\"", "\"baselineAt\":\"yesterday\""),
            good.replace("\"androidApi\":34", "\"androidApi\":\"34\""),
            "[]", "null", "{}", "not json", "",
        )
        for (text in broken) {
            val r = StateCodec.decode(text)
            assertTrue("accepted: ${text.take(60)}", r is ReadResult.Failed)
        }
    }
}
