package io.github.h_sib.declared.sensors

import io.github.h_sib.declared.core.integrity.ReferenceBundle
import io.github.h_sib.declared.core.sensor.ReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** The reference data that ships in the app must be exactly what its manifest says, and must parse. */
class ReferenceAssetsTest {
    private val dir = File("src/main/assets/attestation")

    private fun bytes(name: String) = File(dir, name).readBytes()

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Test
    fun theShippedFilesMatchTheirManifestAndParse() {
        val manifest = File(dir, "MANIFEST.txt").readText()
        val result = ReferenceBundle.load(bytes(ReferenceBundle.ROOTS), bytes(ReferenceBundle.STATUS), manifest)
        assertTrue(result.toString(), result is ReadResult.Ok)
        val data = (result as ReadResult.Ok).value
        assertTrue("expected Google's published roots, found ${data.roots.size}", data.roots.size >= 2)
        assertTrue("the status list should hold well over a thousand entries, found ${data.revocation.size}", data.revocation.size > 1000)
        assertEquals("2026-10-08", data.retrievedOn.toString())
    }

    @Test
    fun theManifestChecksumsAreTheRealOnes() {
        for (entry in ReferenceBundle.parseManifest(File(dir, "MANIFEST.txt").readText())) {
            assertEquals(entry.name, entry.sha256, sha256(bytes(entry.name)))
        }
    }

    @Test
    fun aChangedFileIsRefusedNotUsed() {
        val manifest = File(dir, "MANIFEST.txt").readText()
        val changed = bytes(ReferenceBundle.STATUS).also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        val result = ReferenceBundle.load(bytes(ReferenceBundle.ROOTS), changed, manifest)
        assertTrue(result.toString(), result is ReadResult.Refused && result.reason.contains("does not match the checksum"))
    }
}
