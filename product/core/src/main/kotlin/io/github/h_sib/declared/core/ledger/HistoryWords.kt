package io.github.h_sib.declared.core.ledger

/**
 * The sentences the History screens show that are not built from one entry (an entry's own words are in [LedgerEvents] and
 * [Tripwires]). They live in `core`, not in the screens, so the wording tests reach them.
 */
object HistoryWords {
    const val INTRO =
        "Each time this app looks at your phone it can add an entry to this history. Entries are linked, so changing or removing an old one shows. " +
            "An entry records what this app could read at that moment and nothing more; it does not say the phone was in good order."

    const val NOT_WATCHING =
        "Entries are added only when the app is open (when you open it, and when it hears a change while it is on screen). A long stretch with no entry means the app was not looking, not that nothing happened."

    const val DAMAGED =
        "The file has been left as it is and nothing was added to it. You can start a new history; the damaged file is then kept aside under another name."

    const val TRIPWIRE_EXPLAIN =
        "A tripwire is a key that Android destroys by itself when a fingerprint is added or the screen lock is removed. It says something changed while this app may not have been looking. It cannot say who did it."

    const val LAST_TRIPWIRE = "Last tripwire event:"

    const val VERIFY_IDLE = "Checks that no entry was changed, moved or removed from the middle, and that every signature is valid."

    fun key(held: KeyHeld?): String = when (held) {
        KeyHeld.HARDWARE -> "Entries are signed with a key held in this phone's protected hardware."
        KeyHeld.SOFTWARE -> "Entries are signed with a key kept by Android in software: this phone has no protected hardware for it, or it is an emulator."
        KeyHeld.NONE -> "Entries are not signed: no signing key could be made."
        KeyHeld.UNKNOWN, null -> "It is not known where the signing key is held."
    }

    fun how(t: Taken): String = when (t) {
        Taken.OPENED -> "when the app was opened"
        Taken.ON_SCREEN -> "while the app was on screen"
    }

    fun gap(g: TimelineRow.Gap): String {
        val d = g.duration
        val days = d.toDays()
        val span = if (days >= 2) "$days days" else "${d.toHours()} hours"
        return "Nothing was recorded for $span."
    }

    fun summary(v: Verification): String = when {
        !v.intact -> "The history has been changed after it was written."
        !v.keyTied -> "The history holds together, but its signatures could not be tied to the key this phone holds now, so they prove nothing about this app."
        v.authentic -> "The history holds together and every entry carries a valid signature from this app's key."
        else -> "The history holds together, but ${v.entries - v.signedEntries} of ${v.entries} entries are not signed, so nothing proves those were not written or changed by something else."
    }
}
