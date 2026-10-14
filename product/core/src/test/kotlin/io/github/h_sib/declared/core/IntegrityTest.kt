package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.conditions.assessIntegrity
import io.github.h_sib.declared.core.conditions.mapIntegrity
import io.github.h_sib.declared.core.integrity.ChainLink
import io.github.h_sib.declared.core.integrity.ChainReport
import io.github.h_sib.declared.core.integrity.KeyAttestation
import io.github.h_sib.declared.core.integrity.ReferenceData
import io.github.h_sib.declared.core.integrity.RevocationEntry
import io.github.h_sib.declared.core.integrity.RevocationList
import io.github.h_sib.declared.core.integrity.hex
import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.model.Availability
import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.FactKind
import io.github.h_sib.declared.core.model.Signals
import io.github.h_sib.declared.core.sensor.AttestationEvidence
import io.github.h_sib.declared.core.sensor.ReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** The device-integrity panel: what a verified chain, a failed one and a software one each produce. */
class IntegrityTest {
    private val challenge = ByteArray(32) { 5 }
    private val today = Instant.parse("2026-10-09T12:00:00Z")
    private val fetched = LocalDate.of(2026, 10, 8)

    private fun attestation(level: Long = 1, boot: Long? = 0, locked: Boolean? = true, patch: Long? = 202609, challengeHex: String = hex(challenge)) =
        KeyAttestation(300, level, 300, level, challengeHex, boot, locked, "aa", "bb", 140000, patch, 20260905, 20260905)

    private fun link(i: Int, sig: Boolean?, valid: Boolean = true) = ChainLink(i, "CN=c$i", "$i", sig, if (sig == false) "SignatureException" else null, valid, if (valid) null else "CertificateExpiredException")

    private fun report(
        trusted: Boolean = true,
        att: KeyAttestation? = attestation(),
        expired: Boolean = false,
        legacy: Boolean = false,
        revoked: Boolean = false,
        brokenLink: Boolean = false,
    ) = ChainReport(
        links = listOf(link(0, !brokenLink), link(1, true, !expired), link(2, null, !expired)),
        endsAtTrustedRoot = trusted, rootDetail = "x", legacyRoot = legacy,
        revoked = if (revoked) listOf(1 to RevocationEntry("REVOKED", "KEY_COMPROMISE")) else emptyList(),
        unparsable = null, attestation = att, attestationError = if (att == null) "no record" else null,
    )

    private fun assess(r: ChainReport, software: String? = "2026-09-05", retrievedOn: LocalDate? = fetched, challenge: ByteArray = this.challenge) =
        assessIntegrity(r, challenge, retrievedOn, software, today, today)

    private fun ids(o: io.github.h_sib.declared.core.conditions.ConditionOutput) = o.findings.map { it.id }

    // ------------------------------------------------------------ the good case

    @Test
    fun aTrustedHardwareChainWithAVerifiedLockedBootAndMatchingPatchHasNothingToSay() {
        val o = assess(report())
        assertTrue(o.findings.toString(), o.findings.isEmpty())
        assertEquals(listOf(FactKind.KEY_ATTESTATION, FactKind.BOOT_STATE, FactKind.BOOTLOADER, FactKind.ATTESTED_PATCH), o.facts.map { it.kind })
        assertTrue(o.facts.all { it.availability == Availability.Available })
        assertTrue(o.facts[0].summary, o.facts[0].summary.startsWith("Hardware key check: passed"))
        assertTrue(o.facts[0].summary.contains("reference data from 2026-10-08, 1 days old"))
        assertTrue(o.facts[1].summary.contains("verified"))
        assertTrue(o.facts[2].summary.contains("locked"))
        assertTrue(o.facts[3].summary.contains("agrees"))
        assertTrue(o.statuses.all { it.availability == Availability.Available })
    }

    @Test
    fun everyIntegrityObservationIsDeviceSideAndSaysSo() {
        val o = assess(report())
        for (fact in o.facts) fact.observation?.let {
            assertEquals(Confidence.MEDIUM, it.confidence)
            assertTrue(it.limits.any { l -> l.contains("ran on the phone itself") })
        }
    }

    // ------------------------------------------------------------ bootloader and boot state

    @Test
    fun anUnlockedBootloaderIsLookFirstAndSuppressesTheBootStateFinding() {
        val o = assess(report(att = attestation(boot = 2, locked = false)))
        assertEquals(listOf("condition:bootloader-unlocked"), ids(o))
        assertEquals(Attention.LOOK_FIRST, o.findings.single().attention)
        assertTrue(o.findings.single().meaning.contains("can no longer vouch for itself"))
        assertTrue(o.facts[2].summary.contains("unlocked"))
    }

