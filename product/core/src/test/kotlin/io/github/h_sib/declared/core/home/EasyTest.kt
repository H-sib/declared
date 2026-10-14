package io.github.h_sib.declared.core.home

import io.github.h_sib.declared.core.model.Attention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EasyTest {
    private data class Row(val id: String, val attention: Attention)

    @Test
    fun `the headline counts what needs action in plain words`() {
        assertEquals("Nothing needs your attention right now.", Easy.headline(0))
        assertEquals("1 thing needs your attention.", Easy.headline(1))
        assertEquals("4 things need your attention.", Easy.headline(4))
    }

    @Test
    fun `a quiet result is never called safe, and the simple view says it is not a promise`() {
        assertTrue(Easy.subline(0).contains("not a promise"))
        val all = listOf(0, 1, 2, 9).flatMap { listOf(Easy.headline(it), Easy.subline(it)) }
        for (text in all) {
            assertFalse(text, listOf("safe", "protected", "secure", "clean", "no threats").any { text.lowercase().contains(it) })
            assertTrue(text, text.none { it == '—' || it == '–' })
        }
    }

    @Test
    fun `why is the first sentence only`() {
        assertEquals("Anyone can open the phone.", Easy.why("Anyone can open the phone. Some checks also need a lock."))
        assertEquals("One sentence.", Easy.why("One sentence."))
        assertEquals("", Easy.why(""))
    }

    @Test
    fun `urgent things come worst first and info is left out`() {
        val rows = listOf(Row("a", Attention.NOTE), Row("b", Attention.LOOK), Row("c", Attention.LOOK_FIRST), Row("d", Attention.LOOK), Row("e", Attention.LOOK_FIRST))
        val urgent = Easy.urgentFirst(rows) { it.attention }
        assertEquals(listOf("c", "e", "b", "d"), urgent.map { it.id })
    }

    @Test
    fun `with nothing urgent the list is empty`() {
        assertEquals(emptyList<Row>(), Easy.urgentFirst(listOf(Row("a", Attention.NOTE)), { it.attention }))
        assertEquals(emptyList<Row>(), Easy.urgentFirst(emptyList<Row>(), { it.attention }))
    }
}
