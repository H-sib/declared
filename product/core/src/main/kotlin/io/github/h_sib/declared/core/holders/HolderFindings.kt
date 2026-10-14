package io.github.h_sib.declared.core.holders

import io.github.h_sib.declared.core.model.Action
import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.model.CapabilityKind
import io.github.h_sib.declared.core.model.DecodedPowers
import io.github.h_sib.declared.core.model.Finding
import io.github.h_sib.declared.core.model.FixedPower
import io.github.h_sib.declared.core.model.Observation
import io.github.h_sib.declared.core.model.SettingsLink
import io.github.h_sib.declared.core.tier.Tier
import io.github.h_sib.declared.core.tier.TierVerdict

/**
 * The rule that decides how loudly a holder is mentioned. Stated in one place so it can be read, tested and
 * argued with: built-in software is only noted; strong powers from a store or setup flow are worth a look;
 * strong powers from an origin this app cannot vouch for come first. An app that could not be classified is
 * UNKNOWN, which is deliberately treated like a file install here and never worded as one.
 */
fun attentionFor(tier: Tier, powers: DecodedPowers): Attention = when {
    tier == Tier.PLATFORM || tier == Tier.SYSTEM -> Attention.NOTE
    !powers.hasStrong -> Attention.NOTE
    tier == Tier.STORE || tier == Tier.OEM_OR_CARRIER_SETUP -> Attention.LOOK
    else -> Attention.LOOK_FIRST
}

/** Builds the glass-box finding for one active holder. All wording lives here so it can be reviewed and tested. */
object HolderFindings {

    fun findingId(kind: CapabilityKind, key: String): String = "${idPrefix(kind)}:$key"

    private fun idPrefix(kind: CapabilityKind) = when (kind) {
        CapabilityKind.ACCESSIBILITY -> "accessibility"
        CapabilityKind.NOTIFICATION_LISTENER -> "notification-listener"
        CapabilityKind.DEVICE_ADMIN -> "device-admin"
        CapabilityKind.INPUT_METHOD -> "input-method"
        CapabilityKind.OVERLAY -> "overlay"
        CapabilityKind.USAGE_ACCESS -> "usage-access"
        CapabilityKind.INSTALL_UNKNOWN_APPS -> "install-unknown-apps"
        CapabilityKind.SMS_ACCESS -> "sms-access"
    }

    fun build(
        kind: CapabilityKind,
        key: String,
        pkg: String,
        label: String,
        subject: String,
        tier: TierVerdict,
        powers: DecodedPowers,
        declaresAccessibilityTool: Boolean?,
        claim: List<Observation>,
        context: List<Observation>,
    ): Finding {
        val attention = attentionFor(tier.tier, powers)
        return Finding(
            id = findingId(kind, key),
            title = title(kind, label, powers),
            subject = subject,
            section = kind.heading,
            attention = attention,
            claim = claim,
            context = context,
            meaning = meaning(kind, label, tier, powers, declaresAccessibilityTool),
            whatItCannotTell = cannotTell(kind, powers, declaresAccessibilityTool),
            actions = actions(kind, label, tier.tier, attention),
            settings = SettingsLink(kind.settings, pkg.takeIf { kind.settings.needsPackage }),
        )
    }

    private fun title(kind: CapabilityKind, label: String, powers: DecodedPowers): String = when (kind) {
        CapabilityKind.ACCESSIBILITY -> "$label can read your screen and tap for you"
        CapabilityKind.NOTIFICATION_LISTENER -> "$label can read every notification you get"
        CapabilityKind.DEVICE_ADMIN -> "$label can set rules for your whole phone"
        CapabilityKind.INPUT_METHOD ->
            if (powers.powers.contains(FixedPower.TYPING_ACTIVE)) "$label is your keyboard, so it sees everything you type" else "$label is switched on as a keyboard and could see what you type"
        CapabilityKind.OVERLAY -> "$label can draw over other apps, which can hide or fake a button"
        CapabilityKind.USAGE_ACCESS -> "$label can see which apps you open and when"
        CapabilityKind.INSTALL_UNKNOWN_APPS -> "$label can install other apps without a store"
        CapabilityKind.SMS_ACCESS -> "$label can read your text messages, including sign-in codes"
    }

