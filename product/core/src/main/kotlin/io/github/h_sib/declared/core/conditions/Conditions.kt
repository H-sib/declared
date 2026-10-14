package io.github.h_sib.declared.core.conditions

import io.github.h_sib.declared.core.model.Action
import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.model.Availability
import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.DeviceFact
import io.github.h_sib.declared.core.model.FactKind
import io.github.h_sib.declared.core.model.Finding
import io.github.h_sib.declared.core.model.Observation
import io.github.h_sib.declared.core.model.Provenance
import io.github.h_sib.declared.core.model.SettingsLink
import io.github.h_sib.declared.core.model.SettingsTarget
import io.github.h_sib.declared.core.model.SignalId
import io.github.h_sib.declared.core.model.SignalStatus
import io.github.h_sib.declared.core.model.Signals
import io.github.h_sib.declared.core.model.SourceInfo
import io.github.h_sib.declared.core.model.Subject
import io.github.h_sib.declared.core.model.Value
import io.github.h_sib.declared.core.sensor.ActiveNetwork
import io.github.h_sib.declared.core.sensor.AdvancedProtectionState
import io.github.h_sib.declared.core.sensor.DeviceFactsReading
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.Transport
import io.github.h_sib.declared.core.sensor.UserCaReading
import io.github.h_sib.declared.core.sensor.WifiReading
import io.github.h_sib.declared.core.sensor.WifiSecurity
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** What the device and network mappers produce: rows for the panels, findings for the conditions that need one, and statuses. */
internal data class ConditionOutput(
    val facts: List<DeviceFact>,
    val findings: List<Finding>,
    val statuses: List<SignalStatus>,
)

/** Where each reading comes from, shown on every finding ("Where I got it"). */
object ConditionSources {
    val PATCH = SourceInfo(api = "Build.VERSION.SECURITY_PATCH", minApi = 23)
    val LOCK = SourceInfo(api = "KeyguardManager.isDeviceSecure", minApi = 23)
    val USB = SourceInfo(api = "Settings.Global adb_enabled", minApi = 17)
    val WIRELESS = SourceInfo(api = "Settings.Global adb_wifi_enabled", minApi = 30)
    val DEVELOPER = SourceInfo(api = "Settings.Global development_settings_enabled", minApi = 17)
    val ADVANCED = SourceInfo(
        api = "AdvancedProtectionManager.isAdvancedProtectionEnabled",
        minApi = 36,
        permissions = listOf("QUERY_ADVANCED_PROTECTION_MODE"),
    )
    val NETWORK = SourceInfo(
        api = "ConnectivityManager.getNetworkCapabilities, getLinkProperties and getDefaultProxy",
        minApi = 23,
        permissions = listOf("ACCESS_NETWORK_STATE"),
    )
    val WIFI = SourceInfo(api = "WifiInfo.getCurrentSecurityType", minApi = 31, permissions = listOf("ACCESS_WIFI_STATE"))
    val USER_CAS = SourceInfo(api = "KeyStore AndroidCAStore with X509TrustManagerExtensions.isUserAddedCertificate", minApi = 24)
}

/** How old a security update may be before it is worth a mention. Plain numbers so they can be read, argued with and tested. */
object PatchAge {
    const val LOOK_DAYS = 180L
    const val LOOK_FIRST_DAYS = 365L
}

private fun unavailableReason(r: ReadResult<*>): String = when (r) {
    is ReadResult.Refused -> "Android refused the request: ${r.reason}"
    is ReadResult.Failed -> "The read failed unexpectedly: ${r.reason}"
    is ReadResult.Ok -> error("not an error result")
}

private fun obs(
    id: SignalId,
    subject: Subject,
    value: Value,
    confidence: Confidence,
    source: SourceInfo,
    limits: List<String>,
    at: Instant,
    provenance: Provenance = Provenance.OBSERVED,
) = Observation(id, subject, value, provenance, confidence, source, limits, at)

private fun finding(
    id: String,
    section: String,
    title: String,
    attention: Attention,
    claim: Observation,
    meaning: String,
    cannotTell: List<String>,
    actions: List<Action>,
    settings: SettingsTarget,
) = Finding(
    id = id, title = title, subject = section, section = section, attention = attention,
    claim = listOf(claim), context = emptyList(), meaning = meaning, whatItCannotTell = cannotTell,
    actions = actions, settings = SettingsLink(settings),
)

