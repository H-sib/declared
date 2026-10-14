package io.github.h_sib.declared.core.paths

import io.github.h_sib.declared.core.model.CapabilityKind
import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.Declarations
import io.github.h_sib.declared.core.model.Profile
import io.github.h_sib.declared.core.model.Provenance
import io.github.h_sib.declared.core.model.SettingsLink
import io.github.h_sib.declared.core.model.SettingsTarget
import io.github.h_sib.declared.core.model.Told
import io.github.h_sib.declared.core.report.AppRow
import io.github.h_sib.declared.core.report.Holder
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.state.Answer
import io.github.h_sib.declared.core.state.Review
import io.github.h_sib.declared.core.state.SavedState
import io.github.h_sib.declared.core.tier.Tier

/**
 * Turns one phone into an attack graph. The rules are THREAT_MODEL.md section 5 (P1 to P6), written as code now and kept in
 * this one file so that moving them to data later is a mechanical change. Every rule names its technique ids, what it needs,
 * the levers that cut it and what this app cannot see on it.
 *
 * Two choices that matter:
 * - A package whose holders all came with the phone (platform or system tier) gets no nodes at all: it can never be
 *   "unfamiliar", so every path through it is FALSE, and leaving it out changes no answer.
 * - A path that is certainly FALSE and cannot change (an app with a launcher icon cannot be a hidden-icon app) is left out
 *   for the same reason; nothing that could be UNKNOWN or fixable is ever left out.
 */
object RuleBook {
    const val PATCH_LIMIT_GENERAL_DAYS = 365
    const val PATCH_LIMIT_HIGH_RISK_DAYS = 180

    private val UNUSUAL_ORIGIN = setOf(Tier.SIDELOAD, Tier.ADB, Tier.UNKNOWN)
    private val BUILT_IN = setOf(Tier.PLATFORM, Tier.SYSTEM)

    private fun sev(general: Severity, highRisk: Severity) = mapOf(Profile.GENERAL to general, Profile.HIGH_RISK to highRisk)

