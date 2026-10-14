package io.github.h_sib.declared.sensors

import io.github.h_sib.declared.core.sensor.ReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorsTest {

    @Test
    fun sha256MatchesPublishedTestVectors() {
        // FIPS 180-4 example vectors: "abc" and the empty message.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hashing.sha256Hex("abc".toByteArray()))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", Hashing.sha256Hex(ByteArray(0)))
    }

    @Test
    fun hexKeepsLeadingZerosAndNegativeBytes() {
        // SHA-256 of a single 0x00 byte starts with 0x6e; this guards the byte-to-hex step (width 2, unsigned).
        val hex = Hashing.sha256Hex(byteArrayOf(0))
        assertEquals(64, hex.length)
        assertEquals("6e340b9cffb37a989ca544e6bb780a2c78901d3fb33738768511a30617afa01d", hex)
    }

    @Test
    fun aSecurityExceptionIsARefusalNotAFailure() {
        val r = guarded<Int> { throw SecurityException("no") }
        assertTrue(r is ReadResult.Refused)
        assertTrue((r as ReadResult.Refused).reason.startsWith("SecurityException"))
    }

    @Test
    fun anyOtherExceptionIsAFailure() {
        val r = guarded<Int> { error("boom") }
        assertTrue(r is ReadResult.Failed)
        assertTrue((r as ReadResult.Failed).reason.contains("boom"))
    }

    @Test
    fun successIsOk() {
        assertEquals(ReadResult.Ok(7), guarded { 7 })
    }

    @Test
    fun aLongMessageIsTruncatedSoItCannotFloodTheScreen() {
        val r = guarded<Int> { throw SecurityException("x".repeat(5000)) } as ReadResult.Refused
        assertTrue(r.reason.length < 220)
    }
}
