package io.github.h_sib.declared.core.report

import io.github.h_sib.declared.core.accessibility.Ability
import io.github.h_sib.declared.core.admin.DevicePolicy
import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.model.Availability
import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.FixedPower
import io.github.h_sib.declared.core.model.Observation
import io.github.h_sib.declared.core.model.Provenance
import io.github.h_sib.declared.core.model.SignalId
import io.github.h_sib.declared.core.model.SignalStatus
import io.github.h_sib.declared.core.model.Signals
import io.github.h_sib.declared.core.model.SourceInfo
import io.github.h_sib.declared.core.model.Value
import io.github.h_sib.declared.core.tier.Tier

/** Plain-language rendering of the model, kept out of the UI so wording is testable and reusable (for example in exports). */
object Describe {

    fun attention(a: Attention): String = when (a) {
        Attention.LOOK_FIRST -> "Look at this first"
        Attention.LOOK -> "Worth a look"
        Attention.NOTE -> "For your information"
    }

    fun confidence(c: Confidence): String = when (c) {
        Confidence.HIGH -> "High"
        Confidence.MEDIUM -> "Medium"
        Confidence.LOW -> "Low"
    }

    fun provenance(p: Provenance): String = when (p) {
        Provenance.OBSERVED -> "Observed: Android reported it"
        Provenance.DECLARED -> "Declared: the app's own statement"
        Provenance.TOLD -> "Told: what you said"
        Provenance.INFERRED -> "Inferred: worked out from other facts"
        Provenance.INVISIBLE -> "Invisible: this app cannot see it"
    }

    fun tier(t: Tier): String = when (t) {
        Tier.PLATFORM -> "Part of Android"
        Tier.SYSTEM -> "Built in"
        Tier.STORE -> "From a store"
        Tier.OEM_OR_CARRIER_SETUP -> "From the phone's setup"
        Tier.SIDELOAD -> "From a file or download"
        Tier.ADB -> "Installed from a computer"
        Tier.UNKNOWN -> "Not recognized"
    }

    /** Names for the short permission names shown per app (the keys of `WatchedPermissions.SENSITIVE`). */
    fun grant(shortName: String): String = when (shortName) {
        "READ_SMS" -> "read text messages"
        "RECEIVE_SMS" -> "receive text messages"
        "SEND_SMS" -> "send text messages"
        "READ_CONTACTS" -> "contacts"
        "READ_CALL_LOG" -> "call history"
        "RECORD_AUDIO" -> "microphone"
        "CAMERA" -> "camera"
        "ACCESS_FINE_LOCATION" -> "precise location"
        "ACCESS_BACKGROUND_LOCATION" -> "location in the background"
        "QUERY_ALL_PACKAGES" -> "list of all apps"
        else -> shortName
    }

    private fun powerName(key: String): String =
        Ability.entries.firstOrNull { it.key == key }?.plain
            ?: DevicePolicy.entries.firstOrNull { it.key == key }?.plain
            ?: FixedPower.entries.firstOrNull { it.key == key }?.plain
            ?: "use something this app has no name for ($key)"

    fun observation(o: Observation): String = when (o.id) {
        Signals.ACCESSIBILITY_ENABLED -> flag(o, "The accessibility service is switched on.", "The accessibility service is switched off.")
        Signals.ACCESSIBILITY_CAPABILITIES -> declared(o, "It declared that it can")
        Signals.ACCESSIBILITY_DECLARED_TOOL ->
            flag(o, "The service says it is an accessibility tool.", "The service does not say it is an accessibility tool.")
        Signals.NOTIFICATION_ENABLED -> flag(o, "Notification access is switched on.", "Notification access is switched off.")
        Signals.DEVICE_ADMIN_ACTIVE -> flag(o, "The app is an active device administrator.", "The app is not an active device administrator.")
        Signals.DEVICE_ADMIN_POLICIES -> declared(o, "It declared that it uses policies that let it")
        Signals.INPUT_METHOD_ENABLED -> flag(o, "The keyboard is switched on.", "The keyboard is switched off.")
        Signals.INPUT_METHOD_DEFAULT -> flag(o, "It is the keyboard in use now.", "It is not the keyboard in use now.")
        Signals.OVERLAY -> flag(o, "The app is allowed to draw over other apps.", "The app is not allowed to draw over other apps.")
        Signals.USAGE_ACCESS -> flag(o, "The app is allowed to see which apps you use.", "The app is not allowed to see which apps you use.")
        Signals.INSTALL_UNKNOWN_APPS -> flag(o, "The app is allowed to install other apps.", "The app is not allowed to install other apps.")
        Signals.SMS_ACCESS -> {
            val names = (o.value as? Value.Items)?.items.orEmpty()
            "Android reports these text-message permissions as granted: " + names.joinToString(", ") + "."
        }
        Signals.APP_INSTALL_ORIGIN -> (o.value as? Value.Text)?.text?.substringAfter(": ", "") ?: "Install origin unknown."
        Signals.PATCH_LEVEL -> "The phone's last security update is dated ${(o.value as? Value.Text)?.text}."
        Signals.SCREEN_LOCK -> flag(o, "A screen lock is set.", "No screen lock is set.")
        Signals.USB_DEBUGGING -> flag(o, "USB debugging is switched on.", "USB debugging is switched off.")
        Signals.WIRELESS_DEBUGGING -> flag(o, "Wireless debugging is switched on.", "Wireless debugging is switched off.")
        Signals.DEVELOPER_OPTIONS -> flag(o, "Developer options are switched on.", "Developer options are switched off.")
        Signals.ADVANCED_PROTECTION -> flag(o, "Advanced Protection is on.", "Advanced Protection is off.")
        Signals.NETWORK_ACTIVE -> "The phone is connected through: " + (o.value as? Value.Items)?.items.orEmpty().joinToString(", ") + "."
        Signals.HTTP_PROXY -> "A web proxy is set: ${(o.value as? Value.Text)?.text}."
        Signals.WIFI_SECURITY -> "The Wi-Fi network's security type is ${(o.value as? Value.Text)?.text}."
        Signals.ATTESTATION_CHAIN -> "The hardware key check result is: ${(o.value as? Value.Text)?.text}."
        Signals.BOOT_STATE -> "The phone's boot state is: ${(o.value as? Value.Text)?.text}."
        Signals.BOOTLOADER -> flag(o, "The bootloader is locked.", "The bootloader is unlocked.")
        Signals.ATTESTED_PATCH -> "The hardware attests a security update from ${(o.value as? Value.Text)?.text}."
        Signals.USER_CAS -> {
            val n = (o.value as? Value.Items)?.items.orEmpty().size
            "$n certificate authority(ies) were added by a user or an administrator."
        }
        else -> o.value.toString()
    }