    fun build(report: Report, saved: SavedState?, declarations: Declarations): AttackGraph {
        val b = GraphBuilder()
        val phone = report.phone
        val apps: Map<String, AppRow> = report.apps.associateBy { it.pkg }
        val changedSigners = Review.signerChanges(report, saved).map { it.holder.pkg }.toSet()

        // ---------------------------------------------------------------- what the person said
        val smsCodes = declared(b, "told:sms-codes", declarations.smsCodes, "Your accounts send you codes by text message", "You have not said whether your accounts send codes by text message.")
        val publicWifi = declared(b, "told:public-wifi", declarations.publicWifi, "You often use public Wi-Fi", "You have not said whether you often use public Wi-Fi.")
        b.lever(
            Lever(
                "authenticator", "Move your accounts from text-message codes to an authenticator app or a passkey",
                "Each account has to be changed on its own website or app, and you need the new method with you when you sign in.",
                cost = 3, flips = setOf(smsCodes), outsidePhone = true,
            ),
        )
        b.lever(
            Lever(
                "avoid-public-wifi", "Avoid public Wi-Fi, or use mobile data in those places",
                "Mobile data can cost money and may be slower or missing in some places.",
                cost = 2, flips = setOf(publicWifi), outsidePhone = true,
            ),
        )

        // ---------------------------------------------------------------- the phone itself: P3, P4, P6
        val dnsOff = stateLeaf(b, "state:private-dns-off", StateKey.PRIVATE_DNS_OFF, phone, "Private DNS is not in use on this connection")
        val onOpenWifi = stateLeaf(b, "state:open-wifi", StateKey.ON_OPEN_WIFI, phone, "You are connected to an open Wi-Fi network (no password)")
        val userCa = stateLeaf(b, "state:user-ca", StateKey.USER_CA_PRESENT, phone, "A certificate authority added by a user or an administrator is installed")
        val proxy = stateLeaf(b, "state:proxy", StateKey.WEB_PROXY_SET, phone, "A web proxy is set for this connection")
        val noLock = stateLeaf(b, "state:no-lock", StateKey.NO_SCREEN_LOCK, phone, "This phone has no screen lock")
        val apOff = stateLeaf(b, "state:ap-off", StateKey.ADVANCED_PROTECTION_OFF, phone, "Advanced Protection is off")

        val limit = if (declarations.profile == Profile.HIGH_RISK) PATCH_LIMIT_HIGH_RISK_DAYS else PATCH_LIMIT_GENERAL_DAYS
        val age = phone.patchAgeDays
        val patchOld = if (age == null) {
            b.leaf("state:patch-old", "The phone's last security update is older than $limit days", Tri.UNKNOWN, Provenance.OBSERVED, Confidence.LOW, phone.patchReason ?: "The security update date was not read.")
        } else {
            b.leaf(
                "state:patch-old", "The phone's last security update is about ${age / 30} months old (the limit used here is $limit days)",
                Tri.of(age >= limit), Provenance.OBSERVED, Confidence.HIGH,
            )
        }

        b.lever(lever("private-dns-on", "Turn on Private DNS", "Some networks block it, and a few work or school networks need their own DNS.", 1, setOf(dnsOff), SettingsTarget.NETWORK))
        b.lever(lever("avoid-open-wifi", "Do not use open Wi-Fi networks", "You may have no connection in places that only offer open Wi-Fi.", 2, setOf(onOpenWifi), SettingsTarget.WIFI, outsidePhone = true))
        b.lever(lever("remove-ca", "Remove the added certificate authority", "A work, school or filtering service may need it; without it some sites or networks stop working.", 2, setOf(userCa), SettingsTarget.SECURITY))
        b.lever(lever("clear-proxy", "Clear the web proxy", "A network that needs the proxy will stop working until it is set again.", 1, setOf(proxy), SettingsTarget.WIFI))
        b.lever(lever("set-lock", "Set a screen lock", "You will have to unlock the phone each time you use it.", 1, setOf(noLock), SettingsTarget.SECURITY))
        b.lever(lever("update-phone", "Install the latest system update", "An update takes time and restarts the phone. If the maker no longer provides updates there is nothing to install, and the only fix is another phone.", 2, setOf(patchOld), SettingsTarget.DEVICE_INFO))
        b.lever(lever("advanced-protection-on", "Turn on Advanced Protection", "It blocks some things on purpose, such as installing apps from outside a store.", 2, setOf(apOff), SettingsTarget.SECURITY))

        b.goal(
            Goal(
                b.or("p3-ca", "Traffic you send can be read or changed by someone on the network, because an added certificate is trusted", userCa),
                "Someone on the network could read what you send, because of an added certificate", Asset.TRAFFIC, listOf("T1638"), sev(Severity.MEDIUM, Severity.HIGH),
                listOf("Whether anyone is actually on the network doing this.", "What the certificate was added for: a workplace may add one on purpose."),
            ),
        )
        b.goal(
            Goal(
                b.or("p3-proxy", "Traffic you send goes through a proxy that can see or change it", proxy),
                "Your traffic goes through a proxy that could read or change it", Asset.TRAFFIC, listOf("T1638"), sev(Severity.MEDIUM, Severity.HIGH),
                listOf("Whose proxy it is, or whether it is yours on purpose."),
            ),
        )
        val openWifiNoDns = b.and("p3-open-wifi-way", "You are on an open Wi-Fi network and your name lookups are not encrypted", onOpenWifi, dnsOff)
        val publicWifiNoDns = b.and("p3-public-wifi-way", "You often use public Wi-Fi and your name lookups are not encrypted", publicWifi, dnsOff)
        b.goal(
            Goal(
                b.or("p3-wifi", "Someone on public Wi-Fi could redirect where your lookups go", openWifiNoDns, publicWifiNoDns),
                "Someone on public Wi-Fi could redirect where your lookups go", Asset.TRAFFIC, listOf("T1638"), sev(Severity.MEDIUM, Severity.HIGH),
                listOf("Passive listening that changes nothing.", "What the network does once you are connected to a sound website over HTTPS."),
            ),
        )
        b.goal(
            Goal(
                b.or("p4-no-lock", "Anyone who picks up the phone can use it", noLock),
                "Anyone who holds the phone for a minute can use it", Asset.DEVICE, listOf("T1626.001", "T1453", "T1629.001"), sev(Severity.HIGH, Severity.CRITICAL),
                listOf("Everything done while this app was not watching."),
            ),
        )
        b.goal(
            Goal(
                b.or("p6-patch", "The phone has known security holes that later updates close", patchOld),
                "The phone is missing security updates that close known holes", Asset.DEVICE, listOf("T1404", "T1664", "T1658"), sev(Severity.MEDIUM, Severity.HIGH),
                listOf("Whether anyone is using one of those holes against this phone.", "Fixes the maker may have shipped outside the update date shown."),
            ),
        )
        b.goal(
            Goal(
                b.or("p6-advanced-protection", "A stricter protection mode that this phone supports is off", apOff),
                "A stricter protection mode that this phone supports is switched off", Asset.DEVICE, listOf("T1404", "T1664"), sev(Severity.LOW, Severity.HIGH),
                listOf("Whether the mode would stop anything that actually threatens you."),
            ),
        )

        // ---------------------------------------------------------------- per package: P1, P2, P5
        val byPkg = report.holders.filter { it.active }.groupBy { it.pkg }
        for ((pkg, holders) in byPkg.toSortedMap()) {
            if (holders.all { it.tier.tier in BUILT_IN }) continue
            val label = holders.first().label
            val app = apps[pkg]
            val unfamiliar = unfamiliarLeaf(b, pkg, label, holders, saved, pkg in changedSigners)
            // Everything this package is allowed to do, for the lever that removes the app.
            val powers = ArrayList<NodeId>()
            fun power(id: String, text: String, confidence: Confidence, provenance: Provenance = Provenance.OBSERVED): NodeId =
                b.leaf(id, text, Tri.TRUE, provenance, confidence).also { powers += it }

            val screenWays = ArrayList<NodeId>()
            for (h in holders.filter { it.kind == CapabilityKind.ACCESSIBILITY && it.powers.powers.any { p -> p.key == "window-content" } }) {
                val part = h.subLabel?.takeIf { it != label }?.let { " ($it)" }.orEmpty()
                val on = power("a11y:${h.key}", "Accessibility is switched on for $label$part", confidenceOf(report, h))
                val able = b.leaf("a11y-read:${h.key}", "That service says it may read the text and layout shown in other apps", Tri.TRUE, Provenance.DECLARED, Confidence.HIGH)
                screenWays += b.and("reads-screen:${h.key}", "$label can read what is on your screen", on, able)
                b.lever(lever("a11y-off:${h.key}", "Turn off the accessibility service of $label", "If you use it for something (a screen reader, a password manager, an automation), that will stop working.", 2, setOf(on), SettingsTarget.ACCESSIBILITY))
            }
            val screen = when (screenWays.size) {
                0 -> null
                1 -> screenWays.single()
                else -> b.or("reads-screen:$pkg", "$label can read what is on your screen", screenWays)
            }
            val notif = holders.firstOrNull { it.kind == CapabilityKind.NOTIFICATION_LISTENER }?.let { h ->
                val n = power("notif:${h.key}", "$label is allowed to read every notification", confidenceOf(report, h))
                b.lever(lever("notif-off:${h.key}", "Take notification access away from $label", "It will stop showing or acting on your notifications, which may be what it is for.", 2, setOf(n), SettingsTarget.NOTIFICATION_ACCESS))
                n
            }
            val sms = holders.firstOrNull { it.kind == CapabilityKind.SMS_ACCESS }?.let { h ->
                val n = power("sms:$pkg", "$label can read your text messages", confidenceOf(report, h))
                b.lever(lever("sms-off:$pkg", "Take the text-message permission away from $label", "It will stop reading messages, which may be what it is for.", 2, setOf(n), SettingsTarget.APP_DETAILS, pkg))
                n
            }
            val admin = holders.firstOrNull { it.kind == CapabilityKind.DEVICE_ADMIN }?.let { h ->
                val n = power("admin:${h.key}", "$label is a device administrator of this phone", confidenceOf(report, h))
                b.lever(lever("admin-off:${h.key}", "Remove $label as a device administrator", "It may refuse to be removed while it protects something (a work profile, a lost-phone service).", 2, setOf(n), SettingsTarget.SECURITY))
                n
            }
            holders.firstOrNull { it.kind == CapabilityKind.OVERLAY }?.let { h ->
                val n = power("overlay:$pkg", "$label may draw over other apps", confidenceOf(report, h))
                b.lever(lever("overlay-off:$pkg", "Stop $label drawing over other apps", "Things it shows on top of other apps (bubbles, floating buttons) will stop appearing.", 1, setOf(n), SettingsTarget.OVERLAY, pkg))
            }

            // P1: the screen can be read by an app the person has no reason to trust.
            if (screen != null) {
                b.goal(
                    Goal(
                        b.and("p1:$pkg", "$label could capture sign-in details and codes from the screen", screen, unfamiliar),
                        "$label could capture sign-in details and codes from your screen", Asset.ACCOUNT,
                        listOf("T1660", "T1453", "T1417.002", "T1517", "T1407", "T1655.001"), sev(Severity.HIGH, Severity.CRITICAL),
                        listOf("What the service actually does.", "Anything the app downloads and runs later."),
                    ),
                )
            }

            // P2: a one-time code can be taken by an app that can read messages, notifications or the screen.
            val codeWays = listOfNotNull(sms, notif, screen)
            if (codeWays.isNotEmpty()) {
                val way = if (codeWays.size == 1) codeWays.single() else b.or("code-ways:$pkg", "$label has a way to see a text message or notification as it arrives", codeWays)
                b.goal(
                    Goal(
                        b.and("p2:$pkg", "$label could take one-time codes sent to you by text message", way, unfamiliar, smsCodes),
                        "$label could take one-time codes sent to you by text message", Asset.CODES,
                        listOf("T1636.004", "T1517", "T1582"), sev(Severity.MEDIUM, Severity.HIGH),
                        listOf("Interception on the carrier's side.", "What the app does with a code it can see."),
                    ),
                )
            }

            // P5: the shape of an app made to watch someone: administrator or screen reader, no icon, and a way to follow you.
            val control = listOfNotNull(admin, screen)
            if (control.isNotEmpty() && app != null && !app.hasLauncherIcon) {
                val grants = app.grants.toSet()
                val follows = listOfNotNull(
                    if ("ACCESS_FINE_LOCATION" in grants || "ACCESS_BACKGROUND_LOCATION" in grants) power("location:$pkg", "$label may use your location", Confidence.HIGH) else null,
                    if ("RECORD_AUDIO" in grants) power("mic:$pkg", "$label may use the microphone", Confidence.HIGH) else null,
                )
                if (follows.isNotEmpty()) {
                    val hidden = power("hidden:$pkg", "$label has no icon in the launcher", Confidence.HIGH)
                    val hold = if (control.size == 1) control.single() else b.or("control:$pkg", "$label controls the phone through an administrator role or a screen reader", control)
                    val follow = if (follows.size == 1) follows.single() else b.or("follow:$pkg", "$label can follow where you are or what you say", follows)
                    b.goal(
                        Goal(
                            b.and("p5:$pkg", "$label has the shape of an app made to watch someone", hold, hidden, follow, unfamiliar),
                            "$label has the shape of an app made to watch someone", Asset.LOCATION,
                            listOf("T1626.001", "T1453", "T1628.001", "T1430", "T1429", "T1512"), sev(Severity.HIGH, Severity.CRITICAL),
                            listOf("What the app does: this is only the combination of abilities.", "Apps installed in disguised or system-level ways."),
                        ),
                    )
                }
            }
            // Removing the app ends everything it is allowed to do, and so every path that depended on it.
            if (powers.isNotEmpty()) {
                b.lever(lever("remove:$pkg", "Remove $label", "You lose the app and what it stored on this phone.", 3, powers.toSet(), SettingsTarget.APP_DETAILS, pkg))
            }
        }
        return b.build()
    }