private const val DEVICE = "This phone's settings"
private const val NETWORK = "The network you are on"

private val NOT_SEEN_BY_THIS_APP = "What happens to information once it leaves the phone: this app has no view of other apps' behavior or network traffic."

// ---------------------------------------------------------------- device facts

internal fun mapDevice(reading: DeviceFactsReading, today: LocalDate, at: Instant): ConditionOutput {
    val facts = ArrayList<DeviceFact>()
    val findings = ArrayList<Finding>()
    val statuses = ArrayList<SignalStatus>()
    val device = Subject.Device

    // ---- security patch
    when (val r = reading.securityPatch) {
        is ReadResult.Ok -> {
            val text = r.value?.trim().orEmpty()
            val date = try { LocalDate.parse(text) } catch (e: DateTimeParseException) { null }
            if (date == null) {
                val reason = if (text.isEmpty()) "The phone reports no security update date." else "The date \"$text\" is not in a form this app can read."
                facts += DeviceFact(FactKind.PATCH_LEVEL, "Last security update: unknown. $reason", Availability.Degraded(reason), null)
                statuses += SignalStatus(Signals.PATCH_LEVEL, Availability.Degraded(reason), ConditionSources.PATCH)
            } else {
                val days = ChronoUnit.DAYS.between(date, today)
                val future = days < 0
                val months = (days / 30).coerceAtLeast(0)
                val limits = listOf("This is the date the phone's system software reports. Phones with altered system software can report any date.")
                val claim = obs(Signals.PATCH_LEVEL, device, Value.Text(text), Confidence.HIGH, ConditionSources.PATCH, limits, at)
                val age = when {
                    future -> "dated in the future, which is unexpected"
                    days < 60 -> "about ${days} days ago"
                    else -> "about $months months ago"
                }
                facts += DeviceFact(FactKind.PATCH_LEVEL, "Last security update: $text ($age).", Availability.Available, claim)
                statuses += SignalStatus(Signals.PATCH_LEVEL, Availability.Available, ConditionSources.PATCH)
                if (!future && days >= PatchAge.LOOK_DAYS) {
                    val old = days >= PatchAge.LOOK_FIRST_DAYS
                    findings += finding(
                        id = "condition:patch-level",
                        section = DEVICE,
                        title = "The last security update was about $months months ago, so known flaws are still open",
                        attention = if (old) Attention.LOOK_FIRST else Attention.LOOK,
                        claim = claim,
                        meaning = "Security updates fix flaws that are already public. This phone reports a security update dated $text, about $months months ago, " +
                            "so the flaws fixed since then are still open here. Phones normally get an update every month or two until the maker stops supporting them." +
                            (if (old) " A phone this far behind has most likely reached the end of its support." else ""),
                        cannotTell = listOf(
                            "Whether this phone is exposed to any particular flaw, or whether anyone is using one.",
                            "Whether the maker has shipped newer fixes that this date does not show: some makers update parts of the system separately.",
                            "Whether the date is honest: a phone with altered system software can report any date.",
                            NOT_SEEN_BY_THIS_APP,
                        ),
                        actions = listOf(
                            Action(
                                "Open About phone and look for a software update. Install it if one is offered.",
                                "The phone restarts and can be slow for a while, so keep it charged and on Wi-Fi.",
                            ),
                            Action(
                                "If no update is offered, the phone has probably reached the end of its support. Think about which accounts and apps you keep on it.",
                                "Moving to a supported phone costs money and time; the phone itself keeps working.",
                            ),
                        ),
                        settings = SettingsTarget.DEVICE_INFO,
                    )
                }
            }
        }
        else -> {
            val reason = unavailableReason(r)
            facts += DeviceFact(FactKind.PATCH_LEVEL, "Last security update: not readable. $reason", Availability.Unavailable(reason), null)
            statuses += SignalStatus(Signals.PATCH_LEVEL, Availability.Unavailable(reason), ConditionSources.PATCH)
        }
    }

    // ---- screen lock
    when (val r = reading.screenLock) {
        is ReadResult.Ok -> {
            val claim = obs(
                Signals.SCREEN_LOCK, device, Value.Flag(r.value), Confidence.HIGH, ConditionSources.LOCK,
                listOf("This says whether a PIN, pattern or password is set. It does not say how strong it is."), at,
            )
            facts += DeviceFact(FactKind.SCREEN_LOCK, if (r.value) "Screen lock: set." else "Screen lock: none.", Availability.Available, claim)
            statuses += SignalStatus(Signals.SCREEN_LOCK, Availability.Available, ConditionSources.LOCK)
            if (!r.value) {
                findings += finding(
                    id = "condition:screen-lock",
                    section = DEVICE,
                    title = "No screen lock: anyone who picks up this phone can open it",
                    attention = Attention.LOOK_FIRST,
                    claim = claim,
                    meaning = "Without a screen lock, anyone who picks up the phone can open it and everything on it, including accounts that are already signed in. " +
                        "Some of this app's own checks, such as noticing tampering, also need a lock to work.",
                    cannotTell = listOf(
                        "Whether the phone is ever out of your sight, which is what makes this matter.",
                        "Whether other safeguards exist, such as remote erase or tracking: this app cannot read them.",
                        "Whether the setting is deliberate: some shared or single-purpose phones have no lock on purpose.",
                    ),
                    actions = listOf(
                        Action(
                            "Open security settings and set a PIN, pattern or password.",
                            "You will need it every time you unlock the phone, and if you forget it you can lose the data on the phone.",
                        ),
                    ),
                    settings = SettingsTarget.SECURITY,
                )
            }
        }
        else -> {
            val reason = unavailableReason(r)
            facts += DeviceFact(FactKind.SCREEN_LOCK, "Screen lock: not readable. $reason", Availability.Unavailable(reason), null)
            statuses += SignalStatus(Signals.SCREEN_LOCK, Availability.Unavailable(reason), ConditionSources.LOCK)
        }
    }

    // ---- debugging switches and developer options
    fun flag(
        kind: FactKind,
        signal: SignalId,
        source: SourceInfo,
        result: ReadResult<Int?>,
        on: String,
        off: String,
        build: (Observation) -> Finding?,
    ) {
        when (result) {
            is ReadResult.Ok -> {
                when (val v = result.value) {
                    null -> {
                        val note = "This setting does not exist on this phone."
                        facts += DeviceFact(kind, "${kind.label}: not set. $note", Availability.Available, null)
                        statuses += SignalStatus(signal, Availability.Available, source)
                    }
                    0, 1 -> {
                        val claim = obs(signal, device, Value.Flag(v == 1), Confidence.HIGH, source, listOf("This is the setting's value now. It does not say who set it or when."), at)
                        facts += DeviceFact(kind, "${kind.label}: ${if (v == 1) on else off}.", Availability.Available, claim)
                        statuses += SignalStatus(signal, Availability.Available, source)
                        if (v == 1) build(claim)?.let { findings += it }
                    }
                    else -> {
                        val reason = "The setting has the unexpected value $v."
                        facts += DeviceFact(kind, "${kind.label}: unclear. $reason", Availability.Degraded(reason), null)
                        statuses += SignalStatus(signal, Availability.Degraded(reason), source)
                    }
                }
            }
            else -> {
                val reason = unavailableReason(result)
                facts += DeviceFact(kind, "${kind.label}: not readable. $reason", Availability.Unavailable(reason), null)
                statuses += SignalStatus(signal, Availability.Unavailable(reason), source)
            }
        }
    }

    flag(FactKind.USB_DEBUGGING, Signals.USB_DEBUGGING, ConditionSources.USB, reading.usbDebugging, "on", "off") { claim ->
        finding(
            id = "condition:usb-debugging", section = DEVICE, title = "USB debugging is on: a computer you plug into can control this phone", attention = Attention.LOOK, claim = claim,
            meaning = "USB debugging lets a computer read data from this phone and install apps on it, once the phone has approved that computer. Developers need it. " +
                "If someone gets hold of an unlocked phone, it makes it easier to copy data out or add apps.",
            cannotTell = listOf(
                "Which computers the phone has already approved: Android keeps that list out of reach of apps.",
                "Whether a computer is connected right now.",
                "Whether you switched it on yourself: some repair shops and phone makers do.",
            ),
            actions = listOf(
                Action(
                    "Open developer options and switch USB debugging off if you do not use it.",
                    "Developer tools and some repair or backup programs stop working over a cable until you switch it on again.",
                ),
            ),
            settings = SettingsTarget.DEVELOPER,
        )
    }
    flag(FactKind.WIRELESS_DEBUGGING, Signals.WIRELESS_DEBUGGING, ConditionSources.WIRELESS, reading.wirelessDebugging, "on", "off") { claim ->
        finding(
            id = "condition:wireless-debugging", section = DEVICE, title = "Wireless debugging is on: a computer paired over Wi-Fi can control this phone", attention = Attention.LOOK_FIRST, claim = claim,
            meaning = "Wireless debugging lets a computer on the same network connect to this phone without a cable, after the two have been paired. " +
                "It stays reachable for as long as it is on and the phone is on a network.",
            cannotTell = listOf(
                "Which computers have been paired with the phone: Android keeps that list out of reach of apps.",
                "Whether a computer is connected right now.",
                "Whether you switched it on yourself: it is a developer feature.",
            ),
            actions = listOf(
                Action(
                    "Open developer options and switch wireless debugging off if you do not use it.",
                    "Developer tools that work over Wi-Fi stop working until you switch it on again and pair again.",
                ),
            ),
            settings = SettingsTarget.DEVELOPER,
        )
    }
    flag(FactKind.DEVELOPER_OPTIONS, Signals.DEVELOPER_OPTIONS, ConditionSources.DEVELOPER, reading.developerOptions, "on", "off") { claim ->
        finding(
            id = "condition:developer-options", section = DEVICE, title = "Developer options are on, which opens settings meant for app builders", attention = Attention.NOTE, claim = claim,
            meaning = "Developer options is a hidden menu of advanced settings, including USB debugging. Having it on is common among people who have tried it once; it is listed so that you know.",
            cannotTell = listOf(
                "Which advanced settings inside the menu have been changed: this app reads only the few that matter to it.",
                "Whether you switched it on yourself.",
                NOT_SEEN_BY_THIS_APP,
            ),
            actions = listOf(
                Action(
                    "Open developer options and switch the menu off if you do not use it.",
                    "Some developer settings go back to their defaults when you switch the menu off.",
                ),
            ),
            settings = SettingsTarget.DEVELOPER,
        )
    }

    // ---- Advanced Protection (a fact only: whether it matters depends on who the user is, which is Stage 2)
    when (val ap = reading.advancedProtection) {
        is AdvancedProtectionState.Known -> {
            val claim = obs(
                Signals.ADVANCED_PROTECTION, device, Value.Flag(ap.enabled), Confidence.HIGH, ConditionSources.ADVANCED,
                listOf("This says whether Android's Advanced Protection mode is on. It does not say which of its protections apply."), at,
            )
            facts += DeviceFact(FactKind.ADVANCED_PROTECTION, "Advanced Protection: ${if (ap.enabled) "on" else "off"}.", Availability.Available, claim)
            statuses += SignalStatus(Signals.ADVANCED_PROTECTION, Availability.Available, ConditionSources.ADVANCED)
        }
        AdvancedProtectionState.Unsupported -> {
            val reason = "Android 15 and older have no Advanced Protection mode."
            facts += DeviceFact(FactKind.ADVANCED_PROTECTION, "Advanced Protection: not available. $reason", Availability.Unavailable(reason), null)
            statuses += SignalStatus(Signals.ADVANCED_PROTECTION, Availability.Unavailable(reason), ConditionSources.ADVANCED)
        }
        AdvancedProtectionState.NotPermitted -> {
            val reason = "Android did not allow this app to ask. The permission to read it was not granted."
            facts += DeviceFact(FactKind.ADVANCED_PROTECTION, "Advanced Protection: not readable. $reason", Availability.Unavailable(reason), null)
            statuses += SignalStatus(Signals.ADVANCED_PROTECTION, Availability.Unavailable(reason), ConditionSources.ADVANCED)
        }
        is AdvancedProtectionState.Failed -> {
            val reason = "The read failed unexpectedly: ${ap.reason}"
            facts += DeviceFact(FactKind.ADVANCED_PROTECTION, "Advanced Protection: not readable. $reason", Availability.Unavailable(reason), null)
            statuses += SignalStatus(Signals.ADVANCED_PROTECTION, Availability.Unavailable(reason), ConditionSources.ADVANCED)
        }
    }
    return ConditionOutput(facts, findings, statuses)
}

