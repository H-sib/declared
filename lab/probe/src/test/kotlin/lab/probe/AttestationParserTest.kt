package lab.probe

import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttestationParserTest {

    // ---- tiny DER builder, independent of the parser under test ----

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

    private fun keyDescription(challenge: ByteArray, withRootOfTrust: Boolean = true): ByteArray {
        val rootOfTrust = tlv(
            0x30,
            tlv(0x04, ByteArray(32) { 1 }) + tlv(0x01, byteArrayOf(0xFF.toByte())) + enumTlv(0) + tlv(0x04, ByteArray(32) { 2 }),
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
            intTlv(300) + enumTlv(1) + intTlv(300) + enumTlv(1) + tlv(0x04, challenge) + tlv(0x04, ByteArray(0)) + software + hardware,
        )
        // X509Certificate.getExtensionValue wraps the extension in an OCTET STRING.
        return tlv(0x04, description)
    }

    // ---- tests ----

    @Test
    fun parsesPatchLevelsAndVersions() {
        val a = AttestationParser.parse(keyDescription(ByteArray(16) { 7 }))
        assertEquals(300L, a.attestationVersion)
        assertEquals(1L, a.attestationSecurityLevel)
        assertEquals(140000L, a.osVersion)
        assertEquals(202609L, a.osPatchLevel)
        assertEquals(20260905L, a.vendorPatchLevel)
        assertEquals(20260905L, a.bootPatchLevel)
    }

    @Test
    fun parsesRootOfTrustWithHighTagNumber() {
        val a = AttestationParser.parse(keyDescription(ByteArray(16)))
        assertEquals(0L, a.verifiedBootState)
        assertEquals(true, a.deviceLocked)
        assertEquals("01".repeat(32), a.verifiedBootKeyHex)
        assertEquals("02".repeat(32), a.verifiedBootHashHex)
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
        assertEquals(202609L, a.osPatchLevel)
    }

    @Test
    fun longFormLengthsAreHandled() {
        val big = ByteArray(300) { 9 }
        val a = AttestationParser.parse(keyDescription(big))
        assertEquals(hex(big), a.challengeHex)
    }

    @Test
    fun rejectsNonOctetStringWrapper() {
        val bad = tlv(0x30, ByteArray(0))
        var failed = false
        try {
            AttestationParser.parse(bad)
        } catch (e: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun rejectsTruncatedInput() {
        val whole = keyDescription(ByteArray(16))
        var failed = false
        try {
            AttestationParser.parse(whole.copyOf(whole.size - 5))
        } catch (e: Exception) {
            failed = true
        }
        assertTrue(failed)
    }

    @Test
    fun derIntegerSignHandling() {
        assertEquals(-1L, Der.intValue(byteArrayOf(0xFF.toByte())))
        assertEquals(255L, Der.intValue(byteArrayOf(0x00, 0xFF.toByte())))
        assertFalse(Der.intValue(byteArrayOf(0x7F)) < 0)
    }
}