    // ---------------------------------------------------------------- helpers

    private fun lever(
        id: String,
        text: String,
        tradeOff: String,
        cost: Int,
        flips: Set<NodeId>,
        target: SettingsTarget,
        pkg: String? = null,
        outsidePhone: Boolean = false,
    ) = Lever(id, text, tradeOff, cost, flips, SettingsLink(target, pkg), outsidePhone)

    private fun confidenceOf(report: Report, h: Holder): Confidence = h.findingId?.let { report.finding(it)?.confidence } ?: Confidence.MEDIUM

    private fun declared(b: GraphBuilder, id: String, told: Told, yes: String, unsure: String): NodeId = when (told) {
        Told.YES -> b.leaf(id, yes, Tri.TRUE, Provenance.TOLD, Confidence.MEDIUM)
        Told.NO -> b.leaf(id, yes, Tri.FALSE, Provenance.TOLD, Confidence.MEDIUM)
        Told.UNSURE -> b.leaf(id, yes, Tri.UNKNOWN, Provenance.TOLD, Confidence.LOW, unsure)
    }

    private fun stateLeaf(b: GraphBuilder, id: String, key: StateKey, phone: PhoneState, text: String): NodeId {
        val s = phone.of(key)
        return b.leaf(id, text, s.value, Provenance.OBSERVED, s.confidence, s.reason)
    }

