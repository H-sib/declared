package io.github.h_sib.declared.core.ledger

import io.github.h_sib.declared.core.paths.Tri
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.state.SnapshotBuilder
import java.time.Instant

enum class TripwireKind { BIOMETRIC, CREDENTIAL }

sealed interface ArmOutcome {
    data object Armed : ArmOutcome

    /** [why] is shown on the screen: that a tripwire is not possible on this phone is itself worth knowing. */
    data class NotPossible(val why: String) : ArmOutcome
}

/** The two Keystore keys. The Android side makes and reads them; tests pass fakes whose keys die on command. */
interface TripwireKeys {
    fun arm(kind: TripwireKind): ArmOutcome

    fun read(): TripwireReading
}

/** What the screen says about the tripwires right now. */
data class TripwireStatus(val verdict: Verdict, val notPossible: Map<TripwireKind, String>)

data class StepResult(
    val ledger: Ledger,
    val appended: LedgerEntry?,
    val tripwire: TripwireStatus,
)

/**
 * One turn of keeping the history (STAGE3_DESIGN.md sections 3 to 5), with nothing Android in it: look at the tripwire keys,
 * work out what they mean together with the lock screen, set again whatever is gone, and add an entry when the phone changed,
 * a tripwire fired or a key was set. The caller supplies the clock, the signer and the keys, so every path (a key that cannot be
 * read, a phone with no lock, a signer that fails) is exercised on the JVM.
 */
class HistoryKeeper(private val signer: EntrySigner?, private val keys: TripwireKeys, private val appVersion: String) {

    fun step(previous: Ledger?, report: Report, at: Instant, taken: Taken): StepResult {
        val ledger = previous ?: Ledger.begin(at, signer)
        val armed = Armed.of(ledger)
        val lockSet = when (report.phone.of(io.github.h_sib.declared.core.paths.StateKey.NO_SCREEN_LOCK).value) {
            Tri.TRUE -> false
            Tri.FALSE -> true
            Tri.UNKNOWN -> null
        }
        val reading = if (armed.any) keys.read() else TripwireReading(KeyReading(KeyState.MISSING), KeyReading(KeyState.MISSING))
        val judgement = Tripwires.judge(armed, reading, lockSet)

        val events = ArrayList(judgement.events)
        val notPossible = LinkedHashMap<TripwireKind, String>()
        // What is armed after this turn: what was armed and has not fired, plus whatever can be set again now.
        var armedBio = armed.biometric && Verdict.FingerprintAdded != judgement.verdict && !firedBoth(judgement)
        var armedCred = armed.credential && !firedBoth(judgement)
        fun tryArm(kind: TripwireKind) {
            when (val outcome = keys.arm(kind)) {
                ArmOutcome.Armed -> {
                    events += if (kind == TripwireKind.BIOMETRIC) LedgerEvents.ARMED_BIOMETRIC else LedgerEvents.ARMED_CREDENTIAL
                    if (kind == TripwireKind.BIOMETRIC) armedBio = true else armedCred = true
                }
                is ArmOutcome.NotPossible -> notPossible[kind] = outcome.why
            }
        }
        if (!armedBio && (judgement.rearmBiometric || !armed.biometric)) tryArm(TripwireKind.BIOMETRIC)
        if (!armedCred && (judgement.rearmCredential || !armed.credential)) tryArm(TripwireKind.CREDENTIAL)

        val verdict = when {
            judgement.verdict != Verdict.NotSetUpYet -> judgement.verdict
            armedBio || armedCred -> Verdict.NothingSeen
            else -> Verdict.NotPossible(notPossible.values.distinct().joinToString("; ").ifEmpty { "no key could be set" })
        }
        val snapshot = SnapshotBuilder.of(report).let { io.github.h_sib.declared.core.state.Snapshot(at, it.androidApi, it.records) }
        val result = ledger.append(snapshot, at, taken, appVersion, events, signer)
        return when (result) {
            is AppendResult.Appended -> StepResult(result.ledger, result.entry, TripwireStatus(verdict, notPossible))
            is AppendResult.Skipped -> StepResult(ledger, null, TripwireStatus(verdict, notPossible))
        }
    }

    private fun firedBoth(j: Judgement) = LedgerEvents.LOCK_REMOVED in j.events || LedgerEvents.CANNOT_TELL in j.events
}
