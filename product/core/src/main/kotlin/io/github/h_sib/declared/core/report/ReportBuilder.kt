package io.github.h_sib.declared.core.report

import io.github.h_sib.declared.core.conditions.mapDevice
import io.github.h_sib.declared.core.conditions.mapIntegrity
import io.github.h_sib.declared.core.conditions.mapNetwork
import io.github.h_sib.declared.core.holders.Candidate
import io.github.h_sib.declared.core.holders.HolderFindings
import io.github.h_sib.declared.core.holders.Mapped
import io.github.h_sib.declared.core.holders.ObservationSources
import io.github.h_sib.declared.core.holders.mapAccessibility
import io.github.h_sib.declared.core.holders.mapDeviceAdmins
import io.github.h_sib.declared.core.holders.mapInputMethods
import io.github.h_sib.declared.core.holders.mapNotificationListeners
import io.github.h_sib.declared.core.holders.mapSmsGrants
import io.github.h_sib.declared.core.holders.mapSpecialAccess
import io.github.h_sib.declared.core.model.Availability
import io.github.h_sib.declared.core.model.CapabilityKind
import io.github.h_sib.declared.core.model.Finding
import io.github.h_sib.declared.core.model.Observation
import io.github.h_sib.declared.core.model.Provenance
import io.github.h_sib.declared.core.model.SignalStatus
import io.github.h_sib.declared.core.model.Signals
import io.github.h_sib.declared.core.model.Subject
import io.github.h_sib.declared.core.model.Value
import io.github.h_sib.declared.core.paths.PhoneStates
import io.github.h_sib.declared.core.sensor.AccessibilitySource
import io.github.h_sib.declared.core.sensor.AppOpsSource
import io.github.h_sib.declared.core.sensor.AttestationSource
import io.github.h_sib.declared.core.sensor.DeviceAdminSource
import io.github.h_sib.declared.core.sensor.DeviceSource
import io.github.h_sib.declared.core.sensor.InputMethodSource
import io.github.h_sib.declared.core.sensor.NetworkSource
import io.github.h_sib.declared.core.sensor.NotificationListenerSource
import io.github.h_sib.declared.core.sensor.PackageFacts
import io.github.h_sib.declared.core.sensor.PackageFactsSource
import io.github.h_sib.declared.core.sensor.ReferenceSource
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.UserCaSource
import io.github.h_sib.declared.core.sensor.WifiSource
import io.github.h_sib.declared.core.sensor.WatchedPermissions
import io.github.h_sib.declared.core.tier.Tier
import io.github.h_sib.declared.core.tier.TierClassifier
import io.github.h_sib.declared.core.tier.TierVerdict
import java.time.Clock
import java.time.Instant

/** Everything the pipeline reads from the phone. Real implementations live in `sensors-android`; tests pass fakes. */
class Sources(
    val accessibility: AccessibilitySource,
    val notificationListeners: NotificationListenerSource,
    val deviceAdmins: DeviceAdminSource,
    val inputMethods: InputMethodSource,
    val appOps: AppOpsSource,
    val packages: PackageFactsSource,
    val device: DeviceSource,
    val network: NetworkSource,
    val wifi: WifiSource,
    val userCas: UserCaSource,
    val attestation: AttestationSource,
    val reference: ReferenceSource,
)

/**
 * Turns raw readings into a [Report]. This is the whole pipeline, and it touches no Android type: the sources are
 * interfaces, the clock is injected, and every refusal path is an ordinary return value.
 *
 * Cost: one pass over the package list plus a constant-time lookup per holder, so a phone with a thousand apps
 * costs about ten times what a hundred do (checked by a scale test).
 */
