package io.github.h_sib.declared.core.ledger

import io.github.h_sib.declared.core.state.Record
import io.github.h_sib.declared.core.state.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class LastTripwireTest {
    private val t0 = Instant.parse("2026-10-09T10:00:00Z")
    private val signer = SoftwareSigner()

    private fun add(l: Ledger, seconds: Long, vararg events: String): Ledger {
        val at = t0.plusSeconds(seconds)
        val snap = Snapshot(at, 34, mapOf("app:a" to Record.of("k" to seconds.toString())))
        return (l.append(snap, at, Taken.OPENED, "1", events.toList(), signer) as AppendResult.Appended).ledger
    }

    @Test
    fun aHistoryWithNoTripwireEventHasNone() {
        assertNull(History.of(add(Ledger.begin(t0, signer), 0, LedgerEvents.ARMED_CREDENTIAL), null).lastTripwire)
    }

    @Test
    fun theNewestFiringEventIsKeptAfterLaterLooksThatSayNothing() {
        var l = add(Ledger.begin(t0, signer), 0, LedgerEvents.ARMED_CREDENTIAL)
        l = add(l, 100, LedgerEvents.FINGERPRINT_ADDED)
        l = add(l, 200)
        l = add(l, 300, LedgerEvents.LOCK_REMOVED)
        l = add(l, 400, LedgerEvents.ARMED_CREDENTIAL)
        l = add(l, 500)
        assertEquals(LastTripwire(LedgerEvents.LOCK_REMOVED, t0.plusSeconds(300)), History.of(l, null).lastTripwire)
    }

    @Test
    fun anOlderEventIsFoundWhenTheNewerEntriesHaveNone() {
        var l = add(Ledger.begin(t0, signer), 0, LedgerEvents.FINGERPRINT_ADDED)
        l = add(l, 100)
        l = add(l, 200, LedgerEvents.ARMED_BIOMETRIC)
        assertEquals(LastTripwire(LedgerEvents.FINGERPRINT_ADDED, t0), History.of(l, null).lastTripwire)
    }
}
