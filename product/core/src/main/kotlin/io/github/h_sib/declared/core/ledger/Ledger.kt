package io.github.h_sib.declared.core.ledger

import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.state.Change
import io.github.h_sib.declared.core.state.Diff
import io.github.h_sib.declared.core.state.Record
import io.github.h_sib.declared.core.state.Snapshot
import io.github.h_sib.declared.core.state.jsonQuote
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The history (STAGE3_DESIGN.md sections 3 and 4): one entry every time the app takes a snapshot, each linked to the one
 * before by a hash and signed with a key held in the Keystore. It proves that records were not altered after the fact by
 * something that cannot use the key, that none was removed from the middle, and their order. It does NOT prove the phone was
 * clean when an entry was written, and it cannot see the newest entries being removed by something with access to the app's
 * files (nothing outside the phone holds the head). The tests state that limit instead of hiding it.
 */

/** How an entry came about. A stretch with no entry is a gap on the timeline, so this is recorded rather than assumed. */
enum class Taken { OPENED, ON_SCREEN }

enum class KeyHeld { HARDWARE, SOFTWARE, UNKNOWN, NONE }

/** Signs the hash of an entry. The Android side keeps the key in the Keystore; tests use a software key. */
interface EntrySigner {
    /** The public key (X.509 SubjectPublicKeyInfo, DER), or null when there is no key at all. */
    val publicKeySpki: ByteArray?

    val keyHeld: KeyHeld

    fun sign(message: ByteArray): ReadResult<ByteArray>
}

/** ECDSA over P-256 with SHA-256, the scheme the Keystore key uses. Verifying needs nothing but the JDK. */
object EcdsaP256 {
    fun verify(spki: ByteArray, message: ByteArray, signatureDer: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki))
        Signature.getInstance("SHA256withECDSA").run {
            initVerify(key)
            update(message)
            verify(signatureDer)
        }
    } catch (e: Exception) {
        false
    }
}

data class LedgerHeader(val createdAt: Instant, val publicKeySpki: String, val keyHeld: KeyHeld) {
    fun canonical(): String =
        "{\"v\":1,\"created\":" + jsonQuote(createdAt.toString()) + ",\"key\":" + jsonQuote(publicKeySpki) + ",\"held\":" + jsonQuote(keyHeld.name) + "}"

    /** What entry 1 must name as its predecessor. Hashing the header makes the key and the start time part of the chain. */
    val genesis: String get() = Canonical.sha256Hex(canonical())
}

data class LedgerEntry(
    val seq: Long,
    val at: Instant,
    val taken: Taken,
    val appVersion: String,
    val androidApi: Int,
    val snapshotDigest: String,
    val changes: List<Change>,
    val events: List<String>,
    val prev: String,
    val hash: String,
    /** Base64 DER ECDSA signature over the raw bytes of [hash]; null when the entry could not be signed. */
    val signature: String?,
) {
    fun body(): String = body(seq, at, taken, appVersion, androidApi, snapshotDigest, changes, events, prev)

    companion object {
        fun body(seq: Long, at: Instant, taken: Taken, appVersion: String, api: Int, snapshotDigest: String, changes: List<Change>, events: List<String>, prev: String): String =
            "{\"v\":1,\"seq\":" + seq + ",\"at\":" + jsonQuote(at.toString()) + ",\"taken\":" + jsonQuote(taken.name) + ",\"app\":" + jsonQuote(appVersion) +
                ",\"api\":" + api + ",\"snapshot\":" + jsonQuote(snapshotDigest) + ",\"changes\":" + Canonical.changes(changes) +
                ",\"events\":" + Canonical.events(events) + ",\"prev\":" + jsonQuote(prev) + "}"
    }
}

object LedgerPolicy {
    /** An entry that records no change and no event is only written if the last one is at least this old: it shows the app looked, which narrows a gap. */
    val HEARTBEAT: Duration = Duration.ofHours(6)

    /** A stretch longer than this between two entries is shown on the timeline as a gap. */
    val GAP_AFTER: Duration = Duration.ofHours(48)
}

sealed interface AppendResult {
    data class Appended(val ledger: Ledger, val entry: LedgerEntry) : AppendResult

    data class Skipped(val reason: String) : AppendResult
}

data class Ledger(val header: LedgerHeader, val entries: List<LedgerEntry>) {
    val head: LedgerEntry? get() = entries.lastOrNull()

    /** Set by [append], which already knows the records the new entry leads to, so a long history is not replayed again at every entry. Not part of equality. */
    private var seeded: Map<String, Record>? = null