class ReportBuilder(
    private val sources: Sources,
    private val classifier: TierClassifier = TierClassifier(),
    private val clock: Clock = Clock.systemUTC(),
) {

    fun build(androidApi: Int): Report {
        val takenAt = clock.instant()
        val inventory = sources.packages.all()
        val apps: List<PackageFacts>? = (inventory as? ReadResult.Ok)?.value
        val byPackage = HashMap<String, PackageFacts?>((apps?.size ?: 8) * 2)
        apps?.forEach { byPackage[it.pkg] = it }

        val mapped = ArrayList<Mapped>()
        mapped += mapAccessibility(sources.accessibility.read(), takenAt)
        mapped += mapNotificationListeners(sources.notificationListeners.read(), takenAt)
        mapped += mapDeviceAdmins(sources.deviceAdmins.read(), takenAt)
        mapped += mapInputMethods(sources.inputMethods.read(), takenAt)
        val inventorySignal: SignalStatus
        if (apps != null) {
            mapped += mapSpecialAccess(apps, sources.appOps, takenAt)
            mapped += mapSmsGrants(apps, takenAt)
            inventorySignal = SignalStatus(Signals.PACKAGE_INVENTORY, Availability.Available, ObservationSources.INVENTORY)
        } else {
            val cause = when (inventory) {
                is ReadResult.Refused -> "Android refused: ${inventory.reason}"
                is ReadResult.Failed -> "the read failed: ${inventory.reason}"
                is ReadResult.Ok -> ""
            }
            val reason = "The package list could not be read, so per-app grants are not shown: $cause"
            inventorySignal = SignalStatus(Signals.PACKAGE_INVENTORY, Availability.Unavailable(reason), ObservationSources.INVENTORY)
            val gone = listOf(Signals.OVERLAY, Signals.USAGE_ACCESS, Signals.INSTALL_UNKNOWN_APPS, Signals.SMS_ACCESS)
            mapped += Mapped(emptyList(), gone.map { SignalStatus(it, Availability.Unavailable(reason), ObservationSources.APP_OPS) })
        }

        val verdicts = HashMap<String, TierVerdict>()
        fun verdictOf(facts: PackageFacts): TierVerdict = verdicts.getOrPut(facts.pkg) { classifier.classify(facts) }

        var unreadable = 0
        fun factsFor(pkg: String): PackageFacts? {
            if (byPackage.containsKey(pkg)) return byPackage[pkg]
            val facts = (sources.packages.facts(pkg) as? ReadResult.Ok)?.value
            if (facts == null) unreadable += 1
            byPackage[pkg] = facts
            return facts
        }

        val holders = ArrayList<Holder>()
        val findings = ArrayList<Finding>()
        val kindOfFinding = HashMap<String, Int>()
        val activeKinds = HashMap<String, MutableSet<CapabilityKind>>()
        for (candidate in mapped.flatMap { it.candidates }) {
            val facts = factsFor(candidate.pkg)
            val verdict = facts?.let(::verdictOf) ?: classifier.withoutRecord()
            val label = facts?.label ?: candidate.pkg
            val id = HolderFindings.findingId(candidate.kind, candidate.key)
            if (candidate.active) {
                activeKinds.getOrPut(candidate.pkg) { HashSet() } += candidate.kind
                kindOfFinding[id] = candidate.kind.ordinal
                findings += HolderFindings.build(
                    kind = candidate.kind,
                    key = candidate.key,
                    pkg = candidate.pkg,
                    label = label,
                    subject = candidate.subject,
                    tier = verdict,
                    powers = candidate.powers,
                    declaresAccessibilityTool = candidate.declaresAccessibilityTool,
                    claim = candidate.claim,
                    context = listOf(originObservation(candidate, verdict, takenAt)) + candidate.context,
                )
            }
            holders += Holder(
                kind = candidate.kind,
                key = candidate.key,
                pkg = candidate.pkg,
                label = label,
                subLabel = candidate.subLabel,
                active = candidate.active,
                tier = verdict,
                powers = candidate.powers,
                findingId = if (candidate.active) id else null,
            )
        }

        val today = takenAt.atZone(clock.zone).toLocalDate()
        val deviceReading = sources.device.read()
        val deviceOut = mapDevice(deviceReading, today, takenAt)
        val integrityOut = mapIntegrity(
            sources.attestation.read(), sources.reference.read(),
            (deviceReading.securityPatch as? ReadResult.Ok)?.value, takenAt, takenAt,
        )
        val networkReading = sources.network.read()
        val wifiReading = sources.wifi.read()
        val userCaReading = sources.userCas.read()
        val networkOut = mapNetwork(networkReading, wifiReading, userCaReading, takenAt)
        findings += deviceOut.findings
        findings += networkOut.findings
        findings += integrityOut.findings

        val appRows = apps.orEmpty().map { f ->
            AppRow(
                pkg = f.pkg,
                label = f.label ?: f.pkg,
                tier = verdictOf(f),
                signerSha256 = f.signerSha256,
                hasLauncherIcon = f.hasLauncherIcon,
                kinds = activeKinds[f.pkg].orEmpty(),
                grants = f.grantedWatched.filter { it in WatchedPermissions.SENSITIVE }.map(WatchedPermissions::short).sorted(),
            )
        }.sortedWith(compareBy<AppRow>({ TIER_ORDER.indexOf(it.tier.tier) }, { it.label.lowercase() }, { it.pkg }))

        return Report(
            takenAt = takenAt,
            androidApi = androidApi,
            signals = mapped.flatMap { it.statuses } + inventorySignal + originStatus(unreadable) + deviceOut.statuses + networkOut.statuses + integrityOut.statuses,
            holders = holders.sortedWith(compareBy<Holder>({ it.kind.ordinal }, { !it.active }, { it.label.lowercase() }, { it.key })),
            findings = findings.sortedWith(
                compareByDescending<Finding> { it.attention }
                    .thenBy { kindOfFinding[it.id] ?: Int.MAX_VALUE }
                    .thenBy { it.title.lowercase() }
                    .thenBy { it.id },
            ),
            apps = appRows,
            facts = (deviceOut.facts + networkOut.facts + integrityOut.facts).sortedBy { it.kind.ordinal },
            phone = PhoneStates.build(deviceReading, networkReading, wifiReading, userCaReading, today),
        )
    }

    private fun originStatus(unreadable: Int) = SignalStatus(
        Signals.APP_INSTALL_ORIGIN,
        if (unreadable == 0) {
            Availability.Available
        } else {
            Availability.Degraded("The install record could not be read for $unreadable app(s); they are shown as not recognized.")
        },
        ObservationSources.ORIGIN,
    )

    private fun originObservation(candidate: Candidate, verdict: TierVerdict, at: Instant) = Observation(
        id = Signals.APP_INSTALL_ORIGIN,
        subject = Subject.App(candidate.pkg),
        value = Value.Text("${verdict.tier}: ${verdict.reason}"),
        provenance = Provenance.INFERRED,
        confidence = verdict.confidence,
        source = ObservationSources.ORIGIN,
        limits = verdict.limits,
        observedAt = at,
    )

    private companion object {
        /** Most notable first, for lists of apps. A display order, not a ranking of danger. */
        val TIER_ORDER = listOf(Tier.SIDELOAD, Tier.ADB, Tier.UNKNOWN, Tier.OEM_OR_CARRIER_SETUP, Tier.STORE, Tier.SYSTEM, Tier.PLATFORM)
    }
}
