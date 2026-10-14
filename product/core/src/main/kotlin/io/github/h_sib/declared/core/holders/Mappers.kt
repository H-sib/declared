package io.github.h_sib.declared.core.holders

import io.github.h_sib.declared.core.accessibility.Ability
import io.github.h_sib.declared.core.admin.DevicePolicy
import io.github.h_sib.declared.core.model.Availability
import io.github.h_sib.declared.core.model.CapabilityKind
import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.DecodedPowers
import io.github.h_sib.declared.core.model.FixedPower
import io.github.h_sib.declared.core.model.Observation
import io.github.h_sib.declared.core.model.Provenance
import io.github.h_sib.declared.core.model.SignalId
import io.github.h_sib.declared.core.model.SignalStatus
import io.github.h_sib.declared.core.model.Signals
import io.github.h_sib.declared.core.model.SourceInfo
import io.github.h_sib.declared.core.model.Subject
import io.github.h_sib.declared.core.model.Value
import io.github.h_sib.declared.core.sensor.AccessibilityReading
import io.github.h_sib.declared.core.sensor.AccessibilityServiceFact
import io.github.h_sib.declared.core.sensor.AppOpsSource
import io.github.h_sib.declared.core.sensor.ComponentId
import io.github.h_sib.declared.core.sensor.DeviceAdminReading
import io.github.h_sib.declared.core.sensor.InputMethodReading
import io.github.h_sib.declared.core.sensor.NotificationListenerReading
import io.github.h_sib.declared.core.sensor.OpMode
import io.github.h_sib.declared.core.sensor.PackageFacts
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.SpecialAccess
import io.github.h_sib.declared.core.sensor.WatchedPermissions
import java.time.Instant

/** What a mapper learned about one candidate holder, before the install record and tier are attached. */
internal data class Candidate(
    val kind: CapabilityKind,
    val key: String,
    val pkg: String,
    val subLabel: String?,
    val active: Boolean,
    val powers: DecodedPowers,
    val claim: List<Observation>,
    val context: List<Observation>,
    val declaresAccessibilityTool: Boolean?,
    val subject: String,
)

internal data class Mapped(val candidates: List<Candidate>, val statuses: List<SignalStatus>)

/** Where each reading comes from, shown to the user on every finding ("Where I got it"). */
object ObservationSources {
    val ACCESSIBILITY_ENABLED = SourceInfo(
        api = "AccessibilityManager.getEnabledAccessibilityServiceList, cross-checked with Settings.Secure enabled_accessibility_services",
        minApi = 14,
    )
    val ACCESSIBILITY_CAPABILITIES = SourceInfo(api = "AccessibilityServiceInfo.getCapabilities", minApi = 18)
    val ACCESSIBILITY_TOOL = SourceInfo(api = "AccessibilityServiceInfo.isAccessibilityTool", minApi = 34)
    val NOTIFICATION_ENABLED = SourceInfo(api = "Settings.Secure enabled_notification_listeners", minApi = 18)
    val DEVICE_ADMIN_ACTIVE = SourceInfo(api = "DevicePolicyManager.getActiveAdmins", minApi = 8)
    val DEVICE_ADMIN_POLICIES = SourceInfo(api = "DeviceAdminInfo.usesPolicy", minApi = 8)
    val INPUT_METHOD_ENABLED = SourceInfo(api = "InputMethodManager.getEnabledInputMethodList", minApi = 3)
    val INPUT_METHOD_DEFAULT = SourceInfo(api = "Settings.Secure default_input_method", minApi = 3)
    val APP_OPS = SourceInfo(api = "AppOpsManager.unsafeCheckOpNoThrow, with PackageInfo.requestedPermissionsFlags for the default mode", minApi = 29)
    val RUNTIME_GRANTS = SourceInfo(api = "PackageInfo.requestedPermissionsFlags", minApi = 16)
    val ORIGIN = SourceInfo(
        api = "PackageManager.getInstallSourceInfo, PackageManager.checkSignatures(\"android\", pkg), ApplicationInfo.flags",
        minApi = 30,
    )
    val INVENTORY = SourceInfo(api = "PackageManager.getInstalledPackages (limited to what the manifest queries make visible)", minApi = 1)
}

