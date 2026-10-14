package io.github.h_sib.declared.core.model

/**
 * One thing an app is allowed to do, in plain words. Every capability kind (accessibility abilities, device-admin
 * policies, a keyboard seeing what you type, a text-message grant...) is described as a list of these.
 */
interface Power {
    val key: String

    /** Fits after "It can ...". */
    val plain: String

    /** Strong powers are the ones that make an unfamiliar origin worth a closer look (see `attentionFor`). */
    val strong: Boolean
}

/** A power the platform encodes as one bit of a mask. */
interface BitPower : Power {
    val bit: Int
}

data class DecodedPowers(
    val powers: List<Power>,
    /** Bits set in the mask that this app has no name for. Shown, never dropped. */
    val unrecognizedBits: Int = 0,
) {
    val hasStrong: Boolean get() = powers.any { it.strong }

    companion object {
        val NONE = DecodedPowers(emptyList())
    }
}

object Bitmask {
    fun decode(bits: Int, all: List<BitPower>): DecodedPowers {
        val known = all.filter { bits and it.bit != 0 }
        val knownMask = known.fold(0) { acc, p -> acc or p.bit }
        return DecodedPowers(known, bits and knownMask.inv())
    }
}

/** Powers that are not a bitmask: one fixed power per kind of grant. */
enum class FixedPower(
    override val key: String,
    override val plain: String,
    override val strong: Boolean,
) : Power {
    READ_NOTIFICATIONS("read-notifications", "read every notification that arrives on this phone, including messages and one-time codes, and dismiss them", true),
    TYPING_ACTIVE("typing-active", "receive everything you type, in every app, while it is the keyboard in use", true),
    TYPING_IF_CHOSEN("typing-if-chosen", "receive everything you type if it is chosen as the keyboard; it is switched on but is not the one in use", false),
    DRAW_OVER_APPS("draw-over-apps", "put its own windows on top of other apps", true),
    SEE_APP_USAGE("see-app-usage", "see which apps you use and for how long", true),
    INSTALL_APPS("install-apps", "install other apps that did not come from a store", true),
    READ_SMS("read-sms", "read the text messages that arrive, including one-time codes", true),
}

/** Where Android lets the user change a thing. `core` names the place; the app module turns it into an intent. */
enum class SettingsTarget(val buttonLabel: String, val needsPackage: Boolean) {
    ACCESSIBILITY("Open Android accessibility settings", false),
    NOTIFICATION_ACCESS("Open Android notification access settings", false),
    SECURITY("Open Android security settings", false),
    KEYBOARD("Open Android keyboard settings", false),
    OVERLAY("Open this app's draw-over-other-apps setting", true),
    USAGE_ACCESS("Open Android usage access settings", false),
    INSTALL_UNKNOWN("Open this app's install-other-apps setting", true),
    APP_DETAILS("Open this app's settings page", true),
    DEVELOPER("Open Android developer options", false),
    DEVICE_INFO("Open About phone (software update)", false),
    WIFI("Open Android Wi-Fi settings", false),
    NETWORK("Open Android network settings", false),
}

data class SettingsLink(val target: SettingsTarget, val pkg: String? = null) {
    init {
        require(!target.needsPackage || pkg != null) { "$target needs a package" }
    }
}

/** The kinds of capability Stage 1 inventories. The order is the order of the sections on screen. */
enum class CapabilityKind(
    val heading: String,
    val blurb: String,
    /** Short noun phrase for lists of what an app holds ("accessibility service", "keyboard"). */
    val shortName: String,
    val settings: SettingsTarget,
    /** True for kinds that are a switched-on component (so "installed, not switched on" rows exist); false for per-app grants. */
    val isComponent: Boolean,
) {
    ACCESSIBILITY("Can read and control the screen", "Accessibility services", "accessibility service", SettingsTarget.ACCESSIBILITY, true),
    NOTIFICATION_LISTENER("Can read notifications", "Apps with notification access", "notification access", SettingsTarget.NOTIFICATION_ACCESS, true),
    DEVICE_ADMIN("Device administrators", "Apps that set rules for the whole phone", "device administrator", SettingsTarget.SECURITY, true),
    INPUT_METHOD("Keyboards", "Apps that can receive what you type", "keyboard", SettingsTarget.KEYBOARD, true),
    OVERLAY("Can draw over other apps", "Apps allowed to display over other apps", "draws over other apps", SettingsTarget.OVERLAY, false),
    USAGE_ACCESS("Can see which apps you use", "Apps with usage access", "usage access", SettingsTarget.USAGE_ACCESS, false),
    INSTALL_UNKNOWN_APPS("Can install other apps", "Apps allowed to install apps from outside a store", "installs other apps", SettingsTarget.INSTALL_UNKNOWN, false),
    SMS_ACCESS("Can read text messages", "Apps granted text-message access", "text-message access", SettingsTarget.APP_DETAILS, false),
}
