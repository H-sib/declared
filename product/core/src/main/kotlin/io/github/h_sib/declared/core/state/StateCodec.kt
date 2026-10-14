package io.github.h_sib.declared.core.state

import io.github.h_sib.declared.core.json.Json
import io.github.h_sib.declared.core.model.Declarations
import io.github.h_sib.declared.core.model.Profile
import io.github.h_sib.declared.core.model.Told
import io.github.h_sib.declared.core.sensor.ReadResult
import java.time.Instant
import java.time.format.DateTimeParseException

/** A JSON string literal, with every character that JSON requires to be escaped. */
fun jsonQuote(s: String): String = buildString {
    append('"')
    for (c in s) {
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }
    append('"')
}

/**
 * The saved state as JSON text, and back. Reading is strict: a wrong version, a missing field or an unknown answer is a
 * `Failed` result with a reason, never a partly filled state. The Android side encrypts the text; nothing here knows how.
 */
object StateCodec {
    fun encode(s: SavedState): String = buildString {
        append("{\"version\":").append(s.version)
        append(",\"baselineAt\":").append(jsonQuote(s.baselineAt.toString()))
        append(",\"answers\":{")
        s.answers.entries.joinTo(this, ",") { (k, a) ->
            jsonQuote(k) + ":{\"answer\":" + jsonQuote(a.answer.name) + ",\"signers\":[" + a.signerSha256.joinToString(",") { jsonQuote(it) } +
                "],\"at\":" + jsonQuote(a.answeredAt.toString()) + "}"
        }
        append("},\"declarations\":{\"smsCodes\":").append(jsonQuote(s.declarations.smsCodes.name))
        append(",\"publicWifi\":").append(jsonQuote(s.declarations.publicWifi.name))
        append(",\"profile\":").append(jsonQuote(s.declarations.profile.name))
        append("},\"reviewed\":{\"takenAt\":").append(jsonQuote(s.reviewed.takenAt.toString()))
        append(",\"androidApi\":").append(s.reviewed.androidApi)
        append(",\"records\":{")
        s.reviewed.records.entries.joinTo(this, ",") { (k, r) ->
            jsonQuote(k) + ":{" + r.fields.entries.joinToString(",") { (f, v) -> jsonQuote(f) + ":" + jsonQuote(v) } + "}"
        }
        append("}}}")
    }

    fun decode(text: String): ReadResult<SavedState> = try {
        ReadResult.Ok(parse(text))
    } catch (e: Json.JsonException) {
        ReadResult.Failed("the saved state is not valid JSON: ${e.message}")
    } catch (e: IllegalArgumentException) {
        ReadResult.Failed("the saved state is not what this version expects: ${e.message}")
    }

    private fun obj(v: Any?, what: String): Map<*, *> = v as? Map<*, *> ?: throw IllegalArgumentException("$what is not an object")

    private fun str(v: Any?, what: String): String = v as? String ?: throw IllegalArgumentException("$what is not text")

    private fun instant(v: Any?, what: String): Instant = try {
        Instant.parse(str(v, what))
    } catch (e: DateTimeParseException) {
        throw IllegalArgumentException("$what is not a date")
    }

    private fun parse(text: String): SavedState {
        val root = obj(Json.parse(text), "the state")
        val version = (root["version"] as? Long) ?: throw IllegalArgumentException("no version")
        require(version in SavedState.OLDEST_READABLE_VERSION.toLong()..SavedState.CURRENT_VERSION.toLong()) { "version $version is not supported" }
        val answers = LinkedHashMap<String, HolderAnswer>()
        for ((k, v) in obj(root["answers"], "answers")) {
            val a = obj(v, "answer $k")
            val answer = Answer.entries.firstOrNull { it.name == a["answer"] } ?: throw IllegalArgumentException("unknown answer for $k")
            val signers = (a["signers"] as? List<*>)?.map { str(it, "a signer") } ?: throw IllegalArgumentException("no signers for $k")
            answers[str(k, "an answer key")] = HolderAnswer(answer, signers, instant(a["at"], "the date of answer $k"))
        }
        // Version 1 had no declarations: nothing was asked yet, so every question reads as unanswered. From version 2 they are required.
        val declarations = if (version == 1L) Declarations() else declarations(obj(root["declarations"], "declarations"))
        val reviewed = obj(root["reviewed"], "reviewed")
        val records = LinkedHashMap<String, Record>()
        for ((k, v) in obj(reviewed["records"], "records")) {
            val fields = LinkedHashMap<String, String>()
            for ((f, x) in obj(v, "record $k")) fields[str(f, "a field name")] = str(x, "field $f of $k")
            records[str(k, "a record key")] = Record(fields.toSortedMap())
        }
        val api = (reviewed["androidApi"] as? Long)?.toInt() ?: throw IllegalArgumentException("no Android version")
        return SavedState(
            version.toInt(), instant(root["baselineAt"], "baselineAt"), answers.toSortedMap(),
            Snapshot(instant(reviewed["takenAt"], "takenAt"), api, records.toSortedMap()),
            declarations,
        )
    }

    private fun declarations(o: Map<*, *>): Declarations {
        fun told(name: String): Told = Told.entries.firstOrNull { it.name == o[name] } ?: throw IllegalArgumentException("unknown answer for $name")
        val profile = Profile.entries.firstOrNull { it.name == o["profile"] } ?: throw IllegalArgumentException("unknown profile")
        return Declarations(told("smsCodes"), told("publicWifi"), profile)
    }
}

/** What the store found when asked. Corrupt and Unavailable are different problems and are told apart on screen. */
sealed interface StoreResult {
    /** Nothing saved yet: a first run. */
    data object Empty : StoreResult

    data class Loaded(val saved: SavedState) : StoreResult

    /** Something was saved but cannot be read (damaged, tampered with, or from a version this app does not know). */
    data class Corrupt(val reason: String) : StoreResult

    /** The place to keep it could not be used at all (for example the key store refused). */
    data class Unavailable(val reason: String) : StoreResult
}

interface StateStore {
    fun load(): StoreResult

    fun save(saved: SavedState): ReadResult<Unit>
}
