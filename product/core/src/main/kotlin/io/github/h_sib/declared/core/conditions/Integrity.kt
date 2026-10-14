package io.github.h_sib.declared.core.conditions

import io.github.h_sib.declared.core.integrity.ChainReport
import io.github.h_sib.declared.core.integrity.ChainVerifier
import io.github.h_sib.declared.core.integrity.KeyAttestation
import io.github.h_sib.declared.core.integrity.ReferenceData
import io.github.h_sib.declared.core.integrity.attestedPatchDate
import io.github.h_sib.declared.core.integrity.hex
import io.github.h_sib.declared.core.model.Action
import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.model.Availability
import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.DeviceFact
import io.github.h_sib.declared.core.model.FactKind
import io.github.h_sib.declared.core.model.Finding
import io.github.h_sib.declared.core.model.Observation
import io.github.h_sib.declared.core.model.Provenance
import io.github.h_sib.declared.core.model.SettingsLink
import io.github.h_sib.declared.core.model.SettingsTarget
import io.github.h_sib.declared.core.model.SignalId
import io.github.h_sib.declared.core.model.SignalStatus
import io.github.h_sib.declared.core.model.Signals
import io.github.h_sib.declared.core.model.SourceInfo
import io.github.h_sib.declared.core.model.Subject
import io.github.h_sib.declared.core.model.Value
import io.github.h_sib.declared.core.sensor.AttestationEvidence
import io.github.h_sib.declared.core.sensor.ReadResult
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

object IntegritySources {
    val ATTESTATION = SourceInfo(
        api = "Android Keystore key attestation (KeyGenParameterSpec.setAttestationChallenge), chain checked offline with java.security",
        minApi = 24,
    )
    val REFERENCE = SourceInfo(api = "Google's published attestation roots and certificate status list, bundled with the app", minApi = 1)
}

/** A revocation list older than this is reported as stale and lowers confidence: entries added since are unknown. */
object ReferenceAge {
    const val STALE_DAYS = 180L
}

private const val SECTION = "Hardware check of this phone's software"
private const val CHECKED_HERE = "This check ran on the phone itself. A separate trusted server would be a stronger judge, and leaked attestation keys can fool it until Google revokes them."

private fun finding(
    id: String, title: String, subject: String, attention: Attention, claim: Observation, meaning: String,
    cannotTell: List<String>, actions: List<Action>, settings: SettingsTarget,
) = Finding(
    id = id, title = title, subject = subject, section = SECTION, attention = attention, claim = listOf(claim), context = emptyList(),
    meaning = meaning, whatItCannotTell = cannotTell, actions = actions, settings = SettingsLink(settings),
)

private fun reasonOf(r: ReadResult<*>): String = when (r) {
    is ReadResult.Refused -> "Android refused the request: ${r.reason}"
    is ReadResult.Failed -> "The read failed unexpectedly: ${r.reason}"
    is ReadResult.Ok -> error("not an error result")
}

private val ALL_SIGNALS = listOf(Signals.ATTESTATION_CHAIN, Signals.BOOT_STATE, Signals.BOOTLOADER, Signals.ATTESTED_PATCH)
private val ALL_KINDS = listOf(FactKind.KEY_ATTESTATION, FactKind.BOOT_STATE, FactKind.BOOTLOADER, FactKind.ATTESTED_PATCH)

private fun unreadable(reason: String, signalsReason: String = reason): ConditionOutput = ConditionOutput(
    facts = ALL_KINDS.map { DeviceFact(it, "${it.label}: not readable. $reason", Availability.Unavailable(reason), null) },
    findings = emptyList(),
    statuses = ALL_SIGNALS.map { SignalStatus(it, Availability.Unavailable(signalsReason), IntegritySources.ATTESTATION) },
)

/**
 * The device-integrity panel: verify the attestation chain offline, then read the boot state, bootloader and attested
 * patch level from it, but only believe those fields when the chain is trusted and hardware-backed. Software
 * attestation (emulators, rare phones) is reported as exactly that, never as a pass.
 */
