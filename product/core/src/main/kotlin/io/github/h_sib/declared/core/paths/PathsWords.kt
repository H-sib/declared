package io.github.h_sib.declared.core.paths

import io.github.h_sib.declared.core.model.Profile
import io.github.h_sib.declared.core.model.Told

/**
 * The fixed sentences of the paths screens. They live in `core`, next to the engine, so the same banned-word test that
 * reads every engine sentence reads these too (no score, no "safe", no accusing words, no dashes).
 */
object PathsWords {
    const val INTRO =
        "These are ways someone could get at what matters to you, given what this app could read on this phone and what you told it. " +
            "A path is not a sign that anything has happened."

    const val NONE_FOUND =
        "No attack path was found from the facts this app could read. That is not a promise that nothing can happen: " +
            "see what this app cannot see."

    const val NOT_RULED_OUT_INTRO =
        "Not ruled out: these depend on something this app could not read, or on a question you have not answered, " +
            "so it cannot say whether they exist."

    const val PLAN_TITLE = "Smallest set of changes"

    const val PLAN_EXACT = "This is the smallest set that stops every path above that any change known here can stop."

    val PLAN_APPROXIMATE =
        "The search for the smallest set reached its limit of ${Fixes.DEFAULT_BUDGET} steps. This set stops every path it can, " +
            "but a smaller one might exist."

    const val PREVIEW_HINT = "Tick a change to see which paths it would stop. Nothing is changed on your phone."

    const val NOTHING_CAN_STOP_THIS = "Nothing this app knows how to change would stop this path on its own."

    const val ABOUT_INTRO =
        "Questions only you can answer. The answers are kept on this phone, encrypted, and are never sent anywhere. " +
            "\"Not sure\" is a fine answer: the paths that depend on it stay on the not ruled out list instead of being dropped."

    const val ASK_SMS_CODES = "Do your accounts send you sign-in codes by text message?"

    const val ASK_PUBLIC_WIFI = "Do you often use public Wi-Fi?"

    const val ASK_PROFILE = "How much weight should the paths give each weakness?"

    fun told(t: Told): String = when (t) {
        Told.YES -> "Yes"
        Told.NO -> "No"
        Told.UNSURE -> "Not sure"
    }

    fun profile(p: Profile): String = when (p) {
        Profile.GENERAL -> "Most people"
        Profile.HIGH_RISK -> "Someone who may be targeted, such as a journalist or an activist"
    }

    fun severity(s: Severity): String = when (s) {
        Severity.LOW -> "For your information"
        Severity.MEDIUM -> "Worth a look"
        Severity.HIGH -> "Look at this first"
        Severity.CRITICAL -> "Look at this before anything else"
    }

    fun provenance(p: io.github.h_sib.declared.core.model.Provenance): String = when (p) {
        io.github.h_sib.declared.core.model.Provenance.OBSERVED -> "seen on this phone"
        io.github.h_sib.declared.core.model.Provenance.DECLARED -> "the app's own statement"
        io.github.h_sib.declared.core.model.Provenance.TOLD -> "what you told this app"
        io.github.h_sib.declared.core.model.Provenance.INFERRED -> "worked out from other facts"
        io.github.h_sib.declared.core.model.Provenance.INVISIBLE -> "not visible to this app"
    }

    /** "Changing these 3 things stops 4 of the 6 paths." Singular and plural, because a sentence that reads wrong is noticed. */
    fun planSummary(levers: Int, cut: Int, total: Int): String {
        val things = if (levers == 1) "this 1 change" else "these $levers changes"
        val outcome = when {
            total == 1 -> "stops the 1 path this app can see"
            cut == total -> "stops all $total paths this app can see"
            else -> "stops $cut of the $total paths this app can see"
        }
        return "Making $things $outcome."
    }

    /** What is left once the changes the person ticked are applied. */
    fun remaining(n: Int): String = when (n) {
        0 -> "With the changes you ticked, none of these paths would remain."
        1 -> "With the changes you ticked, 1 path would remain."
        else -> "With the changes you ticked, $n paths would remain."
    }

    fun pathCount(n: Int): String = if (n == 1) "1 path this app can see." else "$n paths this app can see."
}
