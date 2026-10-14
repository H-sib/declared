package io.github.h_sib.declared.core.ledger

import io.github.h_sib.declared.core.state.Change
import io.github.h_sib.declared.core.state.Record
import io.github.h_sib.declared.core.state.Snapshot
import io.github.h_sib.declared.core.state.jsonQuote
import java.security.MessageDigest

/**
 * The one text a ledger entry is hashed from (STAGE3_DESIGN.md section 3). It is written by hand, with a fixed order of keys
 * and of records, so that two programs that disagree about JSON libraries still produce the same bytes: the independent
 * verifier in `product/tools` rebuilds it from the same description and compares hashes.
 */
object Canonical {
    fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun sha256Hex(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0 && hex.all { it in "0123456789abcdef" }) { "not lowercase hex" }
        return ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun fields(r: Record): String = r.fields.toSortedMap().entries.joinToString(",", "{", "}") { (k, v) -> jsonQuote(k) + ":" + jsonQuote(v) }

    /** What a snapshot says, not when it was taken: the same phone state has the same text at any time. */
    fun snapshot(s: Snapshot): String =
        "{\"api\":" + s.androidApi + ",\"records\":" + s.records.toSortedMap().entries.joinToString(",", "{", "}") { (k, r) -> jsonQuote(k) + ":" + fields(r) } + "}"

    fun snapshotDigest(s: Snapshot): String = sha256Hex(snapshot(s))

    fun change(c: Change): String = when (c) {
        is Change.Added -> "{\"k\":" + jsonQuote(c.key) + ",\"t\":\"A\",\"a\":" + fields(c.record) + "}"
        is Change.Removed -> "{\"k\":" + jsonQuote(c.key) + ",\"t\":\"R\",\"b\":" + fields(c.record) + "}"
        is Change.Modified -> "{\"k\":" + jsonQuote(c.key) + ",\"t\":\"M\",\"b\":" + fields(c.before) + ",\"a\":" + fields(c.after) + "}"
    }

    fun changes(list: List<Change>): String = list.sortedBy { it.key }.joinToString(",", "[", "]") { change(it) }

    fun events(list: List<String>): String = list.sorted().joinToString(",", "[", "]") { jsonQuote(it) }
}
