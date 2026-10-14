package io.github.h_sib.declared.core.integrity

import io.github.h_sib.declared.core.json.Json
import java.time.LocalDate
import java.util.Base64

/** One entry of Google's certificate status list for Android key attestation. */
data class RevocationEntry(val status: String, val reason: String?)

/**
 * Google's published list of attestation certificates that must no longer be trusted, keyed by certificate serial
 * number. The list is bundled with the app and shows its date: Stage 1 has no network, so it can be out of date, and a
 * stale list lowers confidence instead of being hidden.
 */
class RevocationList(private val entries: Map<String, RevocationEntry>) {
    val size: Int get() = entries.size

    /** Google's list is keyed by serial number; the decimal and the hexadecimal spellings are both checked. */
    fun lookup(serialDecimal: String, serialHex: String): RevocationEntry? {
        val hexLower = serialHex.lowercase()
        for (key in listOf(serialDecimal, hexLower, hexLower.trimStart('0'))) {
            entries[key]?.let { return it }
        }
        return null
    }

    companion object {
        /** Parses `{"entries": {"<serial>": {"status": "REVOKED", "reason": "KEY_COMPROMISE"}, ...}}`. */
        fun parse(json: String): RevocationList {
            val root = Json.parse(json) as? Map<*, *> ?: throw Json.JsonException("the status list is not an object")
            val entries = root["entries"] as? Map<*, *> ?: throw Json.JsonException("the status list has no \"entries\"")
            val out = LinkedHashMap<String, RevocationEntry>(entries.size * 2)
            for ((k, v) in entries) {
                val e = v as? Map<*, *> ?: throw Json.JsonException("entry $k is not an object")
                val status = e["status"] as? String ?: throw Json.JsonException("entry $k has no status")
                out[k as String] = RevocationEntry(status, e["reason"] as? String)
            }
            return RevocationList(out)
        }
    }
}

/** What the chain check is judged against: the trusted roots and the revocation list, with the date the list was fetched. */
class ReferenceData(
    /** DER of each trusted root certificate. */
    val roots: List<ByteArray>,
    val revocation: RevocationList,
    /** When the bundled data was fetched from Google. Shown to the user; null if unknown. */
    val retrievedOn: LocalDate?,
) {
    companion object {
        /** Parses a JSON array of PEM certificates (`["-----BEGIN CERTIFICATE-----\n...", ...]`) into DER. */
        fun parseRoots(json: String): List<ByteArray> {
            val list = Json.parse(json) as? List<*> ?: throw Json.JsonException("the roots file is not an array")
            return list.map { item ->
                val pem = item as? String ?: throw Json.JsonException("a root is not a string")
                val body = pem.lines().filter { !it.startsWith("-----") }.joinToString("")
                Base64.getMimeDecoder().decode(body)
            }
        }
    }
}