    /**
     * Is this app one the person has reason to look at? Their own answer decides first (they said they do not recognize it, or
     * that they do), a changed signing certificate always counts, and without an answer an origin outside a store counts.
     */
    private fun unfamiliarLeaf(b: GraphBuilder, pkg: String, label: String, holders: List<Holder>, saved: SavedState?, signerChanged: Boolean): NodeId {
        val answers = holders.mapNotNull { saved?.answers?.get(Review.key(it))?.answer }
        val tier = holders.first().tier
        val id = "unfamiliar:$pkg"
        return when {
            Answer.NOT_RECOGNIZED in answers -> b.leaf(id, "You said you do not recognize $label", Tri.TRUE, Provenance.TOLD, Confidence.HIGH)
            signerChanged -> b.leaf(id, "$label is now signed by a different certificate than when you last looked", Tri.TRUE, Provenance.OBSERVED, Confidence.HIGH)
            answers.isNotEmpty() && answers.all { it == Answer.RECOGNIZED } -> b.leaf(id, "You said you recognize $label", Tri.FALSE, Provenance.TOLD, Confidence.HIGH)
            tier.tier in UNUSUAL_ORIGIN -> b.leaf(id, "$label: ${tier.reason}", Tri.TRUE, Provenance.INFERRED, tier.confidence)
            else -> b.leaf(id, "$label came from a store or a setup flow", Tri.FALSE, Provenance.INFERRED, tier.confidence)
        }
    }
}
