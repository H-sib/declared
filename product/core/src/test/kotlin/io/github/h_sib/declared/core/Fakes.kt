package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.report.ReportBuilder
import io.github.h_sib.declared.core.report.Sources
import io.github.h_sib.declared.core.sensor.AccessibilityReading
import io.github.h_sib.declared.core.sensor.ActiveNetwork
import io.github.h_sib.declared.core.integrity.ReferenceData
import io.github.h_sib.declared.core.sensor.AdvancedProtectionState
import io.github.h_sib.declared.core.sensor.AttestationEvidence
import io.github.h_sib.declared.core.sensor.AttestationSource
import io.github.h_sib.declared.core.sensor.ReferenceSource
import io.github.h_sib.declared.core.sensor.DeviceFactsReading
import io.github.h_sib.declared.core.sensor.DeviceSource
import io.github.h_sib.declared.core.sensor.NetworkSource
import io.github.h_sib.declared.core.sensor.Transport
import io.github.h_sib.declared.core.sensor.UserCaReading
import io.github.h_sib.declared.core.sensor.UserCaSource
import io.github.h_sib.declared.core.sensor.WifiReading
import io.github.h_sib.declared.core.sensor.WifiSource
import io.github.h_sib.declared.core.sensor.AccessibilityServiceFact
import io.github.h_sib.declared.core.sensor.AccessibilitySource
import io.github.h_sib.declared.core.sensor.AppOpsSource
import io.github.h_sib.declared.core.sensor.ComponentId
import io.github.h_sib.declared.core.sensor.DeviceAdminReading
import io.github.h_sib.declared.core.sensor.DeviceAdminSource
import io.github.h_sib.declared.core.sensor.InputMethodReading
import io.github.h_sib.declared.core.sensor.InputMethodSource
import io.github.h_sib.declared.core.sensor.InstallOrigin
import io.github.h_sib.declared.core.sensor.NotificationListenerReading
import io.github.h_sib.declared.core.sensor.NotificationListenerSource
import io.github.h_sib.declared.core.sensor.OpMode
import io.github.h_sib.declared.core.sensor.PackageFacts
import io.github.h_sib.declared.core.sensor.PackageFactsSource
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.SpecialAccess
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-09T10:00:00Z"), ZoneOffset.UTC)

class FakeAccessibilitySource(private val result: ReadResult<AccessibilityReading>) : AccessibilitySource {
    override fun read() = result
}

class FakeNotificationListenerSource(private val result: ReadResult<NotificationListenerReading>) : NotificationListenerSource {
    override fun read() = result
}

class FakeDeviceAdminSource(private val result: ReadResult<DeviceAdminReading>) : DeviceAdminSource {
    override fun read() = result
}

class FakeInputMethodSource(private val result: ReadResult<InputMethodReading>) : InputMethodSource {
    override fun read() = result
}

class FakeAppOpsSource(
    private val byPackage: Map<String, ReadResult<Map<SpecialAccess, OpMode>>> = emptyMap(),
    private val otherwise: ReadResult<Map<SpecialAccess, OpMode>> = ReadResult.Ok(emptyMap()),
) : AppOpsSource {
    val asked = mutableListOf<String>()

    override fun modes(pkg: String, uid: Int): ReadResult<Map<SpecialAccess, OpMode>> {
        asked += pkg
        return byPackage[pkg] ?: otherwise
    }
}

class FakePackageFactsSource(
    private val byPackage: Map<String, ReadResult<PackageFacts>>,
    private val listing: ReadResult<List<PackageFacts>>? = null,
) : PackageFactsSource {
    val asked = mutableListOf<String>()

    override fun facts(pkg: String): ReadResult<PackageFacts> {
        asked += pkg
        return byPackage[pkg] ?: ReadResult.Failed("no such package in the fake: $pkg")
    }

    override fun all(): ReadResult<List<PackageFacts>> =
        listing ?: ReadResult.Ok(byPackage.values.mapNotNull { (it as? ReadResult.Ok)?.value })
}

fun component(pkg: String, cls: String = "$pkg.Service") = ComponentId(pkg, cls)

fun service(pkg: String, bits: Int = 0x1 or 0x20, tool: Boolean? = null, label: String? = null) =
    AccessibilityServiceFact(component(pkg), bits, tool, label)

fun facts(
    pkg: String,
    label: String? = pkg,
    system: Boolean = false,
    updatedSystem: Boolean = false,
    platformSigned: Boolean? = false,
    installing: String? = "com.android.vending",
    initiating: String? = installing,
    origin: InstallOrigin? = null,
    readable: Boolean = true,
    uid: Int = 10000,
    requested: Set<String> = emptySet(),
    granted: Set<String> = emptySet(),
    signers: List<String> = listOf("00".repeat(32)),
    launcher: Boolean = true,
) = PackageFacts(
    pkg = pkg,
    label = label,
    uid = uid,
    system = system,
    updatedSystem = updatedSystem,
    platformSigned = platformSigned,
    installingPackage = installing,
    initiatingPackage = initiating,
    installOrigin = origin,
    updateOwner = null,
    installRecordReadable = readable,
    signerSha256 = signers,
    hasLauncherIcon = launcher,
    requestedWatched = requested,
    grantedWatched = granted,
)

