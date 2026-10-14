package io.github.h_sib.declared.core.home

import io.github.h_sib.declared.core.model.Attention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupingTest {
    private data class Row(val id: String, val attention: Attention)

    private fun group(vararg rows: Row) = Grouping.of(rows.toList(), { it.id }, { it.attention })

    @Test
    fun `the same ability in several apps becomes one row, at the place of the first`() {
        val g = group(
            Row("notification-listener:a", Attention.NOTE),
            Row("condition:screen-lock", Attention.LOOK_FIRST),
            Row("notification-listener:b", Attention.NOTE),
            Row("notification-listener:c", Attention.NOTE),
        )
        assertEquals(listOf(FindingKind.NOTIFICATIONS, FindingKind.SETTING), g.map { it.kind })
        assertEquals(3, g[0].members.size)
        assertTrue(g[0].isGroup)
        assertFalse(g[1].isGroup)
    }

    @Test
    fun `different attention levels are never merged, so a worse one is not hidden in a quiet group`() {
        val g = group(Row("overlay:a", Attention.LOOK), Row("overlay:b", Attention.LOOK_FIRST), Row("overlay:c", Attention.LOOK))
        assertEquals(2, g.size)
        assertEquals(listOf(Attention.LOOK, Attention.LOOK_FIRST), g.map { it.attention })
        assertEquals(2, g[0].members.size)
    }

    @Test
    fun `conditions are never merged, whatever their number`() {
        val g = group(Row("condition:usb-debugging", Attention.LOOK), Row("condition:developer-options", Attention.LOOK), Row("condition:http-proxy", Attention.LOOK))
        assertEquals(3, g.size)
        assertTrue(g.none { it.isGroup })
        assertEquals(listOf(FindingKind.SETTING, FindingKind.SETTING, FindingKind.NETWORK), g.map { it.kind })
    }

    @Test
    fun `nothing is dropped and nothing is counted twice`() {
        val rows = listOf(
            Row("accessibility:a", Attention.LOOK_FIRST), Row("accessibility:b", Attention.LOOK_FIRST), Row("review:not-recognized:x", Attention.LOOK_FIRST),
            Row("review:signer-changed:y", Attention.LOOK_FIRST), Row("condition:software-keys", Attention.NOTE), Row("sms-access:z", Attention.LOOK),
        )
        val g = Grouping.of(rows, { it.id }, { it.attention })
        assertEquals(rows.sortedBy { it.id }, g.flatMap { it.members }.sortedBy { it.id })
        assertEquals(setOf(FindingKind.ACCESSIBILITY, FindingKind.NOT_RECOGNIZED, FindingKind.NEW_SIGNER, FindingKind.INTEGRITY, FindingKind.SMS), g.map { it.kind }.toSet())
    }

    @Test
    fun `every kind has plain words and none of them uses a dash`() {
        for (k in FindingKind.entries) {
            assertTrue(k.name, k.title.isNotBlank() && k.title.none { it == '—' || it == '–' })
            assertTrue(k.name, !k.groupable || k.noun.isNotBlank())
        }
    }

    @Test
    fun `an id that is not known falls into settings and is still listed`() {
        assertEquals(FindingKind.SETTING, Grouping.kindOf("something-new:x"))
        assertEquals(1, group(Row("something-new:x", Attention.NOTE)).size)
    }
}
