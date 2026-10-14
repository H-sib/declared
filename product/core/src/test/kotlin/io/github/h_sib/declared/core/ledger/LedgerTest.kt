package io.github.h_sib.declared.core.ledger

import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.state.Change
import io.github.h_sib.declared.core.state.Record
import io.github.h_sib.declared.core.state.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.time.Duration
import java.time.Instant
import java.util.Random

/** A software key standing in for the Keystore's: same scheme, same encodings. */
class SoftwareSigner(override val keyHeld: KeyHeld = KeyHeld.SOFTWARE) : EntrySigner {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    var failing = false

    override val publicKeySpki: ByteArray get() = pair.public.encoded

    override fun sign(message: ByteArray): ReadResult<ByteArray> {
        if (failing) return ReadResult.Failed("the key store would not sign")
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(pair.private)
        s.update(message)
        return ReadResult.Ok(s.sign())
    }
}

class LedgerTest {
    private val t0 = Instant.parse("2026-10-09T10:00:00Z")

    private fun record(vararg f: Pair<String, String>) = Record.of(*f)

    private fun snap(at: Instant, vararg records: Pair<String, Record>) = Snapshot(at, 34, records.toMap())

    private fun randomSnapshot(random: Random, at: Instant, universe: Int = 40): Snapshot {
        val records = LinkedHashMap<String, Record>()
        repeat(universe) { i ->
            if (random.nextInt(4) != 0) records["app:p$i"] = record("label" to "App $i", "tier" to listOf("STORE", "SIDELOAD", "SYSTEM")[random.nextInt(3)], "n" to random.nextInt(3).toString())
        }
        return Snapshot(at, 34, records.toSortedMap())
    }

    private fun appended(l: Ledger, s: Snapshot, at: Instant, signer: EntrySigner?, taken: Taken = Taken.OPENED, events: List<String> = emptyList()): Ledger =
        (l.append(s, at, taken, "0.3.0", events, signer) as AppendResult.Appended).ledger

    private fun build(entries: Int, signer: EntrySigner? = SoftwareSigner(), seed: Long = 1): Pair<Ledger, EntrySigner?> {
        val random = Random(seed)
        var l = Ledger.begin(t0, signer)
        repeat(entries) { i -> l = appended(l, randomSnapshot(random, t0.plusSeconds(i * 3600L)), t0.plusSeconds(i * 3600L), signer) }
        return l to signer
    }

    // ------------------------------------------------------------ canonical text

    @Test
    fun theCanonicalTextDoesNotDependOnTheOrderThingsWereInsertedIn() {
        val a = snap(t0, "app:b" to record("y" to "2", "x" to "1"), "app:a" to record("k" to "v"))
        val b = Snapshot(t0.plusSeconds(99), 34, linkedMapOf("app:a" to Record(mapOf("k" to "v")), "app:b" to Record(linkedMapOf("x" to "1", "y" to "2"))))
        assertEquals(Canonical.snapshot(a), Canonical.snapshot(b))
        assertEquals("when it was taken is not part of what it says", Canonical.snapshotDigest(a), Canonical.snapshotDigest(b))
        assertEquals("{\"api\":34,\"records\":{\"app:a\":{\"k\":\"v\"},\"app:b\":{\"x\":\"1\",\"y\":\"2\"}}}", Canonical.snapshot(a))
    }