fun reading(
    installed: List<AccessibilityServiceFact>,
    enabled: Set<ComponentId>,
    setting: Set<ComponentId>? = enabled,
) = AccessibilityReading(installed, enabled, setting)

val NO_ACCESSIBILITY: ReadResult<AccessibilityReading> = ReadResult.Ok(AccessibilityReading(emptyList(), emptySet(), emptySet()))
val NO_LISTENERS: ReadResult<NotificationListenerReading> = ReadResult.Ok(NotificationListenerReading(emptyList(), emptySet()))
val NO_ADMINS: ReadResult<DeviceAdminReading> = ReadResult.Ok(DeviceAdminReading(emptyList(), emptySet()))
val NO_KEYBOARDS: ReadResult<InputMethodReading> = ReadResult.Ok(InputMethodReading(emptyList(), emptySet(), null))

/** A full set of sources where everything not mentioned reads as "nothing there". */
fun sources(
    accessibility: ReadResult<AccessibilityReading> = NO_ACCESSIBILITY,
    listeners: ReadResult<NotificationListenerReading> = NO_LISTENERS,
    admins: ReadResult<DeviceAdminReading> = NO_ADMINS,
    keyboards: ReadResult<InputMethodReading> = NO_KEYBOARDS,
    appOps: AppOpsSource = FakeAppOpsSource(),
    packages: PackageFactsSource = FakePackageFactsSource(emptyMap()),
    device: DeviceFactsReading = healthyDevice(),
    network: ReadResult<ActiveNetwork> = QUIET_NETWORK,
    wifi: ReadResult<WifiReading> = NOT_ON_WIFI,
    userCas: ReadResult<UserCaReading> = NO_USER_CAS,
    attestation: ReadResult<AttestationEvidence> = ReadResult.Refused("not exercised in this test"),
    reference: ReadResult<ReferenceData> = ReadResult.Refused("not exercised in this test"),
) = Sources(
    FakeAccessibilitySource(accessibility),
    FakeNotificationListenerSource(listeners),
    FakeDeviceAdminSource(admins),
    FakeInputMethodSource(keyboards),
    appOps,
    packages,
    FakeDeviceSource(device),
    FakeNetworkSource(network),
    FakeWifiSource(wifi),
    FakeUserCaSource(userCas),
    FakeAttestationSource(attestation),
    FakeReferenceSource(reference),
)

fun builder(sources: Sources) = ReportBuilder(sources, clock = FIXED_CLOCK)

fun factsMap(vararg items: PackageFacts): Map<String, ReadResult<PackageFacts>> =
    items.associate { it.pkg to ReadResult.Ok(it) }

class FakeDeviceSource(private val reading: DeviceFactsReading) : DeviceSource {
    override fun read() = reading
}

class FakeNetworkSource(private val result: ReadResult<ActiveNetwork>) : NetworkSource {
    override fun read() = result
}

class FakeWifiSource(private val result: ReadResult<WifiReading>) : WifiSource {
    override fun read() = result
}

class FakeUserCaSource(private val result: ReadResult<UserCaReading>) : UserCaSource {
    override fun read() = result
}

/** A phone with nothing to report: a recent patch (the fixed clock is 2026-10-09), a lock, debugging off. */
fun healthyDevice(
    patch: ReadResult<String?> = ReadResult.Ok("2026-09-05"),
    lock: ReadResult<Boolean> = ReadResult.Ok(true),
    usb: ReadResult<Int?> = ReadResult.Ok(0),
    wireless: ReadResult<Int?> = ReadResult.Ok(null),
    developer: ReadResult<Int?> = ReadResult.Ok(0),
    advanced: AdvancedProtectionState = AdvancedProtectionState.Unsupported,
) = DeviceFactsReading("14", 34, patch, lock, usb, wireless, developer, advanced)

fun network(
    present: Boolean = true,
    transports: Set<Transport> = setOf(Transport.CELLULAR),
    validated: Boolean = true,
    captive: Boolean = false,
    privateDns: Boolean? = true,
    privateDnsName: String? = "dns.example",
    proxy: String? = null,
) = ReadResult.Ok(ActiveNetwork(present, transports, true, validated, captive, privateDns, privateDnsName, proxy))

val QUIET_NETWORK: ReadResult<ActiveNetwork> = network()
val NOT_ON_WIFI: ReadResult<WifiReading> = ReadResult.Ok(WifiReading(connected = false, security = null))
val NO_USER_CAS: ReadResult<UserCaReading> = ReadResult.Ok(UserCaReading(emptyList()))

class FakeAttestationSource(private val result: ReadResult<AttestationEvidence>) : AttestationSource {
    override fun read() = result
}

class FakeReferenceSource(private val result: ReadResult<ReferenceData>) : ReferenceSource {
    override fun read() = result
}
