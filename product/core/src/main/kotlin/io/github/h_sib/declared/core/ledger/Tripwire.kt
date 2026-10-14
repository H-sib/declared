package io.github.h_sib.declared.core.ledger

/**
 * The tripwires (STAGE3_DESIGN.md section 5, `FINDINGS.md` Q3). Two Keystore keys that the system destroys on its own: one
 * that needs a strong biometric (so it dies when a fingerprint is added, and when the lock is removed) and one that also
 * accepts the device credential (so it dies only when the lock is removed). A changed PIN trips neither, which is said on the
 * screen. What this file decides is what the readings may honestly be taken to mean: a missing key alone is ambiguous
 * (reinstall, cleared data, a restore), so it is always read together with the lock screen.
 */
enum class KeyState { VALID, INVALIDATED, MISSING, UNREADABLE }

data class KeyReading(val state: KeyState, val reason: String? = null)

data class TripwireReading(val biometric: KeyReading, val credential: KeyReading)

/** Which of the two keys this app created and has not seen destroyed, worked out from the history so it survives a restart. */
data class Armed(val biometric: Boolean, val credential: Boolean) {
    val any: Boolean get() = biometric || credential

    companion object {
        val NONE = Armed(biometric = false, credential = false)

        fun of(ledger: Ledger?): Armed {
            var bio = false
            var cred = false
            for (e in ledger?.entries.orEmpty()) {
                // The events of one entry have no order (they are sorted in the stored text), and a key that fired and was set again in
                // the same turn is armed afterwards, so what fired is applied first and what was set second.
                for (code in e.events) {
                    when (code) {
                        LedgerEvents.FINGERPRINT_ADDED -> bio = false
                        LedgerEvents.LOCK_REMOVED, LedgerEvents.CANNOT_TELL -> { bio = false; cred = false }
                    }
                }
                for (code in e.events) {
                    when (code) {
                        LedgerEvents.ARMED_BIOMETRIC -> bio = true
                        LedgerEvents.ARMED_CREDENTIAL -> cred = true
                    }
                }
            }
            return Armed(bio, cred)
        }
    }
}

sealed interface Verdict {
    /** Neither key was touched. Says nothing about a changed PIN, which trips neither. */
    data object NothingSeen : Verdict

    data object FingerprintAdded : Verdict

    data object LockRemoved : Verdict

    data class CannotTell(val why: String) : Verdict

    /** No key could be set. [why] is shown, because the absence of a tripwire is itself something to know. */
    data class NotPossible(val why: String) : Verdict

    /** Nothing was set up before, so there is nothing to compare with yet. */
    data object NotSetUpYet : Verdict
}

data class Judgement(
    val verdict: Verdict,
    /** Event codes to write into the history now. */
    val events: List<String>,
    /** Keys that are gone and should be made again if the phone allows it. */
    val rearmBiometric: Boolean,
    val rearmCredential: Boolean,
)

object Tripwires {
    private val GONE = setOf(KeyState.INVALIDATED, KeyState.MISSING)

    /** @param lockSet whether the screen lock exists now, read separately (`KeyguardManager.isDeviceSecure`); null if it could not be read. */
    fun judge(armed: Armed, reading: TripwireReading, lockSet: Boolean?): Judgement {
        if (!armed.any) return Judgement(Verdict.NotSetUpYet, emptyList(), rearmBiometric = true, rearmCredential = true)
        val bio = if (armed.biometric) reading.biometric else null
        val cred = if (armed.credential) reading.credential else null
        val unreadable = listOfNotNull(bio, cred).firstOrNull { it.state == KeyState.UNREADABLE }
        if (unreadable != null) {
            // Nothing is concluded and nothing is written: an unreadable key is not an event.
            return Judgement(Verdict.CannotTell("A tripwire key could not be read" + (unreadable.reason?.let { ": $it" } ?: ".")), emptyList(), false, false)
        }
        val credGone = cred?.state in GONE
        val bioGone = bio?.state in GONE
        return when {
            // Lock removal destroys both keys; a key that is merely missing is never explained by a fingerprint, and an invalidated one is only
            // explained by a fingerprint while the lock still exists (the keys of a phone whose lock was removed may read as invalidated).
            credGone || (bioGone && (bio?.state == KeyState.MISSING || lockSet == false)) -> when (lockSet) {
                false -> Judgement(Verdict.LockRemoved, listOf(LedgerEvents.LOCK_REMOVED), rearmBiometric = true, rearmCredential = true)
                true -> Judgement(
                    Verdict.CannotTell("The tripwire keys are gone while a screen lock exists: the lock may have been removed and set again, or the app was reinstalled or its data cleared."),
                    listOf(LedgerEvents.CANNOT_TELL), rearmBiometric = true, rearmCredential = true,
                )
                null -> Judgement(Verdict.CannotTell("The tripwire keys are gone and the screen lock could not be read."), emptyList(), false, false)
            }
            bioGone -> Judgement(Verdict.FingerprintAdded, listOf(LedgerEvents.FINGERPRINT_ADDED), rearmBiometric = true, rearmCredential = false)
            else -> Judgement(Verdict.NothingSeen, emptyList(), false, false)
        }
    }

    fun words(v: Verdict): String = when (v) {
        Verdict.NothingSeen -> "Nothing these tripwires can notice has happened since they were set. A changed PIN trips neither of them."
        Verdict.FingerprintAdded -> "A fingerprint was added since this app last looked. It may have been you."
        Verdict.LockRemoved -> "The screen lock was removed since this app last looked. It may have been you."
        is Verdict.CannotTell -> v.why
        is Verdict.NotPossible -> "No tripwire is possible right now: ${v.why}"
        Verdict.NotSetUpYet -> "The tripwires are being set up."
    }
}
