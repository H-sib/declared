package io.github.h_sib.declared.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.h_sib.declared.core.words.Bits
import io.github.h_sib.declared.core.words.Glossary

/** The text with every jargon word picked out in bold and the accent color, so it is clear which words the decoder explains. */
@Composable
fun emphasize(text: String): AnnotatedString {
    val color = MaterialTheme.colorScheme.primary
    return remember(text, color) {
        buildAnnotatedString {
            var at = 0
            for (m in Glossary.matches(text)) {
                append(text.substring(at, m.start))
                withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = color)) { append(text.substring(m.start, m.end)) }
                at = m.end
            }
            append(text.substring(at))
        }
    }
}

/**
 * A detailed text, never a wall of it: one sentence per line with a marker, the first one a little heavier, jargon picked out,
 * and a short "In plain words" box under it for the jargon words it uses. A text with one sentence is just that sentence.
 */
@Composable
fun Body(text: String, modifier: Modifier = Modifier, decode: Boolean = true) {
    val bits = remember(text) { Bits.split(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (bits.size <= 1) {
            Text(emphasize(text), style = MaterialTheme.typography.bodyMedium)
        } else {
            bits.forEachIndexed { i, bit ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 9.dp).size(6.dp).clip(CircleShape).background(if (i == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline))
                    Text(
                        emphasize(bit), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (i == 0) FontWeight.Medium else null,
                        color = if (i == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (decode) Decoder(listOf(text))
    }
}

/** A short list where each line is its own item with a status marker, for what a screen cannot tell you, and the like. */
@Composable
fun Bullets(items: List<String>, status: Status, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (item in items) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
            StatusMarker(status, Modifier.padding(top = 4.dp).size(12.dp))
            Text(emphasize(item), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The plain meanings of the jargon words found in [texts] (at most [max]), each in a line or two. Nothing is drawn when there are none. */
@Composable
fun Decoder(texts: List<String>, modifier: Modifier = Modifier, max: Int = 3) {
    val terms = remember(texts) { Glossary.termsIn(texts).take(max) }
    if (terms.isEmpty()) return
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 12.dp, vertical = 10.dp).semantics(mergeDescendants = true) { },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("In plain words", style = MaterialTheme.typography.labelMedium.copy(fontFamily = DeclaredMono), color = MaterialTheme.colorScheme.primary)
        val strong = MaterialTheme.colorScheme.onSurface
        for (t in terms) Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = strong)) { append(t.name) }
                append("  ")
                append(t.meaning)
            },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One fact as a label and its value, like a line of a spec sheet. For the nerd corners, where a paragraph would be too much to scan. */
@Composable
fun FactRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label.uppercase(), Modifier.weight(0.34f), style = MaterialTheme.typography.labelSmall.copy(fontFamily = DeclaredMono), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(emphasize(value), Modifier.weight(0.66f), style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}
