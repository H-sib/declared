package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.integrity.AttestationParser
import io.github.h_sib.declared.core.integrity.Der
import io.github.h_sib.declared.core.integrity.attestedPatchDate
import io.github.h_sib.declared.core.integrity.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.time.LocalDate

/** Ported from the lab (lab/probe AttestationParserTest, 8 tests) and extended for the malformed-input paths. */
class DerAttestationTest {

    // ---- a tiny DER builder, independent of the parser under test ----

    private fun len(n: Int): ByteArray = when {
        n < 128 -> byteArrayOf(n.toByte())
        n < 256 -> byteArrayOf(0x81.toByte(), n.toByte())
        else -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun tlv(tag: Int, content: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + len(content.size) + content

    private fun intTlv(v: Long): ByteArray = tlv(0x02, BigInteger.valueOf(v).toByteArray())

    private fun enumTlv(v: Int): ByteArray = tlv(0x0A, byteArrayOf(v.toByte()))

    private fun base128(n: Int): ByteArray {
        val parts = mutableListOf<Int>()
        var v = n
        do {
            parts.add(v and 0x7F)
            v = v shr 7
        } while (v > 0)
        val ordered = parts.reversed()
        return ordered.mapIndexed { i, p -> (if (i < ordered.size - 1) p or 0x80 else p).toByte() }.toByteArray()
    }

    /** [tagNo] EXPLICIT, context class, constructed, using the high-tag-number form for tags of 31 or more. */
    private fun explicit(tagNo: Int, content: ByteArray): ByteArray {
        val tagBytes = if (tagNo < 31) byteArrayOf((0xA0 or tagNo).toByte()) else byteArrayOf(0xBF.toByte()) + base128(tagNo)
        return tagBytes + len(content.size) + content
    }

    private fun keyDescription(
        challenge: ByteArray,
        withRootOfTrust: Boolean = true,
        securityLevel: Int = 1,
        bootState: Int = 0,
        locked: Boolean = true,
    ): ByteArray {
        val rootOfTrust = tlv(
            0x30,
            tlv(0x04, ByteArray(32) { 1 }) + tlv(0x01, byteArrayOf(if (locked) 0xFF.toByte() else 0x00)) + enumTlv(bootState) + tlv(0x04, ByteArray(32) { 2 }),
        )
        val hardware = tlv(
            0x30,
            (if (withRootOfTrust) explicit(704, rootOfTrust) else ByteArray(0)) +
                explicit(705, intTlv(140000)) +
                explicit(706, intTlv(202609)) +
                explicit(718, intTlv(20260905)) +
                explicit(719, intTlv(20260905)),
        )
        val software = tlv(0x30, ByteArray(0))
        val description = tlv(
            0x30,
            intTlv(300) + enumTlv(securityLevel) + intTlv(300) + enumTlv(securityLevel) + tlv(0x04, challenge) + tlv(0x04, ByteArray(0)) + software + hardware,
        )
        // X509Certificate.getExtensionValue wraps the extension in an OCTET STRING.
        return tlv(0x04, description)
    }

    @Test
    fun parsesPatchLevelsAndVersions() {
        val a = AttestationParser.parse(keyDescription(ByteArray(16) { 7 }))
        assertEquals(300L, a.attestationVersion)
        assertEquals(1L, a.attestationSecurityLevel)
        assertEquals(140000L, a.osVersion)
        assertEquals(202609L, a.osPatchLevel)
        assertEquals(20260905L, a.vendorPatchLevel)
        assertEquals(20260905L, a.bootPatchLevel)
        assertTrue(a.hardwareBacked)
    }

    @Test
    fun parsesRootOfTrustWithHighTagNumber() {
        val a = AttestationParser.parse(keyDescription(ByteArray(16)))
        assertEquals(0L, a.verifiedBootState)
        assertEquals(true, a.deviceLocked)
        assertEquals("01".repeat(32), a.verifiedBootKeyHex)
        assertEquals("02".repeat(32), a.verifiedBootHashHex)
        assertEquals("verified", a.bootStateName)
    }

    @Test
    fun everyBootStateAndSecurityLevelHasAName() {
        for ((state, name) in mapOf(0 to "verified", 1 to "verified with a key the owner set (custom software)", 2 to "not verified", 3 to "failed verification")) {
            assertEquals(name, AttestationParser.parse(keyDescription(ByteArray(4), bootState = state)).bootStateName)
        }
        assertEquals("software", AttestationParser.parse(keyDescription(ByteArray(4), securityLevel = 0)).securityLevelName)
        assertFalse(AttestationParser.parse(keyDescription(ByteArray(4), securityLevel = 0)).hardwareBacked)
        assertEquals("StrongBox hardware", AttestationParser.parse(keyDescription(ByteArray(4), securityLevel = 2)).securityLevelName)
        assertEquals(false, AttestationParser.parse(keyDescription(ByteArray(4), locked = false)).deviceLocked)
    }

    @Test
    fun challengeRoundTrips() {
        val challenge = ByteArray(16) { (it * 3).toByte() }
        assertEquals(hex(challenge), AttestationParser.parse(keyDescription(challenge)).challengeHex)
    }

    @Test
    fun missingRootOfTrustYieldsNulls() {
        val a = AttestationParser.parse(keyDescription(ByteArray(16), withRootOfTrust = false))
        assertNull(a.verifiedBootState)
        assertNull(a.deviceLocked)
        assertNull(a.bootStateName)
        assertEquals(202609L, a.osPatchLevel)
    }

    @Test
    fun longFormLengthsAreHandled() {
        val big = ByteArray(300) { 9 }
        assertEquals(hex(big), AttestationParser.parse(keyDescription(big)).challengeHex)
    }

    @Test
    fun rejectsNonOctetStringWrapperAndTruncatedInput() {
        for (bad in listOf(tlv(0x30, ByteArray(0)), keyDescription(ByteArray(16)).let { it.copyOf(it.size - 5) }, ByteArray(0), byteArrayOf(0x04))) {
            try {
                AttestationParser.parse(bad)
                fail("accepted malformed input of ${bad.size} bytes")
            } catch (e: IllegalArgumentException) {
                // expected
            } catch (e: NoSuchElementException) {
                // an empty input has no element: also a refusal
            }
        }
    }

    @Test
    fun derRejectsLengthsThatRunPastTheData() {
        for (bad in listOf(byteArrayOf(0x04, 0x05, 1, 2), byteArrayOf(0x04, 0x82.toByte(), 0x01), byteArrayOf(0x04, 0x85.toByte(), 1, 2, 3, 4, 5), byteArrayOf(0x1F, 0x81.toByte()))) {
            try {
                Der.parseAll(bad)
                fail("accepted ${hex(bad)}")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }

    @Test
    fun derIntegerSignHandling() {
        assertEquals(-1L, Der.intValue(byteArrayOf(0xFF.toByte())))
        assertEquals(255L, Der.intValue(byteArrayOf(0x00, 0xFF.toByte())))
        assertFalse(Der.intValue(byteArrayOf(0x7F)) < 0)
    }

    @Test
    fun patchLevelsAreDatesInBothSpellings() {
        assertEquals(LocalDate.of(2026, 9, 1), attestedPatchDate(202609))
        assertEquals(LocalDate.of(2026, 9, 5), attestedPatchDate(20260905))
        assertNull(attestedPatchDate(null))
        assertNull(attestedPatchDate(202613))
        assertNull(attestedPatchDate(20261340))
        assertNull(attestedPatchDate(12))
    }
}