// ---------------------------------------------------------------- network

private fun transportName(t: Transport) = when (t) {
    Transport.WIFI -> "Wi-Fi"
    Transport.CELLULAR -> "mobile data"
    Transport.VPN -> "VPN"
    Transport.ETHERNET -> "cable"
    Transport.OTHER -> "another kind of network"
}

private fun securityText(s: WifiSecurity) = when (s) {
    WifiSecurity.OPEN -> "open (no password; Wi-Fi itself encrypts nothing)"
    WifiSecurity.WEP -> "WEP (an old method that is easily broken)"
    WifiSecurity.WPA_PSK -> "WPA or WPA2 with a password"
    WifiSecurity.WPA3_PSK -> "WPA3 with a password"
    WifiSecurity.ENTERPRISE -> "enterprise (each person signs in)"
    WifiSecurity.OWE -> "Enhanced Open (encrypted, but nobody checks who runs the network)"
    WifiSecurity.WAPI -> "WAPI"
    WifiSecurity.OTHER -> "another kind this app has no name for"
    WifiSecurity.UNKNOWN -> "unknown"
}

internal const val INFERRED_NETWORK =
    "Android hides the connection in use from an app that has no internet permission, as this one has none by design. This app picked the most likely one from the networks it can still see."

internal fun mapNetwork(
    network: ReadResult<ActiveNetwork>,
    wifi: ReadResult<WifiReading>,
    userCas: ReadResult<UserCaReading>,
    at: Instant,
): ConditionOutput {
    val facts = ArrayList<DeviceFact>()
    val findings = ArrayList<Finding>()
    val statuses = ArrayList<SignalStatus>()
    val subject = Subject.Network

    // ---- the active connection, VPN, Private DNS and proxy come from one reading
    val netSignals = listOf(Signals.NETWORK_ACTIVE, Signals.PRIVATE_DNS, Signals.HTTP_PROXY)
    when (network) {
        is ReadResult.Ok -> {
            val n = network.value
            statuses += netSignals.map { SignalStatus(it, Availability.Available, ConditionSources.NETWORK) }
            if (!n.present) {
                facts += DeviceFact(FactKind.CONNECTION, "Connection: not connected to any network.", Availability.Available, null)
                facts += DeviceFact(FactKind.VPN, "VPN: not applicable without a connection.", Availability.Available, null)
                facts += DeviceFact(FactKind.PRIVATE_DNS, "Private DNS: not applicable without a connection.", Availability.Available, null)
                facts += DeviceFact(FactKind.HTTP_PROXY, "Web proxy: not applicable without a connection.", Availability.Available, null)
            } else {
                val real = n.transports.filter { it != Transport.VPN }.map(::transportName).ifEmpty { listOf("a network") }.joinToString(" and ")
                val reach = when {
                    n.captivePortal -> "Android thinks you must sign in to this network first."
                    n.validated -> "Android confirmed that it reaches the internet."
                    n.internet -> "Android could not confirm that it reaches the internet."
                    else -> "It does not offer internet access."
                }
                val confidence = if (n.inferred) Confidence.MEDIUM else Confidence.HIGH
                facts += DeviceFact(
                    FactKind.CONNECTION, "Connection: $real. $reach", Availability.Available,
                    obs(Signals.NETWORK_ACTIVE, subject, Value.Items(n.transports.map { it.name.lowercase() }.sorted()), confidence, ConditionSources.NETWORK,
                        listOf("This describes the connection the phone is using now. It changes when you move or switch networks.") + listOfNotNull(INFERRED_NETWORK.takeIf { n.inferred }), at),
                )
                val vpn = Transport.VPN in n.transports
                facts += DeviceFact(
                    FactKind.VPN, if (vpn) "VPN: active. Your traffic goes through the VPN's operator." else "VPN: none on this connection.", Availability.Available, null,
                )
                facts += DeviceFact(
                    FactKind.PRIVATE_DNS,
                    when (n.privateDnsActive) {
                        true -> "Private DNS: in use" + (n.privateDnsServerName?.let { " with $it" } ?: "") + "."
                        false -> "Private DNS: not in use on this connection."
                        null -> "Private DNS: this app could not tell."
                    },
                    if (n.privateDnsActive == null) Availability.Degraded("Android did not say.") else Availability.Available,
                    null,
                )
                val proxy = n.httpProxy
                if (proxy == null) {
                    facts += DeviceFact(FactKind.HTTP_PROXY, "Web proxy: none set.", Availability.Available, null)
                } else {
                    val claim = obs(
                        Signals.HTTP_PROXY, subject, Value.Text(proxy), confidence, ConditionSources.NETWORK,
                        listOf("This is the proxy Android has configured. Only some apps follow it.") + listOfNotNull(INFERRED_NETWORK.takeIf { n.inferred }), at,
                    )
                    facts += DeviceFact(FactKind.HTTP_PROXY, "Web proxy: set to $proxy.", Availability.Available, claim)
                    findings += finding(
                        id = "condition:http-proxy", section = NETWORK, title = "A web proxy is set, so your web traffic goes through another server", attention = Attention.LOOK, claim = claim,
                        meaning = "A proxy is a server that web requests are sent through. Company networks and privacy or testing tools use them for good reasons. " +
                            "A proxy can see, and change, the web traffic that passes through it unless that traffic is encrypted from end to end.",
                        cannotTell = listOf(
                            "Who runs the proxy or why it was set.",
                            "Which apps actually use it: only some apps follow the setting.",
                            "Whether encrypted traffic is being opened: that needs an added certificate authority, which this app reports separately.",
                        ),
                        actions = listOf(
                            Action(
                                "Open Wi-Fi settings, open the network you are connected to and check its proxy setting. Switch it off if you do not recognize it.",
                                "If your workplace or school needs the proxy, that network may stop working without it.",
                            ),
                        ),
                        settings = SettingsTarget.WIFI,
                    )
                }
            }
        }
        else -> {
            val reason = unavailableReason(network)
            statuses += netSignals.map { SignalStatus(it, Availability.Unavailable(reason), ConditionSources.NETWORK) }
            for (kind in listOf(FactKind.CONNECTION, FactKind.VPN, FactKind.PRIVATE_DNS, FactKind.HTTP_PROXY)) {
                facts += DeviceFact(kind, "${kind.label}: not readable. $reason", Availability.Unavailable(reason), null)
            }
        }
    }

    // ---- Wi-Fi security type
    when (wifi) {
        is ReadResult.Ok -> {
            val w = wifi.value
            if (!w.connected) {
                facts += DeviceFact(FactKind.WIFI_SECURITY, "Wi-Fi security: not connected to Wi-Fi.", Availability.Available, null)
                statuses += SignalStatus(Signals.WIFI_SECURITY, Availability.Available, ConditionSources.WIFI)
            } else if (w.security == null) {
                val reason = w.note ?: "Android does not tell apps the security type of a Wi-Fi network on this version without the location permission."
                facts += DeviceFact(FactKind.WIFI_SECURITY, "Wi-Fi security: not readable. $reason", Availability.Unavailable(reason), null)
                statuses += SignalStatus(Signals.WIFI_SECURITY, Availability.Unavailable(reason), ConditionSources.WIFI)
            } else {
                val claim = obs(
                    Signals.WIFI_SECURITY, subject, Value.Text(w.security.name), Confidence.HIGH, ConditionSources.WIFI,
                    listOf("This is the security type of the network the phone is connected to. It does not say who runs the network."), at,
                )
                facts += DeviceFact(FactKind.WIFI_SECURITY, "Wi-Fi security: ${securityText(w.security)}.", Availability.Available, claim)
                statuses += SignalStatus(Signals.WIFI_SECURITY, Availability.Available, ConditionSources.WIFI)
                if (w.security == WifiSecurity.OPEN || w.security == WifiSecurity.WEP) {
                    val open = w.security == WifiSecurity.OPEN
                    findings += finding(
                        id = "condition:wifi-security", section = NETWORK,
                        title = if (open) "You are on an open Wi-Fi network, so others nearby may see your traffic" else "You are on a Wi-Fi network that uses WEP, which is easy to break",
                        attention = if (open) Attention.LOOK else Attention.LOOK_FIRST, claim = claim,
                        meaning = if (open) {
                            "An open network has no password, and Wi-Fi itself does not encrypt what you send. Most websites and apps add their own encryption, " +
                                "but the network's owner and anyone nearby may see which sites and apps you contact, and can try to tamper with traffic that is not encrypted."
                        } else {
                            "WEP is an old Wi-Fi method that can be broken in minutes with common tools, so the password gives little real protection against someone nearby."
                        },
                        cannotTell = listOf(
                            "Who runs this network or whether they mean harm: open networks include honest cafe and airport Wi-Fi.",
                            "Whether the apps you use encrypt their traffic: this app does not see other apps' traffic.",
                            "Whether the network's name is the real one or a lookalike: this app does not read it.",
                        ),
                        actions = listOf(
                            Action(
                                "Use mobile data, or a network that needs a password, for anything private such as banking.",
                                "Mobile data may count against your plan, and another Wi-Fi network may not be available.",
                            ),
                        ),
                        settings = SettingsTarget.WIFI,
                    )
                }
            }
        }
        else -> {
            val reason = unavailableReason(wifi)
            facts += DeviceFact(FactKind.WIFI_SECURITY, "Wi-Fi security: not readable. $reason", Availability.Unavailable(reason), null)
            statuses += SignalStatus(Signals.WIFI_SECURITY, Availability.Unavailable(reason), ConditionSources.WIFI)
        }
    }

    // ---- added certificate authorities
    when (userCas) {
        is ReadResult.Ok -> {
            val count = userCas.value.sha256.size
            statuses += SignalStatus(Signals.USER_CAS, Availability.Available, ConditionSources.USER_CAS)
            val limits = listOf(
                "This reading was checked on one emulator, by placing a certificate in the user store. It has not been checked on a real phone, on other Android versions, " +
                    "or after installing a certificate through Settings, so it is reported with medium confidence.",
                "It lists certificates added by a user or an administrator. It does not say whether any traffic is being inspected.",
            )
            if (count == 0) {
                facts += DeviceFact(FactKind.USER_CAS, "Added certificate authorities: none found (medium confidence: checked on one emulator only).", Availability.Available, null)
            } else {
                val claim = obs(Signals.USER_CAS, subject, Value.Items(userCas.value.sha256), Confidence.MEDIUM, ConditionSources.USER_CAS, limits, at)
                facts += DeviceFact(FactKind.USER_CAS, "Added certificate authorities: $count found (medium confidence: checked on one emulator only).", Availability.Available, claim)
                findings += finding(
                    id = "condition:user-cas", section = NETWORK, title = if (count == 1) "1 extra certificate authority is trusted, which could let someone read encrypted traffic" else "$count extra certificate authorities are trusted, which could let someone read encrypted traffic", attention = Attention.LOOK, claim = claim,
                    meaning = "Certificate authorities are the organizations your phone trusts to vouch for a website's identity. An added one can vouch for any website, " +
                        "which is how workplace networks and some filtering or testing tools inspect encrypted traffic. It is also what an attacker would need to do the same.",
                    cannotTell = listOf(
                        "Who added it or why.",
                        "Whether any traffic is actually being inspected.",
                        "Whether the reading is complete on your phone: it has been checked on one emulator only, not on a real phone.",
                    ),
                    actions = listOf(
                        Action(
                            "Open security settings, find the list of user or installed certificates (under encryption and credentials; the name differs by phone) and remove ones you do not recognize.",
                            "Removing a certificate that your employer or school installed can stop their Wi-Fi, mail or apps from working.",
                        ),
                    ),
                    settings = SettingsTarget.SECURITY,
                )
            }
        }
        else -> {
            val reason = unavailableReason(userCas)
            facts += DeviceFact(FactKind.USER_CAS, "Added certificate authorities: not readable. $reason", Availability.Unavailable(reason), null)
            statuses += SignalStatus(Signals.USER_CAS, Availability.Unavailable(reason), ConditionSources.USER_CAS)
        }
    }
    return ConditionOutput(facts, findings, statuses)
}
