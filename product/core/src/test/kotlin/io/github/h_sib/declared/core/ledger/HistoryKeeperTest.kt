package io.github.h_sib.declared.core.ledger

import io.github.h_sib.declared.core.healthyDevice
import io.github.h_sib.declared.core.paths.phone
import io.github.h_sib.declared.core.sensor.ReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Keys that die on command, like the Keystore's do when a fingerprint is added or the lock is removed. */
class FakeKeys : TripwireKeys {
    var bio = KeyState.MISSING
    var cred = KeyState.MISSING
    var armBio: ArmOutcome = ArmOutcome.Armed
    var armCred: ArmOutcome = ArmOutcome.Armed
    var unreadable: String? = null
    val armCalls = mutableListOf<TripwireKind>()

    override fun arm(kind: TripwireKind): ArmOutcome {
        armCalls += kind
        val outcome = if (kind == TripwireKind.BIOMETRIC) armBio else armCred
        if (outcome == ArmOutcome.Armed) {
            if (kind == TripwireKind.BIOMETRIC) bio = KeyState.VALID else cred = KeyState.VALID
        }
        return outcome
    }

    override fun read(): TripwireReading {
        unreadable?.let { return TripwireReading(KeyReading(KeyState.UNREADABLE, it), KeyReading(KeyState.UNREADABLE, it)) }
        return TripwireReading(KeyReading(bio), KeyReading(cred))
    }
}

class HistoryKeeperTest {
    private val t0 = Instant.parse("2026-10-09T10:00:00Z")
    private val signer = SoftwareSigner()
    private val locked = phone()
    private val unlocked = phone(device = healthyDevice(lock = ReadResult.Ok(false)))
    private fun keeper(keys: FakeKeys, s: EntrySigner? = signer) = HistoryKeeper(s, keys, "0.3.0")

    @Test
    fun theFirstTurnStartsTheHistoryAndSetsBothTripwires() {
        val keys = FakeKeys()
        val r = keeper(keys).step(null, locked, t0, Taken.OPENED)
        assertNotNull(r.appended)
        assertEquals(1L, r.appended!!.seq)
        assertEquals(listOf(LedgerEvents.ARMED_BIOMETRIC, LedgerEvents.ARMED_CREDENTIAL), r.appended.events.sorted())
        assertEquals(Verdict.NothingSeen, r.tripwire.verdict)
        assertEquals(Armed(true, true), Armed.of(r.ledger))
        assertTrue(r.ledger.verify().authentic)
    }

    @Test
    fun aSecondTurnWithNothingNewWritesNothingButAnOldOneWritesAHeartbeat() {
        val keys = FakeKeys()
        var r = keeper(keys).step(null, locked, t0, Taken.OPENED)
        val again = keeper(keys).step(r.ledger, locked, t0.plusSeconds(3600), Taken.ON_SCREEN)
        assertNull(again.appended)
        assertEquals(Verdict.NothingSeen, again.tripwire.verdict)
        r = keeper(keys).step(r.ledger, locked, t0.plus(LedgerPolicy.HEARTBEAT).plusSeconds(1), Taken.OPENED)
        assertNotNull(r.appended)
        assertTrue(r.appended!!.changes.isEmpty() && r.appended.events.isEmpty())
        assertEquals(2, r.ledger.entries.size)
    }

    @Test
    fun aFingerprintAddedIsWrittenOnceAndTheTripwireIsSetAgain() {
        val keys = FakeKeys()
        var r = keeper(keys).step(null, locked, t0, Taken.OPENED)
        keys.bio = KeyState.INVALIDATED
        r = keeper(keys).step(r.ledger, locked, t0.plusSeconds(60), Taken.OPENED)
        assertEquals(Verdict.FingerprintAdded, r.tripwire.verdict)
        assertEquals(listOf(LedgerEvents.ARMED_BIOMETRIC, LedgerEvents.FINGERPRINT_ADDED), r.appended!!.events.sorted())
        assertEquals(KeyState.VALID, keys.bio)
        val next = keeper(keys).step(r.ledger, locked, t0.plusSeconds(120), Taken.OPENED)
        assertEquals("the same event is not reported twice", Verdict.NothingSeen, next.tripwire.verdict)
        assertNull(next.appended)
        assertEquals(1, next.ledger.entries.count { LedgerEvents.FINGERPRINT_ADDED in it.events })
    }