    private fun flag(o: Observation, yes: String, no: String) = if ((o.value as? Value.Flag)?.on == true) yes else no

    private fun declared(o: Observation, lead: String): String {
        val items = (o.value as? Value.Items)?.items.orEmpty()
        val names = items.map(::powerName)
        return if (names.isEmpty()) "It declared nothing this app recognizes." else "$lead: " + names.joinToString("; ") + "."
    }

    fun source(s: SourceInfo): String {
        val needs = if (s.permissions.isEmpty()) "needs no permission" else "needs " + s.permissions.joinToString(", ")
        return "${s.api} (Android API ${s.minApi} and later; $needs)"
    }

    fun signalName(id: SignalId): String = when (id) {
        Signals.ACCESSIBILITY_ENABLED -> "Accessibility services"
        Signals.ACCESSIBILITY_CAPABILITIES -> "Accessibility abilities"
        Signals.NOTIFICATION_ENABLED -> "Notification access"
        Signals.DEVICE_ADMIN_ACTIVE -> "Device administrators"
        Signals.INPUT_METHOD_ENABLED -> "Keyboards switched on"
        Signals.INPUT_METHOD_DEFAULT -> "Keyboard in use"
        Signals.OVERLAY -> "Drawing over other apps"
        Signals.USAGE_ACCESS -> "Usage access"
        Signals.INSTALL_UNKNOWN_APPS -> "Installing other apps"
        Signals.SMS_ACCESS -> "Text-message access"
        Signals.PACKAGE_INVENTORY -> "List of apps"
        Signals.PATCH_LEVEL -> "Security update date"
        Signals.SCREEN_LOCK -> "Screen lock"
        Signals.USB_DEBUGGING -> "USB debugging"
        Signals.WIRELESS_DEBUGGING -> "Wireless debugging"
        Signals.DEVELOPER_OPTIONS -> "Developer options"
        Signals.ADVANCED_PROTECTION -> "Advanced Protection"
        Signals.NETWORK_ACTIVE -> "Network connection"
        Signals.PRIVATE_DNS -> "Private DNS"
        Signals.HTTP_PROXY -> "Web proxy"
        Signals.WIFI_SECURITY -> "Wi-Fi security type"
        Signals.USER_CAS -> "Added certificate authorities"
        Signals.ATTESTATION_CHAIN -> "Hardware key check"
        Signals.BOOT_STATE -> "Boot state"
        Signals.BOOTLOADER -> "Bootloader"
        Signals.ATTESTED_PATCH -> "Attested security update"
        Signals.ATTESTATION_REFERENCE -> "Google reference data"
        Signals.APP_INSTALL_ORIGIN -> "Where apps came from"
        else -> id.value
    }

    fun signal(status: SignalStatus): String {
        val name = signalName(status.id)
        return when (val a = status.availability) {
            Availability.Available -> "$name: readable on this phone."
            is Availability.Degraded -> "$name: partly readable. ${a.reason}"
            is Availability.Unavailable -> "$name: not readable on this phone. ${a.reason}"
        }
    }

    /**
     * The statuses as a person wants to read them: one sentence with the count of what is fully readable, then one line
     * per problem. Nothing is dropped: an unreadable or partly readable signal always gets its own line (DESIGN.md
     * principle 2), and the readable ones are the sections and rows shown above it.
     */
    fun signalSummary(statuses: List<SignalStatus>): List<String> {
        val readable = statuses.count { it.availability == Availability.Available }
        // Signals that are unreadable for the same reason (for example everything that depends on software-only keys)
        // share one line, so a single cause is not repeated three times. Every signal is still named.
        val problems = statuses.filter { it.availability != Availability.Available }.groupBy { it.availability }.map { (availability, group) ->
            val names = group.map { signalName(it.id) }.distinct().joinToString(", ")
            when (availability) {
                Availability.Available -> ""
                is Availability.Degraded -> "$names: partly readable. ${availability.reason}"
                is Availability.Unavailable -> "$names: not readable on this phone. ${availability.reason}"
            }
        }
        return listOf("Readable on this phone: $readable of ${statuses.size} checks.") + problems
    }
}