    private fun intro(kind: CapabilityKind): String = when (kind) {
        CapabilityKind.ACCESSIBILITY ->
            "Accessibility services exist to help people use their phone, for example by reading the screen aloud."
        CapabilityKind.NOTIFICATION_LISTENER ->
            "Notification access lets an app read the notifications from every app on this phone. Mail, smartwatch and automation apps use it for good reasons."
        CapabilityKind.DEVICE_ADMIN ->
            "A device administrator is an app that Android lets set rules for the whole phone. Work and school apps and anti-theft tools use this for good reasons. " +
                "While an app is an active administrator, Android will not let you uninstall it until you switch that off."
        CapabilityKind.INPUT_METHOD ->
            "A keyboard app receives what you type. People install other keyboards for good reasons, such as languages or layouts."
        CapabilityKind.OVERLAY ->
            "Drawing over other apps lets an app put its own windows on top of what you are using. Screen filters, chat bubbles and assistants use it for good reasons."
        CapabilityKind.USAGE_ACCESS ->
            "Usage access lets an app see which apps you use and for how long. Screen-time and parental-control apps use it for good reasons."
        CapabilityKind.INSTALL_UNKNOWN_APPS ->
            "This setting lets an app install other apps that did not come from a store. Browsers and file managers use it for good reasons."
        CapabilityKind.SMS_ACCESS ->
            "Text-message access lets an app read the messages that arrive, including the one-time codes that sites send to check it is you. Messaging apps use it for good reasons."
    }

    private fun powersSentence(kind: CapabilityKind, label: String, powers: DecodedPowers): String {
        val list = powers.powers.joinToString("; ") { it.plain }
        return when {
            powers.powers.isEmpty() -> when (kind) {
                CapabilityKind.ACCESSIBILITY -> "$label declared no abilities this app recognizes."
                CapabilityKind.DEVICE_ADMIN -> "This app could not read which policies $label declared."
                else -> "$label has this, but this app has no description of what it adds."
            }
            kind == CapabilityKind.ACCESSIBILITY -> "To do that job, Android lets a service that is switched on do what $label declared: $list."
            kind == CapabilityKind.DEVICE_ADMIN -> "$label declared that it uses these policies, so it can $list."
            else -> "$label can $list."
        }
    }

    private fun misuse(kind: CapabilityKind): String = when (kind) {
        CapabilityKind.ACCESSIBILITY ->
            "Attackers sometimes persuade people to switch such a service on so that they can watch and control the phone, which is why this list exists."
        CapabilityKind.NOTIFICATION_LISTENER ->
            "Attackers use notification access to capture one-time codes and messages, which is why this list exists."
        CapabilityKind.DEVICE_ADMIN ->
            "Stalking and spying apps sometimes make themselves administrators so that they are hard to remove, which is why this list exists."
        CapabilityKind.INPUT_METHOD ->
            "A hostile keyboard could record what you type, which is why this list exists."
        CapabilityKind.OVERLAY ->
            "Attackers use overlays to cover real buttons or to put a fake sign-in screen on top of a real app, which is why this list exists."
        CapabilityKind.USAGE_ACCESS ->
            "Attackers use it to learn when a banking or chat app is in front, which is why this list exists."
        CapabilityKind.INSTALL_UNKNOWN_APPS ->
            "It is also how an app that is already on the phone can bring in more apps, which is why this list exists."
        CapabilityKind.SMS_ACCESS ->
            "It is also how an attacker captures the codes that protect an account, which is why this list exists."
    }

    private fun meaning(
        kind: CapabilityKind,
        label: String,
        tier: TierVerdict,
        powers: DecodedPowers,
        declaresAccessibilityTool: Boolean?,
    ): String = buildString {
        append(intro(kind)).append(' ')
        append(powersSentence(kind, label, powers)).append(' ')
        append(misuse(kind)).append(" It does not mean that $label does that. ")
        append(
            when (tier.tier) {
                Tier.PLATFORM, Tier.SYSTEM -> "${tier.reason} Built-in software such as a screen reader is expected to be here."
                else -> tier.reason
            },
        )
        if (declaresAccessibilityTool == true) {
            append(" The service says it is an accessibility tool; that is its own statement, and this app cannot check it.")
        }
    }

