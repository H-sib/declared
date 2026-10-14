package io.github.h_sib.declared.core.words

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlossaryTest {
    @Test
    fun `every meaning is one short plain sentence`() {
        for (t in Glossary.terms) {
            assertTrue("${t.name} is too long: ${t.meaning.length}", t.meaning.length <= 80)
            assertTrue(t.name, t.meaning.endsWith("."))
            assertTrue("${t.name} runs on", t.meaning.count { it == '.' } <= 2)
            val all = t.name + " " + t.meaning
            assertTrue("${t.name} has a dash", all.none { it == '—' || it == '–' })
            assertTrue(t.name, t.patterns.isNotEmpty())
        }
        assertEquals("names are unique", Glossary.terms.size, Glossary.terms.map { it.name }.toSet().size)
        val patterns = Glossary.terms.flatMap { it.patterns.map(String::lowercase) }
        assertEquals("a pattern belongs to one term only", patterns.size, patterns.toSet().size)
    }

    @Test
    fun `terms are found by their longest form, whatever the case`() {
        val text = "An Accessibility service is on. The proxy is set and USB debugging too."
        val m = Glossary.matches(text)
        assertEquals(listOf("Accessibility service", "Proxy", "USB debugging"), m.map { it.term.name })
        assertEquals("Accessibility service", text.substring(m[0].start, m[0].end))
    }

    @Test
    fun `a word inside another word is not a term`() {
        assertEquals(emptyList<String>(), Glossary.matches("Proxying, hashtag and overlaying are not it.").map { it.term.name })
        assertEquals(emptyList<String>(), Glossary.matches("The roots and the rooting are not it.").map { it.term.name })
        assertEquals(listOf("Root"), Glossary.matches("A rooted phone.").map { it.term.name })
        assertEquals("already signed in is not a signature", emptyList<String>(), Glossary.matches("Accounts that are already signed in.").map { it.term.name })
        assertEquals(listOf("Signature"), Glossary.matches("Entries are signed with a key.").map { it.term.name })
    }

    @Test
    fun `terms in several texts come back once each, in order`() {
        val found = Glossary.termsIn(listOf("A proxy. A tripwire.", "Another proxy, then a hash."))
        assertEquals(listOf("Proxy", "Tripwire", "Hash"), found.map { it.name })
    }

    @Test
    fun `sentences are split but abbreviations and numbers are not`() {
        assertEquals(listOf("One thing.", "Two things.", "Android 14 is out."), Bits.split("One thing. Two things. Android 14 is out."))
        assertEquals(listOf("Some apps, e.g. a bank app, do this.", "Others do not."), Bits.split("Some apps, e.g. a bank app, do this. Others do not."))
        assertEquals(listOf("Version 3.5 is fine."), Bits.split("Version 3.5 is fine."))
        assertEquals(emptyList<String>(), Bits.split("   "))
    }
}
