package io.github.h_sib.declared.core.ledger

import io.github.h_sib.declared.core.json.Json
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.state.Change
import io.github.h_sib.declared.core.state.Record
import io.github.h_sib.declared.core.state.jsonQuote
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The ledger as JSON text and back. Reading is strict like the saved state's: a missing field or an unknown word is a
 * `Failed` result with a reason. It does not check the hashes: a ledger that reads but does not verify is shown as damaged by
 * [Ledger.verify], which is the point of keeping the two apart.
 */
object LedgerCodec {
    const val FORMAT = 1

    fun encode(l: Ledger): String = buildString {
        append("{\"format\":").append(FORMAT)
        append(",\"header\":{\"created\":").append(jsonQuote(l.header.createdAt.toString()))
        append(",\"key\":").append(jsonQuote(l.header.publicKeySpki))
        append(",\"held\":").append(jsonQuote(l.header.keyHeld.name)).append("}")
        append(",\"entries\":[")
        l.entries.joinTo(this, ",") { e ->
            "{\"seq\":" + e.seq + ",\"at\":" + jsonQuote(e.at.toString()) + ",\"taken\":" + jsonQuote(e.taken.name) + ",\"app\":" + jsonQuote(e.appVersion) +
                ",\"api\":" + e.androidApi + ",\"snapshot\":" + jsonQuote(e.snapshotDigest) + ",\"changes\":" + Canonical.changes(e.changes) +
                ",\"events\":" + Canonical.events(e.events) + ",\"prev\":" + jsonQuote(e.prev) + ",\"hash\":" + jsonQuote(e.hash) +
                ",\"sig\":" + (e.signature?.let { jsonQuote(it) } ?: "null") + "}"
        }
        append("]}")
    }

    fun decode(text: String): ReadResult<Ledger> = try {
        ReadResult.Ok(parse(text))
    } catch (e: Json.JsonException) {
        ReadResult.Failed("the history is not valid JSON: ${e.message}")
    } catch (e: IllegalArgumentException) {
        ReadResult.Failed("the history is not what this version expects: ${e.message}")
    }

    private fun obj(v: Any?, what: String): Map<*, *> = v as? Map<*, *> ?: throw IllegalArgumentException("$what is not an object")

    private fun list(v: Any?, what: String): List<*> = v as? List<*> ?: throw IllegalArgumentException("$what is not a list")

    private fun str(v: Any?, what: String): String = v as? String ?: throw IllegalArgumentException("$what is not text")

    private fun long(v: Any?, what: String): Long = v as? Long ?: throw IllegalArgumentException("$what is not a number")

    private fun instant(v: Any?, what: String): Instant = try {
        Instant.parse(str(v, what))
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("$what is not a date")
    }

    private fun fields(v: Any?, what: String): Record {
        val m = LinkedHashMap<String, String>()
        for ((k, x) in obj(v, what)) m[str(k, "a field name")] = str(x, "a field of $what")
        return Record(m.toSortedMap())
    }

    private fun change(v: Any?): Change {
        val o = obj(v, "a change")
        val key = str(o["k"], "a change key")
        return when (str(o["t"], "a change type")) {
            "A" -> Change.Added(key, fields(o["a"], "added $key"))
            "R" -> Change.Removed(key, fields(o["b"], "removed $key"))
            "M" -> Change.Modified(key, fields(o["b"], "before $key"), fields(o["a"], "after $key"))
            else -> throw IllegalArgumentException("unknown change type for $key")
        }
    }

    private fun parse(text: String): Ledger {
        val root = obj(Json.parse(text), "the history")
        require(long(root["format"], "format") == FORMAT.toLong()) { "format ${root["format"]} is not supported" }
        val h = obj(root["header"], "header")
        val held = KeyHeld.entries.firstOrNull { it.name == h["held"] } ?: throw IllegalArgumentException("unknown key kind")
        val header = LedgerHeader(instant(h["created"], "created"), str(h["key"], "the key"), held)
        val entries = list(root["entries"], "entries").map { raw ->
            val e = obj(raw, "an entry")
            val taken = Taken.entries.firstOrNull { it.name == e["taken"] } ?: throw IllegalArgumentException("unknown way of taking an entry")
            LedgerEntry(
                seq = long(e["seq"], "seq"),
                at = instant(e["at"], "an entry date"),
                taken = taken,
                appVersion = str(e["app"], "app version"),
                androidApi = long(e["api"], "api").toInt(),
                snapshotDigest = str(e["snapshot"], "snapshot digest"),
                changes = list(e["changes"], "changes").map { change(it) },
                events = list(e["events"], "events").map { str(it, "an event") },
                prev = str(e["prev"], "prev"),
                hash = str(e["hash"], "hash"),
                signature = when (val s = e["sig"]) {
                    null -> null
                    else -> str(s, "signature")
                },
            )
        }
        return Ledger(header, entries)
    }
}

/** What the store found when asked, told apart like the baseline's. */
sealed interface LedgerLoad {
    data object Empty : LedgerLoad

    data class Loaded(val ledger: Ledger) : LedgerLoad

    data class Corrupt(val reason: String) : LedgerLoad

    data class Unavailable(val reason: String) : LedgerLoad
}

interface LedgerStore {
    fun load(): LedgerLoad

    fun save(ledger: Ledger): ReadResult<Unit>
}
