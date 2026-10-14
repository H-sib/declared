package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.integrity.ChainVerifier
import io.github.h_sib.declared.core.integrity.ReferenceData
import io.github.h_sib.declared.core.integrity.RevocationList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate

/**
 * The chain verifier against a real Android attestation chain (an emulator's software chain, see [Fixture]): genuine
 * X.509 material, so signatures, validity dates, serials and the attestation record are all real. The roots are chosen per
 * test, which lets every outcome be reached honestly: trusted, wrong anchor, broken link, expired, revoked.
 */
class ChainVerifierTest {
    private val chain = Fixture.chain()
    private val verifier = ChainVerifier()
    private val noRevocations = RevocationList(emptyMap())

    private fun reference(roots: List<ByteArray>, revocations: RevocationList = noRevocations) = ReferenceData(roots, revocations, LocalDate.of(2026, 10, 8))

    private fun flipLastByte(der: ByteArray) = der.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }

    @Test
    fun theFixtureIsAThreeCertificateChainWithASoftwareAttestation() {
        assertEquals(3, chain.size)
        val r = verifier.verify(chain, reference(listOf(chain.last())), Fixture.NOW)
        val a = r.attestation
        assertNotNull(a)
        assertEquals(0L, a!!.attestationSecurityLevel)
        assertFalse(a.hardwareBacked)
        assertEquals(2L, a.verifiedBootState)
        assertEquals(false, a.deviceLocked)
        assertEquals(202309L, a.osPatchLevel)
        assertEquals(io.github.h_sib.declared.core.integrity.hex(Fixture.challenge()), a.challengeHex)
    }

    @Test
    fun aChainAnchoredAtItsOwnTopIsTrustedAndSaysWhy() {
        val r = verifier.verify(chain, reference(listOf(chain.last())), Fixture.NOW)
        assertTrue(r.problems().toString(), r.trusted)
        assertTrue(r.endsAtTrustedRoot)
        assertTrue(r.rootDetail, r.rootDetail.contains("is trusted root #0"))
        assertEquals(listOf(true, true, null), r.links.map { it.signatureOk })
        assertTrue(r.problems().isEmpty())
        assertFalse(r.legacyRoot)
    }

    @Test
    fun aChainIsAlsoTrustedWhenItsTopIsSignedByTheAnchor() {
        // Anchor at the root and give the verifier only the first two certificates: the second one is signed by the anchor.
        val r = verifier.verify(chain.take(2), reference(listOf(chain.last())), Fixture.NOW)
        assertTrue(r.problems().toString(), r.trusted)
        assertTrue(r.rootDetail, r.rootDetail.contains("is signed by trusted root #0"))
    }

    @Test
    fun withoutTheRightAnchorTheChainIsNotTrustedEvenThoughEveryLinkIsSigned() {
        for (roots in listOf(emptyList(), listOf(chain[0]), listOf(chain[1]))) {
            val r = verifier.verify(chain, reference(roots), Fixture.NOW)
            assertTrue(r.brokenLinks.isEmpty())
            assertFalse(r.endsAtTrustedRoot)
            assertFalse(r.trusted)
            assertTrue(r.problems().any { it.contains("does not lead back to a certificate that Google publishes") })
        }
    }

    @Test
    fun oneFlippedBitInAnyCertificateBreaksTheLinkAboveIt() {
        val anchored = reference(listOf(chain.last()))
        for (index in 0..1) {
            val tampered = chain.mapIndexed { i, der -> if (i == index) flipLastByte(der) else der }
            val r = verifier.verify(tampered, anchored, Fixture.NOW)
            assertEquals("index $index", listOf(index), r.brokenLinks)
            assertFalse(r.trusted)
            assertTrue(r.problems().any { it.contains("not signed by the next one") })
        }
    }

    @Test
    fun aChainWhoseRootIsReplacedByTheTamperedRootIsNotTrusted() {
        val tamperedTop = flipLastByte(chain.last())
        val r = verifier.verify(chain.take(2) + tamperedTop, reference(listOf(chain.last())), Fixture.NOW)
        assertFalse(r.trusted)
    }

    @Test
    fun certificatesOutsideTheirDatesAreReportedAndDistrusted() {
        val anchored = reference(listOf(chain.last()))
        val late = verifier.verify(chain, anchored, Instant.parse("2027-01-01T00:00:00Z"))
        assertEquals(listOf(1, 2), late.expired)
        assertFalse(late.trusted)
        assertTrue(late.problems().any { it.contains("outside its validity dates") })
        val early = verifier.verify(chain, anchored, Instant.parse("2026-10-01T00:00:00Z"))
        assertEquals(listOf(1, 2), early.expired)
        assertFalse(early.trusted)
    }

    @Test
    fun theLegacyRootRuleAcceptsExpiredCertificatesButNothingElse() {
        // The rule is keyed on a marker found in the top certificate's names; this chain's top contains "droid unregistered device ca".
        val legacy = ChainVerifier("droid unregistered device ca")
        val anchored = reference(listOf(chain.last()))
        val late = legacy.verify(chain, anchored, Instant.parse("2027-01-01T00:00:00Z"))
        assertTrue(late.legacyRoot)
        assertEquals(listOf(1, 2), late.expired)
        assertTrue(late.problems().toString(), late.trusted)
        // Legacy status never excuses a broken link, a missing anchor or a revoked certificate.
        val tampered = chain.mapIndexed { i, der -> if (i == 0) flipLastByte(der) else der }
        assertFalse(legacy.verify(tampered, anchored, Instant.parse("2027-01-01T00:00:00Z")).trusted)
        assertFalse(legacy.verify(chain, reference(emptyList()), Instant.parse("2027-01-01T00:00:00Z")).trusted)
        val revoked = RevocationList(mapOf(BigInteger(1, hexBytes("209ab7a76aa0bc0afdfceec20a190431")).toString() to io.github.h_sib.declared.core.integrity.RevocationEntry("REVOKED", "KEY_COMPROMISE")))
        assertFalse(legacy.verify(chain, reference(listOf(chain.last()), revoked), Instant.parse("2027-01-01T00:00:00Z")).trusted)
    }

    private fun hexBytes(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun aRevokedCertificateIsFoundByDecimalOrHexSerialAndDistrustsTheChain() {
        val serialHex = "b4c85ecd413704520487de6e7b302d79" // the intermediate, as printed by OpenSSL from the fixture
        val decimal = BigInteger(1, hexBytes(serialHex)).toString()
        for (key in listOf(decimal, serialHex)) {
            val list = RevocationList(mapOf(key to io.github.h_sib.declared.core.integrity.RevocationEntry("REVOKED", "KEY_COMPROMISE")))
            val r = verifier.verify(chain, reference(listOf(chain.last()), list), Fixture.NOW)
            assertEquals("key $key", listOf(1), r.revoked.map { it.first })
            assertFalse(r.trusted)
            assertTrue(r.problems().any { it.contains("revocation list (REVOKED, KEY_COMPROMISE)") })
        }
    }

    @Test
    fun garbageAnEmptyChainAndANonCertificateAreRefusedWithAReason() {
        val anchored = reference(listOf(chain.last()))
        val garbage = verifier.verify(listOf(byteArrayOf(1, 2, 3)), anchored, Fixture.NOW)
        assertNotNull(garbage.unparsable)
        assertFalse(garbage.trusted)
        val empty = verifier.verify(emptyList(), anchored, Fixture.NOW)
        assertNull(empty.unparsable)
        assertFalse(empty.trusted)
        assertTrue(empty.problems().any { it.contains("no certificate chain") })
    }

    @Test
    fun aGarbageRootIsSkippedNotFatal() {
        val r = verifier.verify(chain, reference(listOf(byteArrayOf(9, 9), chain.last())), Fixture.NOW)
        assertTrue(r.trusted)
        assertTrue(r.rootDetail, r.rootDetail.endsWith("#1"))
    }

    @Test
    fun theAttestationRecordComesFromTheCertificateNearestTheRoot() {
        // The leaf of this fixture carries the record; the verifier takes the one nearest the root that has one.
        val r = verifier.verify(chain, reference(listOf(chain.last())), Fixture.NOW)
        assertNotNull(r.attestation)
        assertNull(r.attestationError)
        // A chain with no certificate holding a record has none to read.
        val none = verifier.verify(chain.drop(1), reference(listOf(chain.last())), Fixture.NOW)
        assertNull(none.attestation)
        assertNotNull(none.attestationError)
    }
}