    /** The records as of the newest entry, by replaying every diff. Null when a diff cannot be applied: [verify] says where. */
    val headRecords: Map<String, Record>? by lazy {
        seeded ?: run {
            var records: Map<String, Record> = emptyMap()
            for (e in entries) records = try { Diff.apply(records, e.changes) } catch (ex: IllegalArgumentException) { return@run null }
            records
        }
    }

    /** The newest snapshot, or an empty one before the first entry. */
    fun headSnapshot(): Snapshot? = headRecords?.let { Snapshot(head?.at ?: header.createdAt, head?.androidApi ?: 0, it) }

    fun append(snapshot: Snapshot, at: Instant, taken: Taken, appVersion: String, events: List<String> = emptyList(), signer: EntrySigner?): AppendResult {
        val previous = headRecords ?: return AppendResult.Skipped("the existing history cannot be replayed, so nothing is added to it")
        val diff = Diff.of(previous, snapshot.records)
        val last = head
        val extra = ArrayList(events)
        val keyNow = signer?.publicKeySpki?.let { Base64.getEncoder().encodeToString(it) }.orEmpty()
        if (keyNow != header.publicKeySpki) extra += "signing-key.changed"
        if (diff.isEmpty() && extra.isEmpty() && last != null && Duration.between(last.at, at).abs() < LedgerPolicy.HEARTBEAT) {
            return AppendResult.Skipped("nothing changed and the last entry is recent")
        }
        val seq = (last?.seq ?: 0L) + 1
        val prev = last?.hash ?: header.genesis
        val digest = Canonical.snapshotDigest(snapshot)
        val body = LedgerEntry.body(seq, at, taken, appVersion, snapshot.androidApi, digest, diff, extra, prev)
        val hash = Canonical.sha256Hex(body)
        // Only sign under the key the history started with; another key's signatures would not verify and would only look like tampering.
        val signature = if (keyNow == header.publicKeySpki && signer != null && keyNow.isNotEmpty()) {
            (signer.sign(Canonical.hexToBytes(hash)) as? ReadResult.Ok)?.value?.let { Base64.getEncoder().encodeToString(it) }
        } else {
            null
        }
        val entry = LedgerEntry(seq, at, taken, appVersion, snapshot.androidApi, digest, diff, extra.sorted(), prev, hash, signature)
        return AppendResult.Appended(Ledger(header, entries + entry).also { it.seeded = snapshot.records }, entry)
    }

    /**
     * Checks the history. Pass the key this phone holds now ([HeldKey.Spki]) to tie the signatures to it: without that, a history
     * rewritten from scratch under a key of someone else's making would pass, because the key it is checked against is the one it names.
     */
    fun verify(held: HeldKey = HeldKey.NotAsked): Verification = LedgerVerifier.verify(this, held)

    companion object {
        /** A new, empty history that will be signed by [signer]'s key (or not at all, when it has none). */
        fun begin(createdAt: Instant, signer: EntrySigner?): Ledger {
            val key = signer?.publicKeySpki?.let { Base64.getEncoder().encodeToString(it) }.orEmpty()
            return Ledger(LedgerHeader(createdAt, key, if (key.isEmpty()) KeyHeld.NONE else signer!!.keyHeld), emptyList())
        }
    }
}

enum class Severity { BROKEN, WEAK }

/** What the phone's own signing key is, when a check should be tied to it. */
sealed interface HeldKey {
    /** The check is of the file alone: signatures are tested against the key the file names. */
    data object NotAsked : HeldKey

    /** The key the Keystore holds now (X.509 SubjectPublicKeyInfo), or null when it could not be read. */
    class Spki(val bytes: ByteArray?) : HeldKey
}

data class Problem(val seq: Long?, val severity: Severity, val text: String)

data class Verification(
    val entries: Int,
    val signedEntries: Int,
    val problems: List<Problem>,
    /** What was actually checked, in the words the screen shows. */
    val checked: List<String>,
    /** What this check cannot see. Always shown with the result. */
    val notChecked: List<String>,
    /** False when the check was asked to tie the history to this phone's key and could not: the signatures may be someone else's. */
    val keyTied: Boolean = true,
) {
    /** False when something shows the history was changed after the fact. A weak finding (an unsigned entry, a clock set back) does not break it. */
    val intact: Boolean get() = problems.none { it.severity == Severity.BROKEN }

    /**
     * True only when the chain holds AND every entry carries a valid signature under the key the history began with. An unsigned
     * entry proves nothing: anything able to edit the file can recompute an unkeyed hash, only the key makes an entry unforgeable.
     */
    val authentic: Boolean get() = intact && signedEntries == entries && keyTied

    val weak: Boolean get() = problems.any { it.severity == Severity.WEAK }
}

object LedgerVerifier {
    const val MAX_PROBLEMS = 50

