package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.state.SealedFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class SealedFileTest {
    private val iv = ByteArray(12) { (it + 1).toByte() }
    private val ciphertext = ByteArray(40) { (it * 3).toByte() }

    @Test
    fun packingAndUnpackingRoundTrips() {
        val (i, c) = SealedFile.unpack(SealedFile.pack(iv, ciphertext))
        assertArrayEquals(iv, i)
        assertArrayEquals(ciphertext, c)
    }

    @Test
    fun everyKindOfWrongFileIsRefusedWithAReason() {
        val good = SealedFile.pack(iv, ciphertext)
        val cases = mapOf(
            "empty" to ByteArray(0),
            "too short" to byteArrayOf(0xCA.toByte(), 1),
            "someone else's file" to good.copyOf().also { it[0] = 0x7F },
            "a newer format" to good.copyOf().also { it[1] = 9 },
            "no iv" to good.copyOf().also { it[2] = 0 },
            "truncated inside the iv or tag" to good.copyOf(3 + 12 + 10),
            "an iv longer than the file" to good.copyOf().also { it[2] = 200.toByte() },
        )
        for ((name, bytes) in cases) {
            try {
                SealedFile.unpack(bytes)
                fail("accepted: $name")
            } catch (e: SealedFile.FormatException) {
                assertEquals(true, e.message!!.isNotBlank())
            }
        }
    }

    @Test
    fun randomBytesNeverCrashTheReaderOrParseAsAValidFileByAccident() {
        val random = Random(99)
        var accepted = 0
        repeat(5000) {
            val bytes = ByteArray(random.nextInt(80)).also { random.nextBytes(it) }
            try {
                SealedFile.unpack(bytes)
                accepted += 1
            } catch (e: SealedFile.FormatException) {
                // expected
            }
        }
        // Only the exact magic and format bytes get through, which random data hits about once in 65,000 tries.
        assertEquals(true, accepted < 10)
    }
}
