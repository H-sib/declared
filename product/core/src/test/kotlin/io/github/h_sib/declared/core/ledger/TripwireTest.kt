package io.github.h_sib.declared.core.ledger

import io.github.h_sib.declared.core.state.Record
import io.github.h_sib.declared.core.state.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TripwireTest {
    private fun r(s: KeyState, why: String? = null) = KeyReading(s, why)

    private fun judge(armed: Armed, bio: KeyState, cred: KeyState, lock: Boolean?) =
        Tripwires.judge(armed, TripwireReading(r(bio, if (bio == KeyState.UNREADABLE) "boom" else null), r(cred, if (cred == KeyState.UNREADABLE) "boom" else null)), lock)

    private val both = Armed(biometric = true, credential = true)

    // ------------------------------------------------------------ the table of STAGE3_DESIGN.md section 5

    @Test
    fun bothKeysFineMeansNothingTheTripwiresCanSeeHappened() {
        val j = judge(both, KeyState.VALID, KeyState.VALID, lock = true)
        assertEquals(Verdict.NothingSeen, j.verdict)
        assertTrue(j.events.isEmpty() && !j.rearmBiometric && !j.rearmCredential)
    }

    @Test
    fun theBiometricKeyGoneWithTheCredentialKeyFineAndALockMeansAFingerprintWasAdded() {
        val j = judge(both, KeyState.INVALIDATED, KeyState.VALID, lock = true)
        assertEquals(Verdict.FingerprintAdded, j.verdict)
        assertEquals(listOf(LedgerEvents.FINGERPRINT_ADDED), j.events)
        assertTrue(j.rearmBiometric && !j.rearmCredential)
    }

    @Test
    fun bothKeysGoneAndNoLockMeansTheLockWasRemoved() {
        for (state in listOf(KeyState.INVALIDATED, KeyState.MISSING)) {
            val j = judge(both, state, state, lock = false)
            assertEquals(state.name, Verdict.LockRemoved, j.verdict)
            assertEquals(listOf(LedgerEvents.LOCK_REMOVED), j.events)
            assertTrue(j.rearmBiometric && j.rearmCredential)
        }
    }

    @Test
    fun bothKeysGoneWhileALockExistsIsNeverExplainedBecauseALockCanBeRemovedAndSetAgainOrTheAppReinstalled() {
        val j = judge(both, KeyState.MISSING, KeyState.MISSING, lock = true)
        assertTrue(j.verdict is Verdict.CannotTell)
        assertEquals(listOf(LedgerEvents.CANNOT_TELL), j.events)
        assertTrue(j.verdict.let { it is Verdict.CannotTell && it.why.contains("reinstalled") })
    }

    @Test
    fun nothingWasSetUpBeforeSoThereIsNothingToCompareWith() {
        val j = judge(Armed.NONE, KeyState.MISSING, KeyState.MISSING, lock = true)
        assertEquals(Verdict.NotSetUpYet, j.verdict)
        assertTrue(j.rearmBiometric && j.rearmCredential && j.events.isEmpty())
    }

    @Test
    fun aKeyThatCannotBeReadConcludesNothingAndWritesNothing() {
        val j = judge(both, KeyState.UNREADABLE, KeyState.VALID, lock = true)
        assertTrue(j.verdict.let { it is Verdict.CannotTell && it.why.contains("boom") })
        assertTrue(j.events.isEmpty() && !j.rearmBiometric && !j.rearmCredential)
    }

    @Test
    fun aLockThatCannotBeReadMeansNoClaimAboutWhyTheKeysAreGone() {
        val j = judge(both, KeyState.MISSING, KeyState.MISSING, lock = null)
        assertTrue(j.verdict is Verdict.CannotTell)
        assertTrue(j.events.isEmpty())
    }

    @Test
    fun aPhoneWithOnlyTheCredentialKeyArmedCannotTellAFingerprintAndSaysNothingAboutOne() {
        val j = judge(Armed(biometric = false, credential = true), KeyState.INVALIDATED, KeyState.VALID, lock = true)
        assertEquals("the biometric key is not ours to read", Verdict.NothingSeen, j.verdict)
    }

    @Test
    fun aBiometricOnlyPhoneWhoseLockWasRemovedReadsAsLockRemovedEvenIfItsKeyShowsAsInvalidated() {
        val j = judge(Armed(biometric = true, credential = false), KeyState.INVALIDATED, KeyState.MISSING, lock = false)
        assertEquals(Verdict.LockRemoved, j.verdict)
    }

    // ------------------------------------------------------------ every combination

    @Test
    fun noCombinationOfReadingsEverBlamesAFingerprintWithoutALockOrALockRemovalWithOne() {
        var cases = 0
        for (armedBio in listOf(false, true)) for (armedCred in listOf(false, true))
            for (bio in KeyState.entries) for (cred in KeyState.entries) for (lock in listOf(true, false, null)) {
                val armed = Armed(armedBio, armedCred)
                val j = judge(armed, bio, cred, lock)
                cases += 1
                val where = "armed=$armed bio=$bio cred=$cred lock=$lock -> ${j.verdict}"
                if (!armed.any) assertEquals(where, Verdict.NotSetUpYet, j.verdict)
                if (j.verdict == Verdict.FingerprintAdded) assertTrue(where, lock != false && armedBio && j.events == listOf(LedgerEvents.FINGERPRINT_ADDED))
                if (j.verdict == Verdict.LockRemoved) assertTrue(where, lock == false && j.events == listOf(LedgerEvents.LOCK_REMOVED))
                if (j.verdict is Verdict.CannotTell && j.events.isNotEmpty()) assertTrue(where, lock == true && j.events == listOf(LedgerEvents.CANNOT_TELL))
                // a key the person did not have set up cannot be the reason for any verdict
                if (!armedBio && !armedCred) assertTrue(where, j.events.isEmpty())
                // events are only ever written for something that cannot un-happen
                assertTrue(where, j.events.size <= 1)
                val armedKeys = listOfNotNull(if (armedBio) bio else null, if (armedCred) cred else null)
                if (armedKeys.isNotEmpty() && armedKeys.all { it == KeyState.VALID }) assertEquals(where, Verdict.NothingSeen, j.verdict)
            }
        assertEquals(2 * 2 * 4 * 4 * 3, cases)
    }

    // ------------------------------------------------------------ what is armed, worked out from the history

    private fun ledgerWith(vararg events: List<String>): Ledger {
        val signer = SoftwareSigner()
        var l = Ledger.begin(Instant.parse("2026-10-09T10:00:00Z"), signer)
        events.forEachIndexed { i, e ->
            val at = Instant.parse("2026-10-09T10:00:00Z").plusSeconds(i * 100L)
            l = (l.append(Snapshot(at, 34, mapOf("app:a" to Record.of("k" to i.toString()))), at, Taken.OPENED, "1", e, signer) as AppendResult.Appended).ledger
        }
        return l
    }

    @Test
    fun whatIsArmedFollowsTheHistoryOfEventsInOrder() {
        assertEquals(Armed.NONE, Armed.of(null))
        assertEquals(Armed.NONE, Armed.of(ledgerWith(emptyList())))
        assertEquals(Armed(true, true), Armed.of(ledgerWith(listOf(LedgerEvents.ARMED_BIOMETRIC, LedgerEvents.ARMED_CREDENTIAL))))
        assertEquals("a fingerprint trips only the biometric key", Armed(false, true), Armed.of(ledgerWith(listOf(LedgerEvents.ARMED_BIOMETRIC, LedgerEvents.ARMED_CREDENTIAL), listOf(LedgerEvents.FINGERPRINT_ADDED))))
        assertEquals("and it can be armed again", Armed(true, true), Armed.of(ledgerWith(listOf(LedgerEvents.ARMED_BIOMETRIC, LedgerEvents.ARMED_CREDENTIAL), listOf(LedgerEvents.FINGERPRINT_ADDED), listOf(LedgerEvents.ARMED_BIOMETRIC))))
        assertEquals("lock removal ends both", Armed.NONE, Armed.of(ledgerWith(listOf(LedgerEvents.ARMED_BIOMETRIC, LedgerEvents.ARMED_CREDENTIAL), listOf(LedgerEvents.LOCK_REMOVED))))
        assertEquals(Armed.NONE, Armed.of(ledgerWith(listOf(LedgerEvents.ARMED_CREDENTIAL), listOf(LedgerEvents.CANNOT_TELL))))
        assertFalse(Armed.NONE.any)
    }

    @Test
    fun aKeyThatFiredAndWasSetAgainInTheSameEntryIsArmedWhateverOrderTheEventsAreStoredIn() {
        for (events in listOf(
            listOf(LedgerEvents.FINGERPRINT_ADDED, LedgerEvents.ARMED_BIOMETRIC),
            listOf(LedgerEvents.ARMED_BIOMETRIC, LedgerEvents.FINGERPRINT_ADDED),
        )) {
            val a = Armed.of(ledgerWith(listOf(LedgerEvents.ARMED_BIOMETRIC, LedgerEvents.ARMED_CREDENTIAL), events))
            assertEquals(Armed(true, true), a)
        }
        val both = listOf(LedgerEvents.CANNOT_TELL, LedgerEvents.ARMED_BIOMETRIC, LedgerEvents.ARMED_CREDENTIAL)
        assertEquals(Armed(true, true), Armed.of(ledgerWith(listOf(LedgerEvents.ARMED_BIOMETRIC), both)))
    }

    @Test
    fun everyVerdictHasWordsThatNeverAccuse() {
        for (v in listOf(Verdict.NothingSeen, Verdict.FingerprintAdded, Verdict.LockRemoved, Verdict.CannotTell("the keys are gone and nothing explains it"), Verdict.NotPossible("no screen lock"), Verdict.NotSetUpYet)) {
            val w = Tripwires.words(v)
            assertTrue(w.length > 10)
        }
        assertTrue(Tripwires.words(Verdict.FingerprintAdded).contains("It may have been you"))
        assertTrue(Tripwires.words(Verdict.NothingSeen).contains("A changed PIN trips neither"))
    }
}