    val CHECKED = listOf(
        "Every entry names the one before it, starting from the key and start time the history began with.",
        "Every entry's hash is worked out again from what it says.",
        "Replaying the changes from the start gives, at every entry, the snapshot whose fingerprint that entry recorded.",
        "Every signature is checked against the key the history began with, and that key against the one this phone holds.",
    )

    val NOT_CHECKED = listOf(
        "That the phone was in good order when an entry was written: an entry records what this app could read then, nothing more.",
        "Whether the newest entries were removed: nothing outside this phone holds the latest hash. Keep a copy of it to be able to tell.",
    )

    fun verify(ledger: Ledger, held: HeldKey = HeldKey.NotAsked): Verification {
        val problems = ArrayList<Problem>()
        fun problem(seq: Long?, severity: Severity, text: String) {
            if (problems.size < MAX_PROBLEMS) problems += Problem(seq, severity, text)
        }
        val header = ledger.header
        val spki = if (header.publicKeySpki.isEmpty()) null else try { Base64.getDecoder().decode(header.publicKeySpki) } catch (e: IllegalArgumentException) { null }
        if (header.publicKeySpki.isNotEmpty() && spki == null) problem(null, Severity.BROKEN, "The key this history began with is not readable.")
        if (header.publicKeySpki.isEmpty()) problem(null, Severity.WEAK, "This history has no signing key, so nothing in it is signed.")
        var keyTied = true
        if (held is HeldKey.Spki && header.publicKeySpki.isNotEmpty() && spki != null) {
            val now = held.bytes
            if (now == null) {
                keyTied = false
                problem(null, Severity.WEAK, "This phone's signing key could not be read just now, so the signatures could not be tied to it.")
            } else if (Base64.getEncoder().encodeToString(now) != header.publicKeySpki) {
                keyTied = false
                problem(null, Severity.BROKEN, "This history was signed with a key that is not the one this phone holds. Whoever wrote it used another key, so its signatures prove nothing about this app.")
            }
        }

        var records: Map<String, Record> = emptyMap()
        var replayOk = true
        var signed = 0
        var prevHash = header.genesis
        var prevAt: Instant? = null
        ledger.entries.forEachIndexed { i, e ->
            if (e.seq != (i + 1).toLong()) problem(e.seq, Severity.BROKEN, "Entry ${i + 1} is numbered ${e.seq}: an entry was removed, added or moved.")
            if (e.prev != prevHash) problem(e.seq, Severity.BROKEN, "Entry ${e.seq} does not follow the entry before it: the chain is broken here.")
            val recomputed = Canonical.sha256Hex(e.body())
            if (recomputed != e.hash) problem(e.seq, Severity.BROKEN, "Entry ${e.seq} says something different from the hash recorded for it: it was changed.")
            if (replayOk) {
                records = try {
                    Diff.apply(records, e.changes)
                } catch (ex: IllegalArgumentException) {
                    replayOk = false
                    problem(e.seq, Severity.BROKEN, "The changes in entry ${e.seq} cannot be applied to the history before it.")
                    records
                }
                if (replayOk) {
                    val digest = Canonical.snapshotDigest(Snapshot(e.at, e.androidApi, records))
                    if (digest != e.snapshotDigest) problem(e.seq, Severity.BROKEN, "Replaying the history to entry ${e.seq} does not give the snapshot it recorded.")
                }
            }
            when {
                e.signature == null -> if (spki != null) problem(e.seq, Severity.WEAK, "Entry ${e.seq} is not signed, so nothing proves it was written by this app and not changed since.")
                spki == null -> Unit
                else -> {
                    val sig = try { Base64.getDecoder().decode(e.signature) } catch (ex: IllegalArgumentException) { null }
                    val hashBytes = try { Canonical.hexToBytes(e.hash) } catch (ex: IllegalArgumentException) { null }
                    // A signature text must be the canonical encoding of its bytes: base64 has unused bits that could be changed without changing the bytes.
                    val canonical = sig != null && Base64.getEncoder().encodeToString(sig) == e.signature
                    if (sig != null && canonical && hashBytes != null && EcdsaP256.verify(spki, hashBytes, sig)) signed += 1
                    else problem(e.seq, Severity.BROKEN, "The signature of entry ${e.seq} is not valid for the key this history began with.")
                }
            }
            val before = prevAt
            if (before != null && e.at.isBefore(before)) problem(e.seq, Severity.WEAK, "Entry ${e.seq} is dated before the entry preceding it: the phone's clock was set back.")
            prevAt = e.at
            prevHash = e.hash
        }
        return Verification(ledger.entries.size, signed, problems, LedgerVerifier.CHECKED, LedgerVerifier.NOT_CHECKED, keyTied)
    }
}