private fun unavailable(signals: List<SignalId>, source: SourceInfo, result: ReadResult<*>): Mapped {
    val reason = when (result) {
        is ReadResult.Refused -> "Android refused the request: ${result.reason}"
        is ReadResult.Failed -> "The read failed unexpectedly: ${result.reason}"
        is ReadResult.Ok -> error("unavailable() called with an Ok result")
    }
    return Mapped(emptyList(), signals.map { SignalStatus(it, Availability.Unavailable(reason), source) })
}

private fun availability(notes: List<String>): Availability =
    if (notes.isEmpty()) Availability.Available else Availability.Degraded(notes.joinToString(" "))

private fun componentSubject(noun: String, component: ComponentId, label: String?): String {
    val name = label?.let { "\"$it\" " }.orEmpty()
    return "$noun $name(${component.flatten()})"
}

// ---------------------------------------------------------------- accessibility

internal fun mapAccessibility(result: ReadResult<AccessibilityReading>, at: Instant): Mapped {
    val reading = (result as? ReadResult.Ok)?.value
        ?: return unavailable(listOf(Signals.ACCESSIBILITY_ENABLED), ObservationSources.ACCESSIBILITY_ENABLED, result)

    val installedIds = reading.installed.map { it.component }.toSet()
    val bySetting = reading.enabledBySetting
    val disagreements = if (bySetting == null) emptySet() else (reading.enabledByManager - bySetting) + (bySetting - reading.enabledByManager)
    val stale = reading.enabledByManager - installedIds
    // Switched on if EITHER list says so. When they disagree the answer is not "off": a service that Android's service list
    // misses for a moment (it is starting, it crashed, or a test tool attached to the screen, which suspends every service
    // while it runs; found by conformance, D-037) is still switched on in the settings, and hiding it would be the unsafe error.
    val enabledAnywhere = reading.enabledByManager + (bySetting ?: emptySet())

    val candidates = reading.installed.sortedBy { it.component.flatten() }.map { service ->
        val enabled = service.component in enabledAnywhere
        val powers = Ability.decode(service.capabilityBits)
        Candidate(
            kind = CapabilityKind.ACCESSIBILITY,
            key = service.component.flatten(),
            pkg = service.component.pkg,
            subLabel = service.label,
            active = enabled,
            powers = powers,
            claim = if (enabled) accessibilityClaim(service, powers, service.component in disagreements, service.component !in reading.enabledByManager, at) else emptyList(),
            context = accessibilityContext(service, at),
            declaresAccessibilityTool = service.declaresAccessibilityTool,
            subject = componentSubject("Accessibility service", service.component, service.label),
        )
    }

    val notes = buildList {
        if (bySetting == null) add("The cross-check setting could not be read, so the enabled list rests on one source.")
        if (disagreements.isNotEmpty()) {
            add("Android's service list and the settings value disagree about ${disagreements.size} service(s); a service may be starting or stopping.")
        }
        if (stale.isNotEmpty()) add("${stale.size} enabled entry(ies) match no installed service and are not shown as holders.")
    }
    return Mapped(
        candidates,
        listOf(
            SignalStatus(Signals.ACCESSIBILITY_ENABLED, availability(notes), ObservationSources.ACCESSIBILITY_ENABLED),
            SignalStatus(Signals.ACCESSIBILITY_CAPABILITIES, Availability.Available, ObservationSources.ACCESSIBILITY_CAPABILITIES),
        ),
    )
}

