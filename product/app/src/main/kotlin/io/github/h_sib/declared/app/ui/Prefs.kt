package io.github.h_sib.declared.app.ui

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.edit

/**
 * Two choices about how the app looks, kept on this phone: whether the simple view is on, and whether the tour has been seen. They are not
 * readings of the phone and not answers about it, so they are kept apart from the encrypted baseline and history.
 */
@Stable
class UiPrefs(private val store: SharedPreferences?, lab: Boolean = false) {
    /** The intro is shown on the first launch and when asked for. A lab script never sees it, so it cannot stand between a script and the screens. */
    var introOpen by mutableStateOf(!lab && store != null && !store.getBoolean(INTRO_SEEN, false))
        private set

    /** Screenshots are allowed unless the person turns this on. */
    var blockScreenshots by mutableStateOf(store?.getBoolean(BLOCK_SCREENSHOTS, false) ?: false)
        private set

    var easy by mutableStateOf(store?.getBoolean(EASY, false) ?: false)
        private set

    var guideSeen by mutableStateOf(store?.getBoolean(GUIDE_SEEN, false) ?: false)
        private set

    fun chooseEasy(on: Boolean) {
        easy = on
        store?.edit { putBoolean(EASY, on) }
    }

    fun chooseBlockScreenshots(on: Boolean) {
        blockScreenshots = on
        store?.edit { putBoolean(BLOCK_SCREENSHOTS, on) }
    }

    fun openIntro() {
        introOpen = true
    }

    fun closeIntro() {
        introOpen = false
        store?.edit { putBoolean(INTRO_SEEN, true) }
    }

    fun markGuideSeen() {
        guideSeen = true
        store?.edit { putBoolean(GUIDE_SEEN, true) }
    }

    companion object {
        const val FILE = "ui"
        const val EASY = "easy"
        const val GUIDE_SEEN = "guideSeen"
        const val INTRO_SEEN = "introSeen"
        const val BLOCK_SCREENSHOTS = "blockScreenshots"
    }
}

val LocalUiPrefs = staticCompositionLocalOf { UiPrefs(null) }
