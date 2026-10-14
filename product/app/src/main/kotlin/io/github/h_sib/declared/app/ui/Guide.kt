package io.github.h_sib.declared.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private class GuidePage(val glyph: Glyph?, val title: String, val lines: List<String>)

/** What a person needs to know to use the app, in the order they meet it. Short lines; the words are the app's own. */
private val pages = listOf(
    GuidePage(null, "Declared checks your phone", listOf(
        "It shows which apps can do powerful things.",
        "It points out risky settings.",
        "It never changes anything by itself. You decide.",
    )),
    GuidePage(Glyph.APPS, "Home: where you stand", listOf(
        "The top card counts what needs a look.",
        "The phone shows six areas. Tap one to open it.",
        "Press Check this phone to read everything again.",
    )),
    GuidePage(Glyph.ADMIN, "Check: what to look at", listOf(
        "A diamond means look first. A triangle means look. A dot is good to know.",
        "Tap a number at the top to see only that level.",
        "Apps with the same ability are grouped. Tap a group to open it.",
    )),
    GuidePage(Glyph.EYE, "Open a finding", listOf(
        "Start with What you can do. It has a button to the right setting.",
        "Tap Why you should care for the short version.",
        "Blue words are explained in plain words.",
        "Nerd info has the sources, if you want them.",
    )),
    GuidePage(Glyph.NETWORK, "Paths and History", listOf(
        "Paths shows how someone could reach what matters.",
        "Turn on Preview this change to see what it would fix. Your phone is not changed.",
        "History remembers every check and what changed.",
    )),
    GuidePage(Glyph.INTEGRITY, "Make Android work better for you", listOf(
        "Switch off abilities you do not use.",
        "Keep a screen lock and install updates.",
        "Check again after you install a new app.",
        "Declared cannot see everything. See More, then What this app cannot see.",
    )),
    GuidePage(Glyph.PERSON, "Prefer it simple?", listOf(
        "Simple view has bigger text and fewer things.",
        "Each card says what it means and what to do.",
        "Turn it on or off any time under More.",
    )),
)

/** The tour: a few pages, a close at the top, and Done at the end. It sits over the app, so closing it leaves the person where they were. */
@Composable
fun GuideScreen(onClose: () -> Unit) {
    val pager = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == pages.lastIndex
    BackHandler(onBack = onClose)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp, vertical = 12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DeclaredMark(Modifier.size(22.dp))
                Text("How to use Declared", Modifier.semantics { heading() }, style = MaterialTheme.typography.labelLarge)
            }
            TextButton(onClick = onClose) { Text("Close") }
        }
        HorizontalPager(pager, Modifier.weight(1f)) { index ->
            val page = pages[index]
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    if (page.glyph == null) DeclaredMark(Modifier.size(44.dp)) else GlyphIcon(page.glyph, MaterialTheme.colorScheme.primary, iconSize = 48.dp)
                }
                Spacer(Modifier.height(24.dp))
                Text(page.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    for (line in page.lines) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        StatusMarker(Status.NOTE, Modifier.padding(top = 6.dp).size(10.dp))
                        Text(emphasize(line), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    }
                    Decoder(page.lines, max = 2)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.Center) {
            for (i in pages.indices) {
                Box(
                    Modifier.padding(horizontal = 4.dp).size(if (i == pager.currentPage) 22.dp else 8.dp, 8.dp).clip(CircleShape)
                        .background(if (i == pager.currentPage) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        Button(
            onClick = { if (last) onClose() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            Modifier.fillMaxWidth().heightIn(min = 54.dp),
            shape = RoundedCornerShape(14.dp),
        ) { Text(if (last) "Done" else "Next", style = MaterialTheme.typography.labelLarge) }
    }
    }
}
