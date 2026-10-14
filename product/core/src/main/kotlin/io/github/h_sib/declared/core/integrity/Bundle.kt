package io.github.h_sib.declared.core.integrity

import io.github.h_sib.declared.core.sensor.ReadResult
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * The reference data shipped inside the app: Google's attestation roots and certificate status list, plus a manifest that
 * lists the SHA-256 of each file and the day it was fetched. The checksum is checked on every load, so a corrupted or
 * replaced file becomes a refusal ("this check cannot be judged") instead of quietly changing the answer.
 *
 * Manifest lines: `name<TAB>sha256<TAB>yyyy-MM-dd<TAB>source`; lines starting with `#` are comments.
 */
object ReferenceBundle {
    const val ROOTS = "google-attestation-roots.json"
    const val STATUS = "google-attestation-status.json"

    data class ManifestEntry(val name: String, val sha256: String, val retrievedOn: LocalDate, val source: String)

    fun parseManifest(text: String): List<ManifestEntry> = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
        val parts = line.split('\t')
        require(parts.size >= 4) { "manifest line needs four tab-separated fields: $line" }
        val date = try { LocalDate.parse(parts[2]) } catch (e: DateTimeParseException) { throw IllegalArgumentException("bad date in manifest: ${parts[2]}") }
        require(parts[1].length == 64 && parts[1].all { it in "0123456789abcdef" }) { "bad checksum in manifest for ${parts[0]}" }
        ManifestEntry(parts[0], parts[1], date, parts[3])
    }

    private fun sha256(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun load(roots: ByteArray, status: ByteArray, manifestText: String): ReadResult<ReferenceData> {
        val manifest = try {
            parseManifest(manifestText)
        } catch (e: IllegalArgumentException) {
            return ReadResult.Failed("the bundled manifest is malformed: ${e.message}")
        }
        val byName = manifest.associateBy { it.name }
        for ((name, bytes) in listOf(ROOTS to roots, STATUS to status)) {
            val entry = byName[name] ?: return ReadResult.Refused("$name is not listed in the bundled manifest")
            if (sha256(bytes) != entry.sha256) return ReadResult.Refused("$name does not match the checksum in the bundled manifest")
        }
        return try {
            val parsedRoots = ReferenceData.parseRoots(roots.toString(Charsets.UTF_8))
            if (parsedRoots.isEmpty()) return ReadResult.Failed("the bundled roots file holds no certificate")
            val revocation = RevocationList.parse(status.toString(Charsets.UTF_8))
            ReadResult.Ok(ReferenceData(parsedRoots, revocation, listOf(byName.getValue(ROOTS).retrievedOn, byName.getValue(STATUS).retrievedOn).min()))
        } catch (e: IllegalArgumentException) {
            ReadResult.Failed("the bundled reference data could not be parsed: ${e.message}")
        }
    }
}