    @Test
    fun customSoftwareIsLookAndAFailedStartUpCheckIsLookFirst() {
        val selfSigned = assess(report(att = attestation(boot = 1)))
        assertEquals(listOf("condition:boot-state"), ids(selfSigned))
        assertEquals(Attention.LOOK, selfSigned.findings.single().attention)
        for (state in listOf(2L, 3L)) {
            val o = assess(report(att = attestation(boot = state)))
            assertEquals("state $state", listOf("condition:boot-state"), ids(o))
            assertEquals(Attention.LOOK_FIRST, o.findings.single().attention)
        }
    }

    @Test
    fun aRecordWithoutARootOfTrustIsDegradedNotGuessed() {
        val o = assess(report(att = attestation(boot = null, locked = null)))
        assertTrue(o.findings.isEmpty())
        assertTrue(o.facts[1].availability is Availability.Degraded)
        assertTrue(o.facts[2].availability is Availability.Degraded)
    }

    // ------------------------------------------------------------ the chain does not check out

    @Test
    fun anUntrustedHardwareChainIsALookFirstFindingThatEstablishesNothingElse() {
        for (r in listOf(
            report(trusted = false), report(brokenLink = true), report(revoked = true), report(expired = true),
        )) {
            val o = assess(r)
            assertEquals(listOf("condition:attestation-failed"), ids(o))
            val f = o.findings.single()
            assertEquals(Attention.LOOK_FIRST, f.attention)
            assertTrue(f.subject, f.subject.startsWith("Hardware key check: "))
            assertTrue(f.meaning.contains("does not by itself show"))
            assertTrue(o.facts.drop(1).all { it.summary.contains("not established") })
            assertTrue(o.statuses.filter { it.id == Signals.BOOT_STATE }.single().availability is Availability.Unavailable)
        }
    }

    @Test
    fun theFailureNamesItsReasons() {
        assertTrue(assess(report(brokenLink = true)).findings.single().subject.contains("not signed by the next one"))
        assertTrue(assess(report(revoked = true)).findings.single().subject.contains("revocation list"))
        assertTrue(assess(report(expired = true)).findings.single().subject.contains("validity dates"))
    }

    @Test
    fun anExpiredLegacyChainIsStillTrusted() {
        val o = assess(report(expired = true, legacy = true))
        assertTrue(o.findings.toString(), o.findings.isEmpty())
        assertTrue(o.facts[0].summary.startsWith("Hardware key check: passed"))
    }

    @Test
    fun aChainThatAnswersADifferentRequestIsNotTrusted() {
        val o = assess(report(att = attestation(challengeHex = "00".repeat(32))))
        assertEquals(listOf("condition:attestation-failed"), ids(o))
        assertTrue(o.findings.single().subject.contains("different request"))
    }

    // ------------------------------------------------------------ the patch cross-check

    @Test
    fun anAttestedPatchOlderThanTheReportedOneIsLook() {
        val o = assess(report(att = attestation(patch = 202603)), software = "2026-09-05")
        assertEquals(listOf("condition:patch-mismatch"), ids(o))
        assertEquals(Attention.LOOK, o.findings.single().attention)
        assertTrue(o.findings.single().subject.contains("Attested 2026-03, reported 2026-09-05"))
    }

    @Test
    fun equalOrNewerAttestedPatchesMakeNoFinding() {
        assertTrue(assess(report(att = attestation(patch = 202609)), software = "2026-09-05").findings.isEmpty())
        assertTrue(assess(report(att = attestation(patch = 202611)), software = "2026-09-05").findings.isEmpty())
        // The month is what is compared: a software date earlier in the same month does not disagree.
        assertTrue(assess(report(att = attestation(patch = 202609)), software = "2026-09-30").findings.isEmpty())
    }

    @Test
    fun anUnreadableSoftwareDateOrAttestedPatchIsAFactNotAFinding() {
        for (sw in listOf(null, "", "garbage")) {
            val o = assess(report(), software = sw)
            assertTrue(o.findings.isEmpty())
            assertTrue(o.facts[3].summary.contains("reports none to compare"))
        }
        val o = assess(report(att = attestation(patch = null)))
        assertTrue(o.facts[3].availability is Availability.Degraded)
        assertTrue(o.findings.isEmpty())
    }

    // ------------------------------------------------------------ software attestation