internal fun mapIntegrity(
    evidence: ReadResult<AttestationEvidence>,
    reference: ReadResult<ReferenceData>,
    softwarePatch: String?,
    now: Instant,
    at: Instant,
    verifier: ChainVerifier = ChainVerifier(),
): ConditionOutput {
    val ev = (evidence as? ReadResult.Ok)?.value ?: return unreadable(reasonOf(evidence))
    val ref = (reference as? ReadResult.Ok)?.value
        ?: return unreadable("The bundled reference data could not be used (${reasonOf(reference)}), so the chain cannot be judged.")

    return assessIntegrity(verifier.verify(ev.chainDer, ref, now), ev.challenge, ref.retrievedOn, softwarePatch, now, at)
}

/** The judging half, separate so that tests can hand it any chain report: hardware-backed, expired, revoked, and so on. */
internal fun assessIntegrity(
    report: ChainReport,
    challenge: ByteArray,
    retrievedOn: LocalDate?,
    softwarePatch: String?,
    now: Instant,
    at: Instant,
): ConditionOutput {
    val device = Subject.Device
    val today = now.atZone(java.time.ZoneOffset.UTC).toLocalDate()
    val refAgeDays = retrievedOn?.let { ChronoUnit.DAYS.between(it, today) }
    val stale = refAgeDays != null && refAgeDays > ReferenceAge.STALE_DAYS
    val refText = retrievedOn?.let { "reference data from $it" + (if (refAgeDays != null && refAgeDays > 0) ", $refAgeDays days old" else "") } ?: "reference data of unknown age"
    val confidence = if (stale) Confidence.LOW else Confidence.MEDIUM
    val staleLimit = if (stale) listOf("The bundled revocation list is $refAgeDays days old, so certificates revoked since then are not known to this check.") else emptyList()
    val limits = listOf(CHECKED_HERE) + staleLimit

    fun obs(id: SignalId, value: Value, extra: List<String> = emptyList()) =
        Observation(id, device, value, Provenance.OBSERVED, confidence, IntegritySources.ATTESTATION, limits + extra, at)

    val refStatus = SignalStatus(
        Signals.ATTESTATION_REFERENCE,
        if (stale) Availability.Degraded("The bundled revocation list is $refAgeDays days old.") else Availability.Available,
        IntegritySources.REFERENCE,
    )
    val statuses = ArrayList<SignalStatus>()
    statuses += refStatus
    val facts = ArrayList<DeviceFact>()
    val findings = ArrayList<Finding>()

    val attestation = report.attestation
    if (attestation == null) {
        val reason = report.unparsable ?: report.attestationError ?: "no attestation record was found"
        return unreadable(reason, reason).let { it.copy(statuses = it.statuses + refStatus) }
    }
    val challengeMatches = attestation.challengeHex == hex(challenge)

    // ---- software-only attestation: say so, and say what it cannot show
    if (!attestation.hardwareBacked) {
        val summary = "Hardware key check: not possible. This phone keeps its keys in software, so the check cannot say anything about how the phone started or whether its system software was changed."
        facts += DeviceFact(FactKind.KEY_ATTESTATION, summary, Availability.Available, obs(Signals.ATTESTATION_CHAIN, Value.Text("software")))
        for (k in listOf(FactKind.BOOT_STATE, FactKind.BOOTLOADER, FactKind.ATTESTED_PATCH)) {
            facts += DeviceFact(k, "${k.label}: not established (software keys cannot vouch for it).", Availability.Available, null)
        }
        statuses += SignalStatus(Signals.ATTESTATION_CHAIN, Availability.Degraded("Software attestation only: the phone has no hardware-backed key to check."), IntegritySources.ATTESTATION)
        statuses += listOf(Signals.BOOT_STATE, Signals.BOOTLOADER, Signals.ATTESTED_PATCH).map {
            SignalStatus(it, Availability.Unavailable("Not established: the keys are in software."), IntegritySources.ATTESTATION)
        }
        findings += finding(
            id = "condition:software-keys", title = "This phone's keys live in software, so no hardware vouches for them", subject = "Key attestation level: software", attention = Attention.NOTE,
            claim = obs(Signals.ATTESTATION_CHAIN, Value.Text("software")),
            meaning = "Phones normally keep cryptographic keys in a separate hardware area that even the main system cannot read, and that area can sign a record of how the phone started. " +
                "This phone's keys are held in software, which is normal for an emulator and unusual for a real phone. It means this check cannot say how the phone started or whether its system software was changed.",
            cannotTell = listOf(
                "How the phone started or whether its system software is the maker's: nothing here can vouch for it.",
                "Whether the phone is an emulator, a very old model or a model built without the hardware area.",
                CHECKED_HERE,
            ),
            actions = listOf(
                Action(
                    "Nothing to change. If this is a real phone you rely on, check the phone's maker for whether the model supports hardware key storage.",
                    "There is no cost; this is information, not a setting.",
                ),
            ),
            settings = SettingsTarget.DEVICE_INFO,
        )
        return ConditionOutput(facts, findings, statuses)
    }

    // ---- hardware-backed: believe the fields only if the chain checks out and answers this request
    val trusted = report.trusted && challengeMatches
    val problems = report.problems() + (if (!challengeMatches) listOf("the chain answers a different request than the one this app made") else emptyList())
    statuses += SignalStatus(
        Signals.ATTESTATION_CHAIN,
        if (trusted) Availability.Available else Availability.Degraded("The chain did not pass: ${problems.joinToString("; ")}."),
        IntegritySources.ATTESTATION,
    )
    val level = attestation.securityLevelName
    val chainObs = obs(Signals.ATTESTATION_CHAIN, Value.Text(if (trusted) "trusted" else "not trusted"))
    if (!trusted) {
        val reasons = problems.joinToString("; ")
        facts += DeviceFact(FactKind.KEY_ATTESTATION, "Hardware key check: did not pass ($reasons).", Availability.Available, chainObs)
        for (k in listOf(FactKind.BOOT_STATE, FactKind.BOOTLOADER, FactKind.ATTESTED_PATCH)) {
            facts += DeviceFact(k, "${k.label}: not established, because the hardware key check did not pass.", Availability.Available, null)
        }
        statuses += listOf(Signals.BOOT_STATE, Signals.BOOTLOADER, Signals.ATTESTED_PATCH).map {
            SignalStatus(it, Availability.Unavailable("Not established: the hardware key check did not pass."), IntegritySources.ATTESTATION)
        }
        findings += finding(
            id = "condition:attestation-failed", title = "The hardware check on this phone failed, so its system may not be what it claims", subject = "Hardware key check: $reasons", attention = Attention.LOOK_FIRST, claim = chainObs,
            meaning = "Phones can sign a record, with a key held in hardware, of how they started and which software they run. This phone's record could not be traced back to a certificate that Google publishes for this purpose, " +
                "or was changed, or contains a certificate on Google's list of certificates that must no longer be trusted. That points to a problem with this check or with the phone's hardware keys. " +
                "It does not by itself show that the phone has been tampered with.",
            cannotTell = listOf(
                "Whether the phone's software was changed: the record that would show it cannot be trusted.",
                "Whether the cause is the phone, a damaged key, an out-of-date bundled list or this app.",
                CHECKED_HERE,
            ),
            actions = listOf(
                Action(
                    "Restart the phone and open this app again to see whether the result repeats.",
                    "A restart closes running apps and may take a minute.",
                ),
                Action(
                    "If it repeats and you rely on this phone for banking or work, ask its maker or a repair shop you trust about the hardware key check.",
                    "A repair visit costs time and may cost money; the phone can stay in use meanwhile.",
                ),
            ),
            settings = SettingsTarget.DEVICE_INFO,
        )
        return ConditionOutput(facts, findings, statuses)
    }

    facts += DeviceFact(
        FactKind.KEY_ATTESTATION,
        "Hardware key check: passed. The phone's key is held in $level and its certificate chain leads back to a certificate that Google publishes for this purpose ($refText).",
        Availability.Available, chainObs,
    )
    addBootFacts(attestation, facts, findings, statuses, ::obs)
    addPatchFact(attestation, softwarePatch, facts, findings, statuses, ::obs)
    return ConditionOutput(facts, findings, statuses)
}

