package io.github.h_sib.declared.core.accessibility

import io.github.h_sib.declared.core.model.BitPower
import io.github.h_sib.declared.core.model.Bitmask
import io.github.h_sib.declared.core.model.DecodedPowers

/**
 * What an accessibility service declared it may do, decoded from `AccessibilityServiceInfo.getCapabilities()`.
 * Bit values are the platform's `CAPABILITY_*` constants. The lab confirmed the decode against real services
 * (lab/results, P2).
 */
enum class Ability(
    override val bit: Int,
    override val key: String,
    override val plain: String,
    override val strong: Boolean,
) : BitPower {
    WINDOW_CONTENT(0x1, "window-content", "read the text and layout shown on screen in other apps", true),
    TOUCH_EXPLORATION(0x2, "touch-exploration", "take over how touches reach apps, to turn touching the screen into speech or other actions", true),
    KEY_EVENTS(0x8, "key-events", "see and block key presses from hardware keys", true),
    MAGNIFICATION(0x10, "magnification", "control screen magnification", false),
    GESTURES(0x20, "gestures", "tap and swipe on your behalf", true),
    FINGERPRINT_GESTURES(0x40, "fingerprint-gestures", "react to swipes on the fingerprint sensor", false),
    SCREENSHOT(0x80, "screenshot", "take screenshots", true),
    ;

    companion object {
        fun decode(bits: Int): DecodedPowers = Bitmask.decode(bits, entries)
    }
}