    @Test
    fun softwareAttestationIsNotAPassAndSaysWhatItCannotShow() {
        val o = assess(report(trusted = false, att = attestation(level = 0, boot = 2, locked = false)))
        assertEquals(listOf("condition:software-keys"), ids(o))
        assertEquals(Attention.NOTE, o.findings.single().attention)
        assertTrue(o.facts[0].summary.contains("not possible"))
        assertTrue(o.facts.drop(1).all { it.summary.contains("not established") })
        assertTrue(o.statuses.first { it.id == Signals.ATTESTATION_CHAIN }.availability is Availability.Degraded)
        // A software record's boot flags are never believed, even though they say "unlocked".
        assertFalse(ids(o).contains("condition:bootloader-unlocked"))
    }

    // ------------------------------------------------------------ reference data age

    @Test
    fun aStaleRevocationListLowersConfidenceAndSaysSo() {
        val o = assess(report(), retrievedOn = LocalDate.of(2026, 1, 1))
        val ref = o.statuses.first { it.id == Signals.ATTESTATION_REFERENCE }.availability
        assertTrue(ref is Availability.Degraded)
        val obs = o.facts[0].observation!!
        assertEquals(Confidence.LOW, obs.confidence)
        assertTrue(obs.limits.any { it.contains("days old") })
        assertEquals(Availability.Available, assess(report()).statuses.first { it.id == Signals.ATTESTATION_REFERENCE }.availability)
    }

    @Test
    fun theStalenessBoundaryIs180Days() {
        fun conf(days: Long) = assess(report(), retrievedOn = LocalDate.of(2026, 10, 9).minusDays(days)).facts[0].observation!!.confidence
        assertEquals(Confidence.MEDIUM, conf(180))
        assertEquals(Confidence.LOW, conf(181))
    }

    // ------------------------------------------------------------ unreadable inputs and the real fixture

    private fun reference(roots: List<ByteArray>) = ReferenceData(roots, RevocationList(emptyMap()), fetched)

    @Test
    fun unreadableEvidenceOrReferenceMakesEveryRowUnavailableWithTheReason() {
        for (case in listOf(
            Triple(ReadResult.Refused("no keystore"), ReadResult.Ok(reference(emptyList())), "Android refused"),
            Triple(ReadResult.Failed("ProviderException"), ReadResult.Ok(reference(emptyList())), "failed unexpectedly"),
        )) {
            val o = mapIntegrity(case.first, case.second, "2026-09-05", today, today)
            assertEquals(4, o.facts.size)
            assertTrue(o.facts.all { it.availability is Availability.Unavailable && it.summary.contains(case.third) })
            assertTrue(o.findings.isEmpty())
        }
        val chainEvidence = ReadResult.Ok(AttestationEvidence(Fixture.chain(), Fixture.challenge()))
        val o = mapIntegrity(chainEvidence, ReadResult.Refused("checksum mismatch"), "2026-09-05", today, today)
        assertTrue(o.facts.all { it.availability is Availability.Unavailable && it.summary.contains("reference data could not be used") })
    }

    @Test
    fun theRealEmulatorChainIsReportedAsSoftwareKeysWhetherOrNotItsRootIsKnown() {
        val evidence = ReadResult.Ok(AttestationEvidence(Fixture.chain(), Fixture.challenge()))
        for (roots in listOf(listOf(Fixture.chain().last()), emptyList())) {
            val o = mapIntegrity(evidence, ReadResult.Ok(reference(roots)), "2023-09-05", Fixture.NOW, Fixture.NOW)
            assertEquals(listOf("condition:software-keys"), ids(o))
            assertTrue(o.facts[0].summary.contains("not possible"))
        }
    }

    @Test
    fun aChainWithNoAttestationRecordIsUnavailableNotAPass() {
        val evidence = ReadResult.Ok(AttestationEvidence(Fixture.chain().drop(1), Fixture.challenge()))
        val o = mapIntegrity(evidence, ReadResult.Ok(reference(listOf(Fixture.chain().last()))), "2023-09-05", Fixture.NOW, Fixture.NOW)
        assertTrue(o.facts.all { it.availability is Availability.Unavailable })
        assertTrue(o.findings.isEmpty())
    }

    @Test
    fun theReportBuilderCarriesTheIntegrityFactsAndFindings() {
        val evidence = ReadResult.Ok(AttestationEvidence(Fixture.chain(), Fixture.challenge()))
        val report = builder(sources(attestation = evidence, reference = ReadResult.Ok(reference(listOf(Fixture.chain().last()))))).build(34)
        assertTrue(report.findings.any { it.id == "condition:software-keys" })
        assertEquals(FactKind.entries.toList(), report.facts.map { it.kind })
        assertTrue(report.signals.any { it.id == Signals.ATTESTATION_REFERENCE })
    }
}