private fun addBootFacts(
    a: KeyAttestation,
    facts: MutableList<DeviceFact>,
    findings: MutableList<Finding>,
    statuses: MutableList<SignalStatus>,
    obs: (SignalId, Value, List<String>) -> Observation,
) {
    val state = a.verifiedBootState
    val locked = a.deviceLocked
    if (state == null) {
        facts += DeviceFact(FactKind.BOOT_STATE, "Boot state: the record does not say.", Availability.Degraded("The attestation record has no start-up entry."), null)
        facts += DeviceFact(FactKind.BOOTLOADER, "Bootloader: the record does not say.", Availability.Degraded("The attestation record has no start-up entry."), null)
        statuses += listOf(Signals.BOOT_STATE, Signals.BOOTLOADER).map {
            SignalStatus(it, Availability.Degraded("The attestation record has no start-up entry."), IntegritySources.ATTESTATION)
        }
        return
    }
    val stateObs = obs(Signals.BOOT_STATE, Value.Text(a.bootStateName ?: "unknown"), emptyList())
    facts += DeviceFact(FactKind.BOOT_STATE, "Boot state: ${a.bootStateName}.", Availability.Available, stateObs)
    statuses += SignalStatus(Signals.BOOT_STATE, Availability.Available, IntegritySources.ATTESTATION)
    if (locked == null) {
        facts += DeviceFact(FactKind.BOOTLOADER, "Bootloader: the record does not say.", Availability.Degraded("The record has no lock flag."), null)
        statuses += SignalStatus(Signals.BOOTLOADER, Availability.Degraded("The record has no lock flag."), IntegritySources.ATTESTATION)
    } else {
        val lockObs = obs(Signals.BOOTLOADER, Value.Flag(locked), emptyList())
        facts += DeviceFact(FactKind.BOOTLOADER, "Bootloader: ${if (locked) "locked" else "unlocked"}.", Availability.Available, lockObs)
        statuses += SignalStatus(Signals.BOOTLOADER, Availability.Available, IntegritySources.ATTESTATION)
        if (!locked) {
            findings += finding(
                id = "condition:bootloader-unlocked", title = "This phone's bootloader is unlocked, so any system software can be put on it", subject = "Bootloader state from the hardware record", attention = Attention.LOOK_FIRST, claim = lockObs,
                meaning = "The bootloader is the first program that runs when the phone starts. When it is unlocked, someone holding the phone can replace its system software, and the system can no longer vouch for itself. " +
                    "People unlock it on purpose to install other software, and some banking and work apps refuse phones in this state. This reading comes from a record signed with a key held in hardware.",
                cannotTell = listOf(
                    "Who unlocked it, or when.",
                    "Whether the software on the phone is the maker's or something else.",
                    CHECKED_HERE,
                ),
                actions = listOf(
                    Action(
                        "If you did not unlock it yourself, treat the phone as one that someone else may have changed, and think about what you keep on it.",
                        "Nothing changes on the phone; this is a way of deciding how much to trust it.",
                    ),
                    Action(
                        "To go back to the maker's software, ask the maker or a repair shop you trust about restoring it.",
                        "Restoring erases the phone, so copy out what you need first.",
                    ),
                ),
                settings = SettingsTarget.DEVELOPER,
            )
            return // an unlocked bootloader already explains a boot state that is not "verified"
        }
    }
    if (state != 0L) {
        val serious = state != 1L
        findings += finding(
            id = "condition:boot-state",
            title = if (state == 1L) "This phone runs system software signed by its owner, not its maker" else "The phone's system software failed its start-up check, so it may have been changed",
            subject = "Boot state from the hardware record: ${a.bootStateName}",
            attention = if (serious) Attention.LOOK_FIRST else Attention.LOOK, claim = stateObs,
            meaning = if (state == 1L) {
                "When a phone starts, it checks that its system software is signed by a key it knows. Here the key is not the maker's but one set by the phone's owner, which is how custom system software is installed on purpose."
            } else {
                "When a phone starts, it checks that its system software is signed by a key it knows. This phone reports that the check did not succeed, which can mean altered, damaged or unknown system software."
            },
            cannotTell = listOf(
                "Who set the key or why.",
                "Whether the software is harmless: custom software is common among people who build their own.",
                CHECKED_HERE,
            ),
            actions = listOf(
                Action(
                    "If you did not set this up yourself, treat the phone as one that someone else may have changed.",
                    "Nothing changes on the phone; this is a way of deciding how much to trust it.",
                ),
            ),
            settings = SettingsTarget.DEVICE_INFO,
        )
    }
}

