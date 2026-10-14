package io.github.h_sib.declared.core.ledger

import io.github.h_sib.declared.core.state.Record
import io.github.h_sib.declared.core.state.Snapshot
import java.time.Instant

/** A signed history of one entry, for tests of what sits around the ledger (the reducer, the screens). */
fun ledgerWithOneEntry(at: Instant = Instant.parse("2026-10-09T10:00:00Z")): Ledger {
    val signer = SoftwareSigner()
    val first = Snapshot(at, 34, mapOf("app:a" to Record.of("label" to "A", "tier" to "STORE")))
    return (Ledger.begin(at, signer).append(first, at, Taken.OPENED, "0.3.0", emptyList(), signer) as AppendResult.Appended).ledger
}
