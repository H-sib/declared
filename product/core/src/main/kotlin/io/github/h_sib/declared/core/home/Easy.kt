package io.github.h_sib.declared.core.home

import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.words.Bits

/**
 * The words of the simple view: a count in plain language, and for each thing one short sentence of why it matters. The simple view shows
 * only what needs action; everything else stays in the full list. It says so when nothing needs action, and that this is not a promise.
 */
object Easy {
    fun headline(urgent: Int): String = when (urgent) {
        0 -> "Nothing needs your attention right now."
        1 -> "1 thing needs your attention."
        else -> "$urgent things need your attention."
    }

    fun subline(urgent: Int): String =
        if (urgent == 0) "Declared can only check some things, so this is not a promise." else "Tap a card to see what to do."

    /** The first sentence of a longer text: enough to know what it means. */
    fun why(text: String): String = Bits.split(text).firstOrNull() ?: text

    /** What needs action, worst first and otherwise in the order given: Look first, then Look. Info is left out; it is in the full list. */
    fun <T> urgentFirst(items: List<T>, attention: (T) -> Attention): List<T> {
        fun rank(a: Attention) = when (a) {
            Attention.LOOK_FIRST -> 0
            Attention.LOOK -> 1
            Attention.NOTE -> 2
        }
        return items.filter { attention(it) != Attention.NOTE }.sortedBy { rank(attention(it)) }
    }
}
