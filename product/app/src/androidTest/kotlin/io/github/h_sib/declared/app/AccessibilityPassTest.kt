package io.github.h_sib.declared.app

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.accessibility.enableAccessibilityChecks
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.tryPerformAccessibilityChecks
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The accessibility pass for the app itself (DESIGN.md section 8): every screen is opened the way a person opens it, and
 * Google's Accessibility Test Framework checks run on each, once at normal text size and once at twice the size.
 * The framework looks at touch target sizes, text contrast, missing labels and clickable areas that overlap.
 *
 * It uses the real activity on the real phone, with a fresh baseline each time, so it also walks the first-run review.
 * When the lab's fixture apps are installed and switched on the review has cards with answer chips and those are checked
 * too; without them the review is empty and the chips are simply not part of the walk.
 *
 * A screen reader itself is not run here (no TalkBack on the emulator image); this checks what a screen reader is given.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalTestApi::class)
class AccessibilityPassTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var scenario: ActivityScenario<MainActivity>? = null
    private var originalScale: String? = null

    @After
    fun restore() {
        scenario?.close()
        instrumentation.targetContext.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit()
        shell(if (originalScale == null) "settings delete system font_scale" else "settings put system font_scale $originalScale")
    }

    @Test
    fun everyScreenPassesAtNormalTextSize() = walk("1.0")

    @Test
    fun everyScreenPassesAtTwiceTheTextSize() = walk("2.0")

    @Test
    fun simpleViewPassesAtNormalTextSize() = simpleWalk("1.0")

    @Test
    fun simpleViewPassesAtTwiceTheTextSize() = simpleWalk("2.0")

    /** The simple view and the tour, opened the way a person opens them: the view is chosen first, then the intro, the tour, a card, the list and Help. */
    private fun simpleWalk(scale: String) {
        originalScale = shell("settings get system font_scale").trim().takeIf { it.isNotEmpty() && it != "null" }
        shell("settings put system font_scale $scale")
        val context = instrumentation.targetContext
        for (name in listOf("declared-state.bin", "declared-state.bin.bak", "declared-history.bin", "declared-history.bin.bak")) File(context.filesDir, name).delete()
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("easy", true).putBoolean("guideSeen", false).commit()
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).putExtra("allowCapture", true))
        compose.enableAccessibilityChecks()

        waitFor("Next")
        check("simple: first-run page 1")
        click("Next")
        click("Next")
        click("Start the review", scrollTo = true)
        waitFor("Finish the review", scrollTo = true)
        click("Finish the review")

        waitFor("Check my phone again")
        check("simple home")
        click("Take the tour", scrollTo = true)
        waitFor("How to use Declared")
        for (page in 1..6) {
            check("tour page $page")
            click("Next")
        }
        check("tour last page")
        click("Done")

        if (compose.onAllNodesWithTag("easy-card").fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodesWithTag("easy-card").onFirst().performScrollTo().performClick()
            waitFor("What this means")
            check("simple finding")
            click("Technical details", scrollTo = true)
            check("simple finding with technical details")
            back()
        }
        tab("To do")
        waitFor("Check my phone again")
        check("simple to-do list")
        tab("Help")
        waitFor("How to use Declared")
        check("simple help")
        // The story of the name opens from Help, and ends in a button.
        click("Why it is called Declared")
        waitFor("Continue")
        compose.waitForIdle()
        check("intro")
        click("Continue")
        click("Simple view")
        waitFor("Look closer")
        check("back in the full view")
    }

    private fun walk(scale: String) {
        originalScale = shell("settings get system font_scale").trim().takeIf { it.isNotEmpty() && it != "null" }
        shell("settings put system font_scale $scale")
        val context = instrumentation.targetContext
        File(context.filesDir, "declared-state.bin").delete()
        File(context.filesDir, "declared-state.bin.bak").delete()
        File(context.filesDir, "declared-history.bin").delete()
        File(context.filesDir, "declared-history.bin.bak").delete()

        // A lab script's flag: the window is not secure, so the framework can take the screenshot its contrast check needs.
        scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java).putExtra("allowCapture", true))
        compose.enableAccessibilityChecks()

        // First run: three short pages, then the review.
        waitFor("Next")
        check("first-run page 1")
        click("Next")
        check("first-run page 2")
        click("Next")
        check("first-run page 3")
        click("Start the review", scrollTo = true)

        waitFor("Finish the review", scrollTo = true)
        check("review screen")
        // Answer the first card, if there is one: a chip is the one control here that is easy to get too small.
        if (compose.onAllNodesWithText("I recognize this").fetchSemanticsNodes().isNotEmpty()) {
            compose.onAllNodesWithText("I recognize this").onFirst().performClick()
            check("review screen after an answer")
        }
        click("Finish the review")

        waitFor("Check this phone")
        check("home")
        // At twice the text size the button is below the first screenful of the home screen, which scrolls.
        click("Check this phone", scrollTo = true)
        waitFor("What this checks", scrollTo = true)
        check("check tab")
        click("Phone and network readings", scrollTo = true)
        check("check tab with the readings open")
        // The level tiles are filters: one is pressed, the list is checked as filtered, and a second press clears it.
        compose.onNode(hasScrollAction()).performScrollToIndex(0)
        click("Look first")
        check("check tab filtered to one level")
        click("Look first")
        // The first finding, then its longest part.
        clickContaining("confidence")
        waitFor("What you can do")
        check("finding")
        click("Nerd info", scrollTo = true)
        check("finding with the nerd info open")
        back()

        tab("Paths")
        waitFor("Attack paths")
        check("attack paths")
        if (compose.onAllNodesWithText("What stops it").fetchSemanticsNodes().isNotEmpty()) {
            click("What stops it", scrollTo = true)
            // Preview the first change the plan offers, so the chip in its previewing state is checked too.
            if (compose.onAllNodesWithText("Preview this change").fetchSemanticsNodes().isNotEmpty()) {
                click("Preview this change", scrollTo = true)
                check("attack paths with a preview")
            }
        }
        click("Answer the questions about you", scrollTo = true)
        waitFor("Do your accounts send you sign-in codes")
        check("about you")
        click("Done", scrollTo = true)

        tab("History")
        waitFor("Tripwires")
        check("history")
        // A check of the history is only offered something to check once the first entry is written; the result view has the longest text.
        if (compose.onAllNodesWithText("Check the history now").fetchSemanticsNodes().isNotEmpty()) {
            click("Check the history now", scrollTo = true)
            waitFor("What this cannot see", scrollTo = true)
            check("history after a check")
        }

        tab("More")
        waitFor("Look closer")
        check("more")
        click("Nothing leaves this phone", scrollTo = true)
        check("more with the privacy facts open")
        click("What this app cannot see", scrollTo = true)
        waitFor("On every phone", scrollTo = true)
        check("blind-spot map")
        back()

        click("All apps", scrollTo = true)
        waitFor("apps", scrollTo = false)
        check("list of apps")
        back()

        click("Live view", scrollTo = true)
        waitFor("Watching while this app is on screen")
        check("live view")
        back()

        if (compose.onAllNodesWithText("What changed").fetchSemanticsNodes().isNotEmpty()) {
            click("What changed", scrollTo = true)
            waitFor("Changed since you last looked")
            check("what changed")
            back()
        }
    }

    /** The bottom bar's own entry: a tab's name is often also the title of the screen, and the bar comes last in the tree. */
    private fun tab(name: String) {
        compose.onAllNodesWithText(name).onLast().assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun back() {
        compose.onNodeWithContentDescription("Back").assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun clickContaining(text: String) {
        compose.waitUntil(timeoutMillis = 30_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(text, substring = true).onFirst().assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private fun check(screen: String) {
        compose.waitForIdle()
        try {
            compose.onRoot().tryPerformAccessibilityChecks()
        } catch (e: AssertionError) {
            throw AssertionError("Accessibility check failed on the $screen: ${e.message}", e)
        }
    }

    private fun waitFor(text: String, scrollTo: Boolean = false) {
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() ||
                (scrollTo && compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty())
        }
        if (scrollTo) scrollList(text)
    }

    private fun click(text: String, scrollTo: Boolean = false) {
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() ||
                (scrollTo && compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty())
        }
        if (scrollTo) scrollList(text)
        // A control that is in the tree but off the screen cannot be used by a person, so it is not clicked silently.
        compose.onAllNodesWithText(text).onFirst().assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    /** A list builds only what is on screen, so a node further down must be scrolled to before it exists; one that exists is scrolled into view. */
    private fun scrollList(text: String) {
        val existing = compose.onAllNodesWithText(text, substring = true)
        if (existing.fetchSemanticsNodes().isNotEmpty()) {
            // A screen that does not scroll (the first-run pages) has nothing to scroll; whether the node is on screen is checked at the click.
            runCatching { existing.onFirst().performScrollTo() }
        } else {
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
        }
    }

    private fun shell(command: String): String {
        val fd = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use { it.readBytes().decodeToString() }
    }
}
