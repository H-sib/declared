package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.json.Json
import io.github.h_sib.declared.core.integrity.ReferenceBundle
import io.github.h_sib.declared.core.integrity.ReferenceData
import io.github.h_sib.declared.core.integrity.RevocationList
import io.github.h_sib.declared.core.sensor.ReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

class JsonTest {
    @Test
    fun parsesTheShapesTheReferenceFilesUse() {
        val v = Json.parse(""" { "entries" : { "123" : { "status": "REVOKED", "reason": "KEY_COMPROMISE" } }, "n": -4, "f": 1.5, "b": [true, false, null] } """)
        val m = v as Map<*, *>
        assertEquals("REVOKED", ((m["entries"] as Map<*, *>)["123"] as Map<*, *>)["status"])
        assertEquals(-4L, m["n"])
        assertEquals(1.5, m["f"])
        assertEquals(listOf(true, false, null), m["b"])
    }

    @Test
    fun stringEscapesIncludingUnicodeAndPemNewlines() {
        assertEquals("a\nb\"c\\d/e\t\u00e9", Json.parse("\"a\\nb\\\"c\\\\d\\/e\\t\\u00e9\""))
    }

    @Test
    fun rejectsMalformedInput() {
        val bad = listOf(
            "", "{", "[1,]", "{\"a\":1,}", "{\"a\" 1}", "[1 2]", "\"abc", "tru", "{\"a\":1} x", "{\"a\":1,\"a\":2}", "\"a\u0001b\"",
            "\"\\x\"", "\"\\u12\"", "-", "[--1]", "nul", "{1:2}",
        )
        for (text in bad) {
            try {
                Json.parse(text)
                fail("accepted: ${text.take(30)}")
            } catch (e: Json.JsonException) {
                // expected
            }
        }
    }
}

class ReferenceTest {
    private val status = """
        {"entries": {
          "6681152659205225093": {"status": "REVOKED", "reason": "KEY_COMPROMISE"},
          "8350192447815228107": {"status": "SUSPENDED"},
          "6bfec5ee9a": {"status": "REVOKED", "reason": "KEY_COMPROMISE"}
        }}
    """

    @Test
    fun revocationLookupAcceptsDecimalAndHexSpellings() {
        val list = RevocationList.parse(status)
        assertEquals(3, list.size)
        assertEquals("REVOKED", list.lookup("6681152659205225093", "5cd1c07bd5e2a605")?.status)
        assertEquals("SUSPENDED", list.lookup("8350192447815228107", "x")?.status)
        assertNull(list.lookup("1", "2"))
        // hexadecimal key, with a leading zero in the certificate's spelling and capitals
        assertNotNull(list.lookup("0", "006BFEC5EE9A"))
    }

    @Test
    fun malformedStatusListsAreRejected() {
        for (bad in listOf("[]", "{}", "{\"entries\": []}", "{\"entries\": {\"1\": 5}}", "{\"entries\": {\"1\": {}}}")) {
            try {
                RevocationList.parse(bad)
                fail("accepted $bad")
            } catch (e: Json.JsonException) {
                // expected
            }
        }
    }

    @Test
    fun rootsAreDecodedFromPemInsideJson() {
        val der = Fixture.chain().last()
        val pem = "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END CERTIFICATE-----\n"
        val json = "[\"" + pem.replace("\n", "\\n") + "\"]"
        val roots = ReferenceData.parseRoots(json)
        assertEquals(1, roots.size)
        assertTrue(roots[0].contentEquals(der))
    }

    // ---- the bundle with checksums ----

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    private val tab = "\t"
    private val rootsJson = "[\"" + "-----BEGIN CERTIFICATE-----\\n" + Base64.getEncoder().encodeToString(Fixture.chain().last()) + "\\n-----END CERTIFICATE-----\"]"

    private fun manifest(roots: ByteArray, statusBytes: ByteArray, date: String = "2026-10-08") =
        "# comment\n\n${ReferenceBundle.ROOTS}${tab}${sha(roots)}${tab}$date${tab}https://example.test/root\n" +
            "${ReferenceBundle.STATUS}${tab}${sha(statusBytes)}${tab}$date${tab}https://example.test/status\n"

    @Test
    fun aBundleWithMatchingChecksumsLoads() {
        val roots = rootsJson.toByteArray()
        val st = status.toByteArray()
        val r = ReferenceBundle.load(roots, st, manifest(roots, st))
        assertTrue(r.toString(), r is ReadResult.Ok)
        val data = (r as ReadResult.Ok).value
        assertEquals(1, data.roots.size)
        assertEquals("2026-10-08", data.retrievedOn.toString())
        assertEquals(3, data.revocation.size)
    }

    @Test
    fun theOlderOfTheTwoDatesIsTheReferenceDate() {
        val roots = rootsJson.toByteArray()
        val st = status.toByteArray()
        val m = "${ReferenceBundle.ROOTS}${tab}${sha(roots)}${tab}2026-10-08${tab}x\n${ReferenceBundle.STATUS}${tab}${sha(st)}${tab}2026-01-02${tab}y\n"
        assertEquals("2026-01-02", (ReferenceBundle.load(roots, st, m) as ReadResult.Ok).value.retrievedOn.toString())
    }

    @Test
    fun aChangedFileAMissingEntryOrABrokenManifestIsRefusedNotUsed() {
        val roots = rootsJson.toByteArray()
        val st = status.toByteArray()
        val changed = st.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        assertTrue(ReferenceBundle.load(roots, changed, manifest(roots, st)).let { it is ReadResult.Refused && it.reason.contains("checksum") })
        val onlyRoots = "${ReferenceBundle.ROOTS}${tab}${sha(roots)}${tab}2026-10-08${tab}x\n"
        assertTrue(ReferenceBundle.load(roots, st, onlyRoots).let { it is ReadResult.Refused && it.reason.contains("not listed") })
        for (bad in listOf("name${tab}short${tab}2026-10-08${tab}x", "name${tab}${"a".repeat(64)}${tab}not-a-date${tab}x", "only one field", "n${tab}${"A".repeat(64)}${tab}2026-10-08${tab}x")) {
            assertTrue(bad, ReferenceBundle.load(roots, st, bad) is ReadResult.Failed)
        }
    }

    @Test
    fun brokenJsonInsideAMatchingBundleIsAFailureNotACrash() {
        val roots = "not json".toByteArray()
        val st = status.toByteArray()
        assertTrue(ReferenceBundle.load(roots, st, manifest(roots, st)) is ReadResult.Failed)
        val empty = "[]".toByteArray()
        assertTrue(ReferenceBundle.load(empty, st, manifest(empty, st)).let { it is ReadResult.Failed && it.reason.contains("no certificate") })
    }
}