private fun accessibilityClaim(service: AccessibilityServiceFact, powers: DecodedPowers, disagree: Boolean, onlyInSettings: Boolean, at: Instant): List<Observation> {
    val subject = Subject.App(service.component.pkg)
    val enabled = Observation(
        id = Signals.ACCESSIBILITY_ENABLED,
        subject = subject,
        value = Value.Flag(true),
        provenance = Provenance.OBSERVED,
        confidence = if (disagree) Confidence.MEDIUM else Confidence.HIGH,
        source = ObservationSources.ACCESSIBILITY_ENABLED,
        limits = buildList {
            add("This says the service is switched on. It does not say whether it is doing anything right now.")
            if (disagree) {
                add("Android's two ways of listing enabled services disagreed about this one, so it is reported with lower confidence.")
                if (onlyInSettings) add("The settings say it is switched on; Android's list of running services did not include it at that moment. It is shown as switched on rather than hidden.")
            }
        },
        observedAt = at,
    )
    val capabilities = Observation(
        id = Signals.ACCESSIBILITY_CAPABILITIES,
        subject = subject,
        value = Value.Items(
            powers.powers.map { it.key } + listOfNotNull(powers.unrecognizedBits.takeIf { it != 0 }?.let { "unrecognized:0x${it.toString(16)}" }),
        ),
        provenance = Provenance.DECLARED,
        confidence = Confidence.HIGH,
        source = ObservationSources.ACCESSIBILITY_CAPABILITIES,
        limits = listOf("These abilities are what the service declared in its own metadata. They show what it is allowed to do, not what it does."),
        observedAt = at,
    )
    return listOf(enabled, capabilities)
}

private fun accessibilityContext(service: AccessibilityServiceFact, at: Instant): List<Observation> {
    val tool = service.declaresAccessibilityTool ?: return emptyList()
    return listOf(
        Observation(
            id = Signals.ACCESSIBILITY_DECLARED_TOOL,
            subject = Subject.App(service.component.pkg),
            value = Value.Flag(tool),
            provenance = Provenance.DECLARED,
            confidence = Confidence.HIGH,
            source = ObservationSources.ACCESSIBILITY_TOOL,
            limits = listOf("This is the service's own statement. Android does not verify it."),
            observedAt = at,
        ),
    )
}

// ---------------------------------------------------------------- notification listeners

internal fun mapNotificationListeners(result: ReadResult<NotificationListenerReading>, at: Instant): Mapped {
    val reading = (result as? ReadResult.Ok)?.value
        ?: return unavailable(listOf(Signals.NOTIFICATION_ENABLED), ObservationSources.NOTIFICATION_ENABLED, result)
    val stale = reading.enabled - reading.installed.map { it.component }.toSet()
    val candidates = reading.installed.sortedBy { it.component.flatten() }.map { listener ->
        val enabled = listener.component in reading.enabled
        Candidate(
            kind = CapabilityKind.NOTIFICATION_LISTENER,
            key = listener.component.flatten(),
            pkg = listener.component.pkg,
            subLabel = listener.label,
            active = enabled,
            powers = DecodedPowers(listOf(FixedPower.READ_NOTIFICATIONS)),
            claim = if (enabled) {
                listOf(
                    Observation(
                        id = Signals.NOTIFICATION_ENABLED,
                        subject = Subject.App(listener.component.pkg),
                        value = Value.Flag(true),
                        provenance = Provenance.OBSERVED,
                        confidence = Confidence.HIGH,
                        source = ObservationSources.NOTIFICATION_ENABLED,
                        limits = listOf("This says notification access is switched on for this app. It does not say whether the app is reading anything right now."),
                        observedAt = at,
                    ),
                )
            } else {
                emptyList()
            },
            context = emptyList(),
            declaresAccessibilityTool = null,
            subject = componentSubject("Notification listener", listener.component, listener.label),
        )
    }
    val notes = if (stale.isEmpty()) emptyList() else listOf("${stale.size} enabled entry(ies) match no installed listener and are not shown as holders.")
    return Mapped(candidates, listOf(SignalStatus(Signals.NOTIFICATION_ENABLED, availability(notes), ObservationSources.NOTIFICATION_ENABLED)))
}