private fun addPatchFact(
    a: KeyAttestation,
    softwarePatch: String?,
    facts: MutableList<DeviceFact>,
    findings: MutableList<Finding>,
    statuses: MutableList<SignalStatus>,
    obs: (SignalId, Value, List<String>) -> Observation,
) {
    val attested = attestedPatchDate(a.osPatchLevel)
    if (attested == null) {
        facts += DeviceFact(FactKind.ATTESTED_PATCH, "Security update the hardware attests: the record does not say.", Availability.Degraded("No readable patch level in the record."), null)
        statuses += SignalStatus(Signals.ATTESTED_PATCH, Availability.Degraded("No readable patch level in the record."), IntegritySources.ATTESTATION)
        return
    }
    val reported = softwarePatch?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }
    val attestedMonth = YearMonth.from(attested)
    val o = obs(Signals.ATTESTED_PATCH, Value.Text(attestedMonth.toString()), emptyList())
    statuses += SignalStatus(Signals.ATTESTED_PATCH, Availability.Available, IntegritySources.ATTESTATION)
    if (reported == null) {
        facts += DeviceFact(FactKind.ATTESTED_PATCH, "Security update the hardware attests: $attestedMonth (the phone's software reports none to compare).", Availability.Available, o)
        return
    }
    val reportedMonth = YearMonth.from(reported)
    if (!attestedMonth.isBefore(reportedMonth)) {
        facts += DeviceFact(FactKind.ATTESTED_PATCH, "Security update the hardware attests: $attestedMonth. This agrees with the phone's own report ($reported).", Availability.Available, o)
        return
    }
    facts += DeviceFact(
        FactKind.ATTESTED_PATCH,
        "Security update the hardware attests: $attestedMonth, older than the phone's own report ($reported).",
        Availability.Available, o,
    )
    findings += finding(
        id = "condition:patch-mismatch", title = "The phone claims a newer security update than its hardware confirms", subject = "Attested $attestedMonth, reported $reported", attention = Attention.LOOK, claim = o,
        meaning = "The hardware record says the system's security update is from $attestedMonth, while the phone's own software says $reported. A phone that was updated in the normal way gives the same month in both. " +
            "The difference can mean an update that the record does not show, a rollback to older software, or software that reports a date it does not have.",
        cannotTell = listOf(
            "Which of the two is right: the hardware record is harder to forge, but some makers update parts of the system separately.",
            "Whether the difference is harmless.",
            CHECKED_HERE,
        ),
        actions = listOf(
            Action(
                "Open About phone and look for a software update. Install it if one is offered, then open this app again.",
                "The phone restarts and can be slow for a while, so keep it charged and on Wi-Fi.",
            ),
        ),
        settings = SettingsTarget.DEVICE_INFO,
    )
}
