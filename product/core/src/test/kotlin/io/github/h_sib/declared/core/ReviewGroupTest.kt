package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.state.Review
import org.junit.Assert.assertEquals
import org.junit.Test

class ReviewGroupTest {
    private data class Held(val pkg: String, val label: String, val what: String)

    private fun group(vararg items: Held) = Review.groupByApp(items.toList(), { it.pkg }, { it.label })

    @Test
    fun anAppThatHoldsSeveralThingsIsOneQuestion() {
        val g = group(
            Held("com.truecaller", "Truecaller", "screen"), Held("com.whatsapp", "WhatsApp", "notifications"),
            Held("com.truecaller", "Truecaller", "notifications"), Held("com.whatsapp", "WhatsApp", "overlay"), Held("com.truecaller", "Truecaller", "overlay"),
        )
        assertEquals(listOf("Truecaller", "WhatsApp"), g.map { it.label })
        assertEquals(listOf(3, 2), g.map { it.items.size })
    }

    @Test
    fun nothingIsDroppedAndNothingIsAskedTwice() {
        val all = listOf(Held("a", "A", "1"), Held("b", "B", "1"), Held("a", "A", "2"), Held("c", "C", "1"), Held("b", "B", "2"))
        val g = Review.groupByApp(all, { it.pkg }, { it.label })
        assertEquals(all.sortedBy { it.pkg + it.what }, g.flatMap { it.items }.sortedBy { it.pkg + it.what })
        assertEquals(3, g.size)
    }

    @Test
    fun theOrderFollowsTheFirstAppearanceAndAnEmptyListStaysEmpty() {
        assertEquals(listOf("b", "a"), group(Held("b", "B", "1"), Held("a", "A", "1"), Held("b", "B", "2")).map { it.pkg })
        assertEquals(0, group().size)
    }
}