// ---------------------------------------------------------------- device admins

internal fun mapDeviceAdmins(result: ReadResult<DeviceAdminReading>, at: Instant): Mapped {
    val reading = (result as? ReadResult.Ok)?.value
        ?: return unavailable(listOf(Signals.DEVICE_ADMIN_ACTIVE), ObservationSources.DEVICE_ADMIN_ACTIVE, result)
    val installedIds = reading.installed.map { it.component }.toSet()
    val hidden = (reading.active - installedIds).sortedBy { it.flatten() }

    fun candidate(component: ComponentId, label: String?, bits: Int?, active: Boolean): Candidate {
        val powers = bits?.let(DevicePolicy::decode) ?: DecodedPowers.NONE
        val subject = Subject.App(component.pkg)
        val claim = if (!active) {
            emptyList()
        } else {
            listOf(
                Observation(
                    id = Signals.DEVICE_ADMIN_ACTIVE,
                    subject = subject,
                    value = Value.Flag(true),
                    provenance = Provenance.OBSERVED,
                    confidence = Confidence.HIGH,
                    source = ObservationSources.DEVICE_ADMIN_ACTIVE,
                    limits = listOf("This says the app is an active administrator. It does not say whether it is using any of its policies right now."),
                    observedAt = at,
                ),
                Observation(
                    id = Signals.DEVICE_ADMIN_POLICIES,
                    subject = subject,
                    value = Value.Items(
                        powers.powers.map { it.key } + listOfNotNull(powers.unrecognizedBits.takeIf { it != 0 }?.let { "unrecognized:0x${it.toString(16)}" }),
                    ),
                    provenance = Provenance.DECLARED,
                    confidence = if (bits == null) Confidence.LOW else Confidence.HIGH,
                    source = ObservationSources.DEVICE_ADMIN_POLICIES,
                    limits = buildList {
                        add("These are the policies the administrator declared in its own metadata. They show what it is allowed to do, not what it does.")
                        if (bits == null) add("This app could not read the declaration (the package may be hidden from it), so the powers are unknown.")
                    },
                    observedAt = at,
                ),
            )
        }
        return Candidate(
            kind = CapabilityKind.DEVICE_ADMIN,
            key = component.flatten(),
            pkg = component.pkg,
            subLabel = label,
            active = active,
            powers = powers,
            claim = claim,
            context = emptyList(),
            declaresAccessibilityTool = null,
            subject = componentSubject("Device administrator", component, label),
        )
    }

    val candidates = reading.installed.sortedBy { it.component.flatten() }.map { candidate(it.component, it.label, it.policyBits, it.component in reading.active) } +
        hidden.map { candidate(it, null, null, true) }
    val notes = if (hidden.isEmpty()) {
        emptyList()
    } else {
        listOf("${hidden.size} active administrator(s) belong to packages this app cannot see, so what they can do is unknown.")
    }
    return Mapped(candidates, listOf(SignalStatus(Signals.DEVICE_ADMIN_ACTIVE, availability(notes), ObservationSources.DEVICE_ADMIN_ACTIVE)))
}

// ---------------------------------------------------------------- keyboards