    private fun cannotTell(kind: CapabilityKind, powers: DecodedPowers, declaresAccessibilityTool: Boolean?): List<String> = buildList {
        add("Whether this app uses these powers at all, or what it does with anything it can reach. Android shows what was allowed, not what happens.")
        add("Whether the reason the app gives for needing this is honest.")
        add("What happens to information once it leaves the screen: this app has no view of other apps' behavior or network traffic.")
        when (kind) {
            CapabilityKind.ACCESSIBILITY -> {
                if (powers.unrecognizedBits != 0) {
                    add("The service declares ability bits this app has no name for (mask 0x${powers.unrecognizedBits.toString(16)}). They are shown, not interpreted.")
                }
                if (declaresAccessibilityTool == null) {
                    add("Whether the service calls itself an accessibility tool: Android only records that from version 14.")
                }
            }
            CapabilityKind.DEVICE_ADMIN ->
                add("Whether this administrator was set up by an employer, a school or the phone's maker, which are legitimate reasons. This app does not read that.")
            CapabilityKind.INPUT_METHOD ->
                add("Whether a keyboard that is switched on but not in use is ever switched to without your knowledge.")
            CapabilityKind.OVERLAY, CapabilityKind.USAGE_ACCESS, CapabilityKind.INSTALL_UNKNOWN_APPS, CapabilityKind.SMS_ACCESS ->
                add("The setting is read from Android's permission records. It shows what is allowed now, not whether the app has ever used it.")
            CapabilityKind.NOTIFICATION_LISTENER -> Unit
        }
    }

    private fun actions(kind: CapabilityKind, label: String, tier: Tier, attention: Attention): List<Action> = buildList {
        add(primaryAction(kind, label))
        if (attention == Attention.LOOK_FIRST) {
            add(
                Action(
                    text = "Think about where you got $label. If you did not install it on purpose, removing it is reasonable.",
                    tradeOff = "Removing an app deletes its data on this phone, so copy out anything you need first.",
                ),
            )
        }
        if (tier == Tier.UNKNOWN) {
            add(
                Action(
                    text = "Check the app's page in Android's app list; its details may show who installed it.",
                    tradeOff = "This is only a lookup; nothing on the phone changes.",
                ),
            )
        }
    }

    private fun primaryAction(kind: CapabilityKind, label: String): Action = when (kind) {
        CapabilityKind.ACCESSIBILITY -> Action(
            "Open Android's accessibility settings and look at $label. If you do not recognize it or no longer use it, switch it off.",
            "If you rely on it (a screen reader, a password manager's autofill, a one-handed-mode tool), switching it off stops that feature.",
        )
        CapabilityKind.NOTIFICATION_LISTENER -> Action(
            "Open Android's notification access settings and look at $label. If you do not recognize it or no longer use it, switch it off.",
            "Apps that show your notifications on a watch or computer, or that act on them automatically, stop doing that.",
        )
        CapabilityKind.DEVICE_ADMIN -> Action(
            "Open Android's security settings, find the list of device admin apps (its name differs by phone) and look at $label. If you do not recognize it, switch it off; after that the app can be uninstalled.",
            "Work, school or anti-theft features that rely on it stop working, and a phone managed by an employer may lose access to work data.",
        )
        CapabilityKind.INPUT_METHOD -> Action(
            "Open Android's keyboard settings and look at $label. If you do not use it, switch it off, or choose a different keyboard.",
            "You lose that keyboard's layouts, languages, dictionary and saved suggestions.",
        )
        CapabilityKind.OVERLAY -> Action(
            "Open the setting for $label and switch off drawing over other apps if you do not need it.",
            "Features that float over other apps, such as chat bubbles, screen filters and one-handed tools, stop working.",
        )
        CapabilityKind.USAGE_ACCESS -> Action(
            "Open Android's usage access settings and switch it off for $label if you do not need it.",
            "Apps that track screen time or enforce limits stop working.",
        )
        CapabilityKind.INSTALL_UNKNOWN_APPS -> Action(
            "Open the setting for $label and switch off installing other apps if you do not need it.",
            "That app can no longer install other apps, and updates for apps it installed this way may stop.",
        )
        CapabilityKind.SMS_ACCESS -> Action(
            "Open the settings page for $label, go to its permissions and remove the text-message permission if you do not need it.",
            "The app can no longer read or receive text messages, so features such as one-time-code autofill or messaging stop working.",
        )
    }
}
