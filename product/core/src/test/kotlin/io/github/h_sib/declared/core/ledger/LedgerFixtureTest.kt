package io.github.h_sib.declared.core.ledger

import io.github.h_sib.declared.core.healthyDevice
import io.github.h_sib.declared.core.network
import io.github.h_sib.declared.core.paths.phone
import io.github.h_sib.declared.core.paths.sideloaded
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.state.Record
import io.github.h_sib.declared.core.state.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * A real history, written by this code and kept as a file (`src/test/resources/ledger/sample-ledger.json`), so that two other
 * programs can be tested against the same bytes: the independent verifier in `product/tools` (Python, hashlib and OpenSSL) and
 * this one. The file is regenerated only on purpose, with `DECLARED_WRITE_LEDGER_SAMPLE=1`, because its signatures are random.
 */
class LedgerFixtureTest {
    private val t0 = Instant.parse("2026-10-09T10:00:00Z")

    private fun sample(): Ledger {
        val signer = SoftwareSigner(KeyHeld.SOFTWARE)
        val keys = FakeKeys()
        val keeper = HistoryKeeper(signer, keys, "0.3.0")
        var ledger: Ledger? = null
        fun turn(report: Report, seconds: Long, taken: Taken = Taken.OPENED) {
            ledger = keeper.step(ledger, report, t0.plusSeconds(seconds), taken).ledger
        }
        val quiet = phone()
        turn(quiet, 0)
        turn(phone(network = network(proxy = "10.0.0.2:3128")), 600, Taken.ON_SCREEN)
        turn(quiet, 1200, Taken.ON_SCREEN)
        turn(phone(packages = listOf(sideloaded("evil.app")), a11y = listOf("evil.app")), 3600)
        keys.bio = KeyState.INVALIDATED
        turn(phone(packages = listOf(sideloaded("evil.app")), a11y = listOf("evil.app")), 3700)
        turn(phone(device = healthyDevice(lock = ReadResult.Ok(false))), 6 * 24 * 3600L)
        // Odd text, to pin the way strings are escaped and ordered in the canonical form. U+FF5E sorts after the emoji when strings
        // are compared the way Java compares them (by UTF-16 unit) and before it when compared by code point.
        val odd = "quote\" back\\ tab\t newline\n bell\u0007 eé 中 😀"
        val l = ledger!!
        val at = t0.plusSeconds(7 * 24 * 3600L)
        val records = l.headRecords!! + mapOf(
            "app:odd" to Record.of("label" to odd, "～" to "x", "😀" to "y"),
            "app:～" to Record.of("k" to "1"),
            "app:😀" to Record.of("k" to "2"),
        )
        return (l.append(Snapshot(at, 34, records), at, Taken.OPENED, "0.3.0", listOf(odd, "～", "😀"), signer) as AppendResult.Appended).ledger
    }

    private val resource = File("src/test/resources/ledger/sample-ledger.json")

    @Test
    fun theCommittedSampleVerifiesAndIsAuthentic() {
        if (System.getenv("DECLARED_WRITE_LEDGER_SAMPLE") == "1") {
            resource.parentFile.mkdirs()
            resource.writeText(LedgerCodec.encode(sample()))
        }
        val text = resource.readText()
        val ledger = (LedgerCodec.decode(text) as ReadResult.Ok).value
        val v = ledger.verify()
        assertTrue(v.problems.toString(), v.intact && v.authentic)
        assertEquals(7, v.entries)
        assertEquals("what is stored is exactly what is encoded", text, LedgerCodec.encode(ledger))
        assertTrue("the tripwire event is in the sample", ledger.entries.any { LedgerEvents.FINGERPRINT_ADDED in it.events })
        assertTrue("the gap is in the sample", Timeline.of(ledger).any { it is TimelineRow.Gap })
    }
}