internal fun mapInputMethods(result: ReadResult<InputMethodReading>, at: Instant): Mapped {
    val reading = (result as? ReadResult.Ok)?.value
        ?: return unavailable(listOf(Signals.INPUT_METHOD_ENABLED, Signals.INPUT_METHOD_DEFAULT), ObservationSources.INPUT_METHOD_ENABLED, result)
    val installedIds = reading.installed.map { it.component }.toSet()
    val default = reading.default
    val candidates = reading.installed.sortedBy { it.component.flatten() }.map { ime ->
        val enabled = ime.component in reading.enabled
        val isDefault = ime.component == default
        val subject = Subject.App(ime.component.pkg)
        Candidate(
            kind = CapabilityKind.INPUT_METHOD,
            key = ime.component.flatten(),
            pkg = ime.component.pkg,
            subLabel = ime.label,
            active = enabled,
            powers = DecodedPowers(listOf(if (isDefault) FixedPower.TYPING_ACTIVE else FixedPower.TYPING_IF_CHOSEN)),
            claim = if (!enabled) {
                emptyList()
            } else {
                listOfNotNull(
                    Observation(
                        id = Signals.INPUT_METHOD_ENABLED,
                        subject = subject,
                        value = Value.Flag(true),
                        provenance = Provenance.OBSERVED,
                        confidence = Confidence.HIGH,
                        source = ObservationSources.INPUT_METHOD_ENABLED,
                        limits = listOf("This says the keyboard is switched on. A keyboard that is not the one in use receives no typing until it is chosen."),
                        observedAt = at,
                    ),
                    if (isDefault) {
                        Observation(
                            id = Signals.INPUT_METHOD_DEFAULT,
                            subject = subject,
                            value = Value.Flag(true),
                            provenance = Provenance.OBSERVED,
                            confidence = Confidence.HIGH,
                            source = ObservationSources.INPUT_METHOD_DEFAULT,
                            limits = listOf("This says it is the keyboard Android uses now. Some apps, such as password fields in certain apps, may use their own input."),
                            observedAt = at,
                        )
                    } else {
                        null
                    },
                )
            },
            context = emptyList(),
            declaresAccessibilityTool = null,
            subject = componentSubject("Keyboard", ime.component, ime.label),
        )
    }
    val notes = buildList {
        if (default == null) add("This app could not tell which keyboard is in use.")
        if (default != null && default !in installedIds) add("The keyboard Android reports as in use is not among the keyboards this app can see.")
        if (default != null && default !in reading.enabled) add("The keyboard Android reports as in use is not in the enabled list, which is unexpected.")
        val stale = reading.enabled - installedIds
        if (stale.isNotEmpty()) add("${stale.size} enabled keyboard(s) are not among the keyboards this app can see.")
    }
    val availability = availability(notes)
    return Mapped(
        candidates,
        listOf(
            SignalStatus(Signals.INPUT_METHOD_ENABLED, availability, ObservationSources.INPUT_METHOD_ENABLED),
            SignalStatus(Signals.INPUT_METHOD_DEFAULT, availability, ObservationSources.INPUT_METHOD_DEFAULT),
        ),
    )
}

// ---------------------------------------------------------------- special access (app-ops) and text-message grants

private val SPECIAL_KIND = mapOf(
    SpecialAccess.OVERLAY to Triple(CapabilityKind.OVERLAY, Signals.OVERLAY, FixedPower.DRAW_OVER_APPS),
    SpecialAccess.USAGE_ACCESS to Triple(CapabilityKind.USAGE_ACCESS, Signals.USAGE_ACCESS, FixedPower.SEE_APP_USAGE),
    SpecialAccess.INSTALL_UNKNOWN_APPS to Triple(CapabilityKind.INSTALL_UNKNOWN_APPS, Signals.INSTALL_UNKNOWN_APPS, FixedPower.INSTALL_APPS),
)

/** Built-in apps are left out of the grant lists on purpose: they would bury the rest (DESIGN.md section 17). */
internal fun isBuiltIn(f: PackageFacts): Boolean = f.platformSigned == true || f.system || f.updatedSystem