    @Test
    fun theKnownAnswerOfTheHashIsPinnedSoAnIndependentVerifierCanAgree() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Canonical.sha256Hex("abc"))
        val header = LedgerHeader(t0, "", KeyHeld.NONE)
        assertEquals("{\"v\":1,\"created\":\"2026-10-09T10:00:00Z\",\"key\":\"\",\"held\":\"NONE\"}", header.canonical())
    }

    // ------------------------------------------------------------ building and verifying

    @Test
    fun aLongHistoryVerifiesAndReplaysToEverySnapshotItRecorded() {
        val random = Random(7)
        val signer = SoftwareSigner()
        var l = Ledger.begin(t0, signer)
        val snapshots = ArrayList<Snapshot>()
        repeat(200) { i ->
            val s = randomSnapshot(random, t0.plusSeconds(i * 7200L))
            snapshots += s
            l = appended(l, s, s.takenAt, signer)
            if (i % 40 == 0) assertTrue(l.verify().intact)
        }
        val v = l.verify()
        assertTrue(v.problems.toString(), v.intact && !v.weak && v.authentic)
        assertEquals(200, v.entries)
        assertEquals(200, v.signedEntries)
        assertEquals("the head is the last snapshot exactly", snapshots.last().records, l.headSnapshot()!!.records)
    }

    @Test
    fun anEntryThatChangesNothingIsOnlyWrittenOnceTheLastOneIsOld() {
        val signer = SoftwareSigner()
        val s = snap(t0, "app:a" to record("k" to "v"))
        var l = appended(Ledger.begin(t0, signer), s, t0, signer)
        assertTrue(l.append(s, t0.plusSeconds(3600), Taken.OPENED, "1", emptyList(), signer) is AppendResult.Skipped)
        assertTrue("an event is worth an entry on its own", l.append(s, t0.plusSeconds(3600), Taken.OPENED, "1", listOf("x"), signer) is AppendResult.Appended)
        assertTrue("a change is too", l.append(snap(t0, "app:a" to record("k" to "w")), t0.plusSeconds(60), Taken.ON_SCREEN, "1", emptyList(), signer) is AppendResult.Appended)
        l = appended(l, s, t0.plus(LedgerPolicy.HEARTBEAT).plusSeconds(1), signer)
        assertEquals("an old last entry makes a heartbeat worth writing: it narrows the gap", 2L, l.head!!.seq)
        assertTrue(l.head!!.changes.isEmpty())
    }

    @Test
    fun theFirstEntryDescribesTheStartAndEveryLaterOneHoldsOnlyTheDifference() {
        val signer = SoftwareSigner()
        var l = appended(Ledger.begin(t0, signer), snap(t0, "app:a" to record("k" to "1"), "app:b" to record("k" to "2")), t0, signer)
        assertEquals(2, l.entries[0].changes.size)
        l = appended(l, snap(t0, "app:a" to record("k" to "1"), "app:b" to record("k" to "3"), "app:c" to record("k" to "4")), t0.plusSeconds(10), signer)
        assertEquals(listOf("app:b", "app:c"), l.entries[1].changes.map { it.key })
        assertTrue(l.entries[1].changes[0] is Change.Modified && l.entries[1].changes[1] is Change.Added)
    }

    // ------------------------------------------------------------ tampering

    private fun withEntry(l: Ledger, index: Int, change: (LedgerEntry) -> LedgerEntry) = Ledger(l.header, l.entries.mapIndexed { i, e -> if (i == index) change(e) else e })

    @Test
    fun everyKindOfAlterationIsDetected() {
        val (l, _) = build(30)
        assertTrue(l.verify().intact)
        val k = 12
        val tampered = mapOf(
            "date" to withEntry(l, k) { it.copy(at = it.at.plusSeconds(1)) },
            "how it was taken" to withEntry(l, k) { it.copy(taken = if (it.taken == Taken.OPENED) Taken.ON_SCREEN else Taken.OPENED) },
            "app version" to withEntry(l, k) { it.copy(appVersion = "9.9") },
            "android version" to withEntry(l, k) { it.copy(androidApi = 99) },
            "snapshot fingerprint" to withEntry(l, k) { it.copy(snapshotDigest = "0".repeat(64)) },
            "a change" to withEntry(l, k) { e -> e.copy(changes = e.changes.map { c -> if (c is Change.Added) c.copy(record = record("label" to "forged")) else c }) },
            "dropped changes" to withEntry(l, k) { it.copy(changes = emptyList()) },
            "events" to withEntry(l, k) { it.copy(events = it.events + "tripwire.armed.biometric") },
            "previous link" to withEntry(l, k) { it.copy(prev = "1".repeat(64)) },
            "hash" to withEntry(l, k) { it.copy(hash = "2".repeat(64)) },
            "sequence number" to withEntry(l, k) { it.copy(seq = 99) },
            "signature" to withEntry(l, k) { it.copy(signature = it.signature!!.reversed()) },
            "removed signature on a signed history" to withEntry(l, k) { it.copy(signature = null) },
            "an entry removed" to Ledger(l.header, l.entries.filterIndexed { i, _ -> i != k }),
            "two entries swapped" to Ledger(l.header, l.entries.toMutableList().also { val a = it[k]; it[k] = it[k + 1]; it[k + 1] = a }),
            "an entry repeated" to Ledger(l.header, l.entries.take(k + 1) + l.entries[k] + l.entries.drop(k + 1)),
            "the first entry removed" to Ledger(l.header, l.entries.drop(1)),
            "the header's start time" to Ledger(l.header.copy(createdAt = t0.plusSeconds(1)), l.entries),
            "the header's key kind" to Ledger(l.header.copy(keyHeld = KeyHeld.HARDWARE), l.entries),
            "the header's key replaced by another" to Ledger(l.header.copy(publicKeySpki = java.util.Base64.getEncoder().encodeToString(SoftwareSigner().publicKeySpki)), l.entries),
        )
        for ((name, ledger) in tampered) {
            val v = ledger.verify()
            if (name == "removed signature on a signed history") {
                assertTrue("an unsigned entry in a signed history is weak, and the chain itself still holds", v.intact && v.weak)
            } else {
                assertFalse("not detected: $name", v.intact)
            }
        }
    }

    @Test
    fun aSingleCharacterChangedAnywhereInTheStoredTextIsNeverSilentlyAccepted() {
        val (l, _) = build(25, seed = 3)
        val text = LedgerCodec.encode(l)
        val random = Random(11)
        val replacements = "0123456789abcdefXYZ+/=\" {}[],:-"
        var undetected = 0
        var tried = 0
        repeat(4000) {
            val pos = random.nextInt(text.length)
            val old = text[pos]
            var c = replacements[random.nextInt(replacements.length)]
            if (c == old) c = if (old == 'x') 'y' else 'x'
            val mutated = text.substring(0, pos) + c + text.substring(pos + 1)
            tried += 1
            val decoded = LedgerCodec.decode(mutated)
            if (decoded is ReadResult.Ok && decoded.value.verify().authentic && decoded.value != l) {
                // Equal values with different text would be harmless; different values that still verify as authentic are not.
                // (Renaming a signature's key strips it: the chain still holds, but the entry is then unsigned and the history is not authentic.)
                undetected += 1
                System.err.println("undetected change at $pos: '$old' -> '$c' near ${text.substring(maxOf(0, pos - 30), minOf(text.length, pos + 30))}")
            }
        }
        assertEquals("$undetected of $tried single-character changes passed verification", 0, undetected)
    }

    @Test
    fun removingTheNewestEntriesIsNotDetectedAndTheTestSaysSoBecauseTheLimitIsReal() {
        val (l, _) = build(20)
        val kept = l.head!!.hash // what a person who wrote the latest hash down still has
        val cut = Ledger(l.header, l.entries.take(15))
        assertTrue("a shorter chain is still a valid chain", cut.verify().intact)
        assertTrue("but its head is not the one that was written down", cut.head!!.hash != kept)
        assertTrue(LedgerVerifier.NOT_CHECKED.any { it.contains("newest entries") })
    }

    @Test
    fun anUnsignedEntryAndASetBackClockAreWeakFindingsNotBrokenChains() {
        val signer = SoftwareSigner()
        var l = appended(Ledger.begin(t0, signer), snap(t0, "app:a" to record("k" to "1")), t0, signer)
        signer.failing = true
        l = appended(l, snap(t0, "app:a" to record("k" to "2")), t0.plusSeconds(100), signer)
        signer.failing = false
        l = appended(l, snap(t0, "app:a" to record("k" to "3")), t0.minusSeconds(500), signer)
        val v = l.verify()
        assertTrue(v.intact)
        assertTrue(v.weak)
        assertFalse("an unsigned entry means the history as a whole is not authentic", v.authentic)
        assertEquals(2, v.signedEntries)
        assertTrue(v.problems.any { it.text.contains("is not signed") })
        assertTrue(v.problems.any { it.text.contains("clock was set back") })
    }

    @Test
    fun aKeyThatIsNotTheOneTheHistoryBeganWithIsRecordedAndLaterEntriesAreUnsignedNotForged() {
        val first = SoftwareSigner()
        var l = appended(Ledger.begin(t0, first), snap(t0, "app:a" to record("k" to "1")), t0, first)
        val second = SoftwareSigner()
        l = appended(l, snap(t0, "app:a" to record("k" to "2")), t0.plusSeconds(60), second)
        assertNull(l.head!!.signature)
        assertTrue(l.head!!.events.contains(LedgerEvents.KEY_CHANGED))
        assertTrue(l.verify().intact)
    }

    @Test
    fun aHistoryWithNoKeyAtAllSaysNothingInItIsSigned() {
        var l = Ledger.begin(t0, null)
        assertEquals(KeyHeld.NONE, l.header.keyHeld)
        l = appended(l, snap(t0, "app:a" to record("k" to "1")), t0, null)
        val v = l.verify()
        assertTrue(v.intact)
        assertTrue(v.weak)
        assertEquals(0, v.signedEntries)
        assertTrue(v.problems.any { it.text.contains("no signing key") })
    }

    @Test
    fun anUnreplayableHistoryRefusesToGrowInsteadOfBuildingOnSomethingBroken() {
        val (l, signer) = build(5)
        val broken = withEntry(l, 2) { it.copy(changes = listOf(Change.Removed("app:never-existed", record("a" to "b")))) }
        assertNull(broken.headRecords)
        assertTrue(broken.append(snap(t0, "app:a" to record("k" to "1")), t0.plusSeconds(9999), Taken.OPENED, "1", emptyList(), signer) is AppendResult.Skipped)
        assertFalse(broken.verify().intact)
    }

    @Test
    fun aHistoryRewrittenFromScratchUnderAnotherKeyPassesOnlyUntilItIsTiedToTheKeyThePhoneHolds() {
        val real = SoftwareSigner()
        val (genuine, _) = build(12, real, seed = 4)
        // Someone who can write the file but cannot use the Keystore key builds a perfectly consistent history of their own.
        val mine = SoftwareSigner()
        val (forged, _) = build(12, mine, seed = 4)
        assertTrue("checked against the key it names, it is flawless: that is the limit of a check of the file alone", forged.verify().authentic)
        val tied = forged.verify(HeldKey.Spki(real.publicKeySpki))
        assertFalse(tied.authentic)
        assertFalse("another key's signatures are not a quiet weakness, they are a different author", tied.intact)
        assertTrue(tied.problems.any { it.text.contains("not the one this phone holds") })
        val good = genuine.verify(HeldKey.Spki(real.publicKeySpki))
        assertTrue(good.problems.toString(), good.authentic && good.keyTied && good.problems.isEmpty())
    }

    @Test
    fun aKeyThatCannotBeReadJustNowMakesTheHistoryUnprovenButNotBroken() {
        val signer = SoftwareSigner()
        val (l, _) = build(5, signer)
        val v = l.verify(HeldKey.Spki(null))
        assertTrue("nothing in the file is wrong", v.intact)
        assertTrue(v.weak)
        assertFalse("but nothing ties it to this phone", v.authentic)
        assertFalse(v.keyTied)
    }

    @Test
    fun aHistoryWithNoKeyIsNotAccusedOfHavingTheWrongOne() {
        val l = appended(Ledger.begin(t0, null), snap(t0, "app:a" to record("k" to "1")), t0, null)
        val v = l.verify(HeldKey.Spki(SoftwareSigner().publicKeySpki))
        assertTrue(v.intact)
        assertTrue(v.keyTied)
        assertEquals(listOf("This history has no signing key, so nothing in it is signed."), v.problems.map { it.text })
    }

    // ------------------------------------------------------------ the stored text

    @Test
    fun theStoredTextRoundTripsAndEveryProperPrefixIsRefused() {
        val (l, _) = build(8)
        val text = LedgerCodec.encode(l)
        assertEquals(ReadResult.Ok(l), LedgerCodec.decode(text))
        for (n in 0 until text.length step 7) assertTrue("prefix of $n", LedgerCodec.decode(text.substring(0, n)) is ReadResult.Failed)
    }

    @Test
    fun wrongFormatsAndUnknownWordsAreRefusedWithAReason() {
        val (l, _) = build(3)
        val good = LedgerCodec.encode(l)
        for (broken in listOf(good.replace("\"format\":1", "\"format\":2"), good.replace("OPENED", "SOMETIMES"), good.replace("\"held\":\"SOFTWARE\"", "\"held\":\"MAGIC\""), "[]", "{}", "", "null")) {
            assertTrue("accepted: ${broken.take(50)}", LedgerCodec.decode(broken) is ReadResult.Failed)
        }
    }

    @Test
    fun anyTextSurvivesInsideAnEntry() {
        val signer = SoftwareSigner()
        val odd = "quote\" back\\ slash\n newline \u0001 control é 中 😀 {} [] , :"
        val l = appended(Ledger.begin(t0, signer), snap(t0, "app:\"x\"" to record("label" to odd)), t0, signer, events = listOf(odd))
        val back = (LedgerCodec.decode(LedgerCodec.encode(l)) as ReadResult.Ok).value
        assertEquals(l, back)
        assertTrue(back.verify().intact)
    }

    // ------------------------------------------------------------ scale

    @Test
    fun aThousandEntriesOverAThousandAppsAreBuiltVerifiedAndStoredInReasonableTime() {
        val signer = SoftwareSigner()
        val random = Random(5)
        val apps = LinkedHashMap<String, Record>()
        repeat(2000) { i -> apps["app:com.example.app$i"] = record("label" to "App number $i", "tier" to "STORE", "signers" to "ab".repeat(32), "grants" to "CAMERA", "kinds" to "", "launcher" to "true") }
        var l = Ledger.begin(t0, signer)
        val started = System.nanoTime()
        repeat(1000) { i ->
            repeat(5) { apps["app:com.example.app${random.nextInt(2000)}"] = record("label" to "App", "tier" to "SIDELOAD", "signers" to "cd".repeat(32), "grants" to "", "kinds" to "", "launcher" to i.toString()) }
            l = appended(l, Snapshot(t0.plusSeconds(i * 600L), 34, apps.toSortedMap()), t0.plusSeconds(i * 600L), signer)
        }
        val built = (System.nanoTime() - started) / 1_000_000
        val t1 = System.nanoTime()
        val v = l.verify()
        val verified = (System.nanoTime() - t1) / 1_000_000
        val text = LedgerCodec.encode(l)
        val t2 = System.nanoTime()
        val back = LedgerCodec.decode(text)
        val decoded = (System.nanoTime() - t2) / 1_000_000
        println("ledger of 1,000 entries over 2,000 apps: built in $built ms, verified in $verified ms, ${text.length / 1024} KB, decoded in $decoded ms")
        assertTrue(v.problems.toString(), v.intact && !v.weak)
        assertEquals(ReadResult.Ok(l), back)
        assertTrue("verifying took $verified ms", verified < 20_000)
        assertTrue("the stored text is ${text.length} characters", text.length < 12_000_000)
        assertNotNull(Duration.ofMillis(built))
    }
}