    @Test
    fun aLockRemovedIsWrittenAndWithNoLockTheTripwiresCannotBeSetAgain() {
        val keys = FakeKeys()
        var r = keeper(keys).step(null, locked, t0, Taken.OPENED)
        keys.bio = KeyState.MISSING
        keys.cred = KeyState.MISSING
        keys.armBio = ArmOutcome.NotPossible("no screen lock")
        keys.armCred = ArmOutcome.NotPossible("no screen lock")
        r = keeper(keys).step(r.ledger, unlocked, t0.plusSeconds(60), Taken.OPENED)
        assertEquals(Verdict.LockRemoved, r.tripwire.verdict)
        assertEquals(listOf(LedgerEvents.LOCK_REMOVED), r.appended!!.events)
        assertEquals(Armed.NONE, Armed.of(r.ledger))
        assertEquals(setOf(TripwireKind.BIOMETRIC, TripwireKind.CREDENTIAL), r.tripwire.notPossible.keys)
        // later, still no lock: no tripwire is possible, said as such, nothing new written
        val later = keeper(keys).step(r.ledger, unlocked, t0.plusSeconds(120), Taken.OPENED)
        assertTrue(later.tripwire.verdict is Verdict.NotPossible)
        assertNull(later.appended)
        // a lock set again arms them again
        keys.armBio = ArmOutcome.Armed
        keys.armCred = ArmOutcome.Armed
        val locked2 = keeper(keys).step(later.ledger, locked, t0.plusSeconds(180), Taken.OPENED)
        assertEquals(Verdict.NothingSeen, locked2.tripwire.verdict)
        assertEquals(Armed(true, true), Armed.of(locked2.ledger))
    }

    @Test
    fun keysGoneWhileALockExistsAreNeverBlamedOnAnyoneAndAreSetAgain() {
        val keys = FakeKeys()
        var r = keeper(keys).step(null, locked, t0, Taken.OPENED)
        keys.bio = KeyState.MISSING
        keys.cred = KeyState.MISSING
        r = keeper(keys).step(r.ledger, locked, t0.plusSeconds(60), Taken.OPENED)
        assertTrue(r.tripwire.verdict is Verdict.CannotTell)
        assertTrue(r.appended!!.events.contains(LedgerEvents.CANNOT_TELL))
        assertEquals(Armed(true, true), Armed.of(r.ledger))
    }

    @Test
    fun aPhoneThatNeverHadALockGetsNoTripwireAndSaysWhy() {
        val keys = FakeKeys()
        keys.armBio = ArmOutcome.NotPossible("no screen lock")
        keys.armCred = ArmOutcome.NotPossible("no screen lock")
        val r = keeper(keys).step(null, unlocked, t0, Taken.OPENED)
        val v = r.tripwire.verdict
        assertTrue(v is Verdict.NotPossible && v.why.contains("no screen lock"))
        assertTrue(r.appended!!.events.isEmpty())
        assertEquals(Armed.NONE, Armed.of(r.ledger))
    }

    @Test
    fun aPhoneWithALockButNoFingerprintHasOnlyTheLockTripwire() {
        val keys = FakeKeys()
        keys.armBio = ArmOutcome.NotPossible("no fingerprint is enrolled")
        val r = keeper(keys).step(null, locked, t0, Taken.OPENED)
        assertEquals(Armed(biometric = false, credential = true), Armed.of(r.ledger))
        assertEquals(Verdict.NothingSeen, r.tripwire.verdict)
        assertEquals("no fingerprint is enrolled", r.tripwire.notPossible[TripwireKind.BIOMETRIC])
        // a fingerprint is enrolled later: the next turn sets that tripwire, and says so in the history
        keys.armBio = ArmOutcome.Armed
        val later = keeper(keys).step(r.ledger, locked, t0.plusSeconds(60), Taken.OPENED)
        assertEquals(listOf(LedgerEvents.ARMED_BIOMETRIC), later.appended!!.events)
    }

    @Test
    fun aKeyThatCannotBeReadConcludesNothingWritesNothingAndKeepsTheKeys() {
        val keys = FakeKeys()
        var r = keeper(keys).step(null, locked, t0, Taken.OPENED)
        keys.unreadable = "the key store is busy"
        val before = keys.armCalls.size
        r = keeper(keys).step(r.ledger, locked, t0.plusSeconds(60), Taken.OPENED)
        assertTrue(r.tripwire.verdict is Verdict.CannotTell)
        assertNull(r.appended)
        assertEquals("nothing is set again because nothing was destroyed", before, keys.armCalls.size)
        assertEquals(Armed(true, true), Armed.of(r.ledger))
    }

    @Test
    fun aPhoneChangeIsRecordedWithTheTripwireAsTheyHappenTogether() {
        val keys = FakeKeys()
        var r = keeper(keys).step(null, locked, t0, Taken.OPENED)
        r = keeper(keys).step(r.ledger, phone(network = io.github.h_sib.declared.core.network(proxy = "10.0.0.2:3128")), t0.plusSeconds(60), Taken.ON_SCREEN)
        assertTrue(r.appended!!.changes.isNotEmpty())
        assertEquals(Taken.ON_SCREEN, r.appended.taken)
        assertTrue(r.ledger.verify().authentic)
    }

    @Test
    fun withNoSigningKeyTheHistoryStillGrowsAndSaysItIsNotSigned() {
        val keys = FakeKeys()
        val r = keeper(keys, s = null).step(null, locked, t0, Taken.OPENED)
        assertEquals(KeyHeld.NONE, r.ledger.header.keyHeld)
        assertFalse(r.ledger.verify().authentic)
        assertTrue(r.ledger.verify().intact)
    }
}
