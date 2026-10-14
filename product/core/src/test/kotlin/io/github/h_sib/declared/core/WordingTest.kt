package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.accessibility.Ability
import io.github.h_sib.declared.core.model.Finding
import io.github.h_sib.declared.core.report.Describe
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.tier.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wording rules of DESIGN.md sections 2 and 8 are acceptance criteria, so they are tests: never call an app
 * malicious, never show a score, never say "safe", never recommend rooting or a reset, always give a trade-off.
 */
class WordingTest {

    /** Banned as a word or the start of one ("malicious", "maliciously"). */
    private val bannedPrefixes = listOf(
        "malicious", "malware", "virus", "infected", "suspicious", "trojan", "spyware", "hacked", "compromised", "danger", "factory reset",
    )

    /** Banned only as a whole word, so honest disclaimers such as "is not a safety guarantee" stay allowed. */
    private val bannedWords = listOf("safe", "secure", "score", "rating", "root", "rooted", "flash", "flashing", "wipe")

    private fun allText(f: Finding): List<String> =
        listOf(f.title, f.subject, f.meaning) + f.whatItCannotTell + f.actions.flatMap { listOf(it.text, it.tradeOff) } +
            (f.claim + f.context).flatMap { it.limits + Describe.observation(it) }

    /** One finding for every tier and every single ability, so the generated text is reviewed in all of its shapes. */
    private fun everyShape(): List<Finding> {
        val shapes = mutableListOf<Finding>()
        shapes += everyShapeOfEveryKind()
        assertTrue("condition findings must be among the shapes", shapes.any { it.id.startsWith("condition:") })
        val tierFacts = mapOf(
            Tier.PLATFORM to facts("p.t", platformSigned = true),
            Tier.SYSTEM to facts("p.t", system = true),
            Tier.STORE to facts("p.t"),
            Tier.SIDELOAD to facts("p.t", installing = "com.google.android.packageinstaller"),
            Tier.ADB to facts("p.t", installing = null, initiating = "com.android.shell"),
            Tier.UNKNOWN to facts("p.t", installing = null, initiating = null),
        )
        for ((_, f) in tierFacts) {
            for (bits in Ability.entries.map { it.bit } + listOf(0, 0x4, 0xff)) for (tool in listOf(null, true, false)) {
                val svc = service("p.t", bits = bits, tool = tool)
                val report = builder(
                    sources(
                        accessibility = ReadResult.Ok(reading(listOf(svc), setOf(svc.component))),
                        packages = FakePackageFactsSource(mapOf("p.t" to ReadResult.Ok(f))),
                    ),
                ).build(34)
                shapes += report.findings
            }
        }
        return shapes
    }

    private fun startsWithWord(lower: String, word: String) =
        Regex("(?<![a-z])" + Regex.escape(word)).containsMatchIn(lower)

    private fun containsWholeWord(lower: String, word: String) =
        Regex("(?<![a-z])" + Regex.escape(word) + "(?![a-z])").containsMatchIn(lower)

    /** Guards against a checker that silently matches nothing: each rule must be able to fail. */
    @Test
    fun theCheckersCanActuallyFail() {
        assertTrue(startsWithWord("this is maliciously built", "malicious"))
        assertFalse(startsWithWord("this is a benign app", "malicious"))
        assertTrue(containsWholeWord("your phone is safe.", "safe"))
        assertFalse(containsWholeWord("not a safety guarantee", "safe"))
        assertTrue(containsWholeWord("risk score: 5", "score"))
        assertTrue(listOf(0x2014, 0x2013).all { code -> "a${code.toChar()}b".any { it.code == code } })
    }

    @Test
    fun noGeneratedTextUsesBannedWordsOrDashes() {
        val shapes = everyShape()
        assertTrue(shapes.size > 100)
        for (f in shapes) for (text in allText(f)) {
            val lower = text.lowercase()
            for (word in bannedPrefixes) assertFalse("'$word' in: $text", startsWithWord(lower, word))
            for (word in bannedWords) assertFalse("'$word' in: $text", containsWholeWord(lower, word))
            assertFalse("dash in: $text", text.any { it.code == 0x2014 || it.code == 0x2013 })
        }
    }

    @Test
    fun everyActionNamesWhatItCosts() {
        for (f in everyShape()) {
            assertTrue(f.actions.isNotEmpty())
            for (a in f.actions) assertTrue("empty trade-off for ${a.text}", a.tradeOff.length > 20)
        }
    }

    @Test
    fun everyFindingStatesWhatItCannotTellAndHasEvidence() {
        for (f in everyShape()) {
            assertTrue(f.whatItCannotTell.size >= 3)
            assertTrue(f.claim.isNotEmpty())
            assertTrue(f.claim.all { it.limits.isNotEmpty() })
        }
    }

    @Test
    fun theMeaningNeverAccusesTheAppAndSaysSo() {
        for (f in appShapes()) assertTrue(f.meaning, f.meaning.contains("does not mean that") || f.meaning.contains("Built-in"))
    }

    /** Findings about an app have an origin and can accuse it; findings about the phone, the network or the user's own answers do not. */
    private fun appShapes() = everyShape().filter { !it.id.startsWith("condition:") && !it.id.startsWith("review:") }

    @Test
    fun conditionFindingsNeverBlameAPersonOrPredictHarm() {
        val conditions = everyShape().filter { it.id.startsWith("condition:") }
        assertTrue(conditions.map { it.id }.toSet().size >= 8)
        for (f in conditions) {
            val lower = f.meaning.lowercase()
            for (claim in listOf("you have been", "you are being", "someone is", "is attacking", "will be")) assertFalse("'$claim' in: ${f.meaning}", lower.contains(claim))
            assertTrue(f.whatItCannotTell.any { it.contains("Whether") || it.contains("Who") || it.contains("Which") })
        }
    }

    @Test
    fun onlyAFileInstallIsEverWordedAsNotFromAStore() {
        for (f in appShapes()) {
            val origin = f.context.first { it.id.value == "app.install-origin" }.value.toString()
            if (!origin.startsWith("Text(text=SIDELOAD")) assertFalse(origin, origin.lowercase().contains("not from a store"))
        }
        assertEquals(true, appShapes().any { it.context.first().value.toString().startsWith("Text(text=SIDELOAD") })
    }
}