internal fun mapSpecialAccess(apps: List<PackageFacts>, appOps: AppOpsSource, at: Instant): Mapped {
    val candidates = ArrayList<Candidate>()
    var answered = 0
    var refused = 0
    var failed = 0
    var firstRefusal: String? = null
    var firstFailure: String? = null
    for (app in apps) {
        if (isBuiltIn(app) || app.requestedWatched.none { it in WatchedPermissions.SPECIAL }) continue
        when (val r = appOps.modes(app.pkg, app.uid)) {
            is ReadResult.Ok -> {
                answered += 1
                for ((access, modeInfo) in SPECIAL_KIND) {
                    if (access.permission !in app.requestedWatched) continue
                    val mode = r.value[access] ?: OpMode.UNKNOWN
                    val permissionGranted = access.permission in app.grantedWatched
                    val on = when (mode) {
                        OpMode.ALLOWED, OpMode.FOREGROUND -> true
                        OpMode.DEFAULT -> permissionGranted
                        else -> false
                    }
                    if (!on) continue
                    val (kind, signal, power) = modeInfo
                    val viaDefault = mode == OpMode.DEFAULT || mode == OpMode.FOREGROUND
                    candidates += Candidate(
                        kind = kind,
                        key = app.pkg,
                        pkg = app.pkg,
                        subLabel = null,
                        active = true,
                        powers = DecodedPowers(listOf(power)),
                        claim = listOf(
                            Observation(
                                id = signal,
                                subject = Subject.App(app.pkg),
                                value = Value.Flag(true),
                                provenance = Provenance.OBSERVED,
                                confidence = if (viaDefault) Confidence.MEDIUM else Confidence.HIGH,
                                source = ObservationSources.APP_OPS,
                                limits = buildList {
                                    add("This is the setting's state at the moment of reading. It does not say whether the app has used it.")
                                    if (viaDefault) add("Android left this setting at its default, so the state was worked out from whether the permission is granted. That is a weaker reading than an explicit allow.")
                                },
                                observedAt = at,
                            ),
                        ),
                        context = emptyList(),
                        declaresAccessibilityTool = null,
                        subject = "App ${app.label?.let { "\"$it\" " }.orEmpty()}(${app.pkg})",
                    )
                }
            }
            is ReadResult.Refused -> { refused += 1; if (firstRefusal == null) firstRefusal = r.reason }
            is ReadResult.Failed -> { failed += 1; if (firstFailure == null) firstFailure = r.reason }
        }
    }
    val availability: Availability = when {
        answered == 0 && refused > 0 ->
            Availability.Unavailable("Android refused to tell this app about other apps' special access: $firstRefusal. This is expected on Android 10.")
        answered == 0 && failed > 0 -> Availability.Unavailable("The reads failed unexpectedly: $firstFailure")
        refused > 0 || failed > 0 -> Availability.Degraded("${refused + failed} app(s) could not be read; the others were.")
        else -> Availability.Available
    }
    return Mapped(
        candidates,
        SPECIAL_KIND.values.map { (_, signal, _) -> SignalStatus(signal, availability, ObservationSources.APP_OPS) },
    )
}

internal fun mapSmsGrants(apps: List<PackageFacts>, at: Instant): Mapped {
    val candidates = apps.filter { !isBuiltIn(it) }.mapNotNull { app ->
        val granted = listOf(WatchedPermissions.READ_SMS, WatchedPermissions.RECEIVE_SMS).filter { it in app.grantedWatched }
        if (granted.isEmpty()) {
            null
        } else {
            Candidate(
                kind = CapabilityKind.SMS_ACCESS,
                key = app.pkg,
                pkg = app.pkg,
                subLabel = null,
                active = true,
                powers = DecodedPowers(listOf(FixedPower.READ_SMS)),
                claim = listOf(
                    Observation(
                        id = Signals.SMS_ACCESS,
                        subject = Subject.App(app.pkg),
                        value = Value.Items(granted.map(WatchedPermissions::short)),
                        provenance = Provenance.OBSERVED,
                        confidence = Confidence.HIGH,
                        source = ObservationSources.RUNTIME_GRANTS,
                        limits = listOf("This is the grant at the moment of reading. It does not say whether the app has read any message."),
                        observedAt = at,
                    ),
                ),
                context = emptyList(),
                declaresAccessibilityTool = null,
                subject = "App ${app.label?.let { "\"$it\" " }.orEmpty()}(${app.pkg})",
            )
        }
    }
    return Mapped(candidates, listOf(SignalStatus(Signals.SMS_ACCESS, Availability.Available, ObservationSources.RUNTIME_GRANTS)))
}
