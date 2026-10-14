package io.github.h_sib.declared.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.h_sib.declared.app.BuildConfig
import io.github.h_sib.declared.core.flow.AppState
import io.github.h_sib.declared.core.flow.Event
import io.github.h_sib.declared.core.words.Glossary

/** The last tab: the places that are not part of the everyday loop. One row each; the privacy facts are one tap away and never repeated elsewhere. */
@Composable
fun MoreScreen(state: AppState, onEvent: (Event) -> Unit, scroll: ScrollState = rememberScrollState(), onOpenGuide: () -> Unit = {}) {
    val heard = state.live.events.size
    val prefs = LocalUiPrefs.current
    val easy = prefs.easy
    Column(Modifier.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp)) {
        ScreenTitle(if (easy) "Help" else "More")
        SectionLabel("Help", Modifier.padding(top = 16.dp, bottom = 8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NavCard(Glyph.EYE, "How to use Declared", "A one minute tour") { onOpenGuide() }
            NavCard(Glyph.PERSON, "Why it is called Declared", "A short story") { prefs.openIntro() }
            ViewModeSwitch()
            if (easy) NavCard(Glyph.INTEGRITY, "What this app cannot see", "Its limits, listed") { onEvent(Event.OpenBlindSpots) }
        }
        if (!easy) {
            SectionLabel("Look closer", Modifier.padding(top = 20.dp, bottom = 8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NavCard(Glyph.APPS, "All apps", "Every app this app can see, by origin") { onEvent(Event.OpenApps) }
                NavCard(Glyph.EYE, "What this app cannot see", "Its limits, listed") { onEvent(Event.OpenBlindSpots) }
                NavCard(Glyph.LIVE, "Live view", if (state.live.watching) "Watching. $heard heard." else "Not watching") { onEvent(Event.OpenLiveView) }
                if ((state.changes?.items?.size ?: 0) > 0) NavCard(Glyph.REFRESH, "What changed", "Since you last looked") { onEvent(Event.OpenWhatChanged) }
            }
            SectionLabel("You", Modifier.padding(top = 20.dp, bottom = 8.dp))
            NavCard(Glyph.PERSON, "About you", "Two questions only you can answer") { onEvent(Event.OpenAboutYou) }
            SectionLabel("Automatic checks", Modifier.padding(top = 20.dp, bottom = 8.dp))
            AutoCheckCard()
            SectionLabel("Jargon", Modifier.padding(top = 20.dp))
            Expandable("Plain-words decoder", summary = "${Glossary.terms.size} terms, one line each") {
                for (t in Glossary.terms) Column {
                    Text(t.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Text(t.meaning, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        SectionLabel("Screenshots", Modifier.padding(top = 20.dp, bottom = 8.dp))
        BlockScreenshotsSwitch()
        SectionLabel("Privacy", Modifier.padding(top = 20.dp))
        Expandable("Nothing leaves this phone", summary = "No internet permission") {
            for (line in listOf(
                "Declared has no internet permission. The build refuses to add one.",
                "Your answers and the history are kept encrypted on this phone, with keys that never leave it.",
                "To erase everything, clear the app's storage in Android's settings or uninstall it.",
            )) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                    StatusMarker(Status.CLEAR, Modifier.padding(top = 4.dp).size(12.dp))
                    Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DeclaredMark(Modifier.size(20.dp))
            Text("Declared ${BuildConfig.VERSION_NAME} by H-sib", style = MaterialTheme.typography.labelSmall.copy(fontFamily = DeclaredMono), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
