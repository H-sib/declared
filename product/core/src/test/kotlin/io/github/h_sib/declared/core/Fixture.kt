package io.github.h_sib.declared.core

import java.util.Base64

/**
 * A real attestation chain captured from an API 34 emulator on 2026-10-09 (`product/tools/conformance_m4.py` can capture
 * a new one). It is software-backed and rooted in "Droid Unregistered Device CA", Google's test root, so it carries no
 * personal data. Validity: leaf 1970 to 2048, intermediate 2026-10-06 to 2026-10-22, root 2026-10-06 to 2026-12-17.
 */
object Fixture {
    private fun lines(name: String): List<String> =
        checkNotNull(Fixture::class.java.getResourceAsStream("/attestation/$name")) { "missing test resource $name" }
            .bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }

    fun chain(): List<ByteArray> = lines("emulator-api34-software-chain.txt").map { Base64.getDecoder().decode(it) }

    fun challenge(): ByteArray = lines("emulator-api34-software-challenge.txt").single().chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** A moment inside every certificate's validity window. */
    val NOW: java.time.Instant = java.time.Instant.parse("2026-10-09T12:00:00Z")
}
