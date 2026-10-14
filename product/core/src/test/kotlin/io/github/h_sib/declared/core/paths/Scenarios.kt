package io.github.h_sib.declared.core.paths

import io.github.h_sib.declared.core.model.Declarations
import io.github.h_sib.declared.core.model.Told
import io.github.h_sib.declared.core.model.Profile
import io.github.h_sib.declared.core.FakePackageFactsSource
import io.github.h_sib.declared.core.builder
import io.github.h_sib.declared.core.component
import io.github.h_sib.declared.core.facts
import io.github.h_sib.declared.core.factsMap
import io.github.h_sib.declared.core.healthyDevice
import io.github.h_sib.declared.core.NOT_ON_WIFI
import io.github.h_sib.declared.core.NO_USER_CAS
import io.github.h_sib.declared.core.QUIET_NETWORK
import io.github.h_sib.declared.core.network
import io.github.h_sib.declared.core.reading
import io.github.h_sib.declared.core.service
import io.github.h_sib.declared.core.sources
import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.Provenance
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.sensor.ActiveNetwork
import io.github.h_sib.declared.core.sensor.AdvancedProtectionState
import io.github.h_sib.declared.core.sensor.DeviceAdminFact
import io.github.h_sib.declared.core.sensor.DeviceAdminReading
import io.github.h_sib.declared.core.sensor.DeviceFactsReading
import io.github.h_sib.declared.core.sensor.ListenerFact
import io.github.h_sib.declared.core.sensor.NotificationListenerReading
import io.github.h_sib.declared.core.sensor.PackageFacts
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.Transport
import io.github.h_sib.declared.core.sensor.UserCaReading
import io.github.h_sib.declared.core.sensor.WatchedPermissions
import io.github.h_sib.declared.core.sensor.WifiReading
import io.github.h_sib.declared.core.sensor.WifiSecurity
import io.github.h_sib.declared.core.state.Answer
import io.github.h_sib.declared.core.state.Review
import io.github.h_sib.declared.core.state.SavedState
import java.time.Instant


const val FROM_A_FILE = "com.google.android.packageinstaller"

/** Phones for the attack-path tests: one function to build any phone through the real report pipeline with fake sources. */
fun phone(
    packages: List<PackageFacts> = emptyList(),
    a11y: List<String> = emptyList(),
    listeners: List<String> = emptyList(),
    admins: List<String> = emptyList(),
    device: DeviceFactsReading = healthyDevice(),
    network: ReadResult<ActiveNetwork> = QUIET_NETWORK,
    wifi: ReadResult<WifiReading> = NOT_ON_WIFI,
    userCas: ReadResult<UserCaReading> = NO_USER_CAS,
): Report {
    val services = a11y.map { service(it) }
    return builder(
        sources(
            accessibility = ReadResult.Ok(reading(services, services.map { it.component }.toSet())),
            listeners = ReadResult.Ok(NotificationListenerReading(listeners.map { ListenerFact(component(it)) }, listeners.map { component(it) }.toSet())),
            admins = ReadResult.Ok(DeviceAdminReading(admins.map { DeviceAdminFact(component(it, "$it.Admin"), 0x1) }, admins.map { component(it, "$it.Admin") }.toSet())),
            packages = FakePackageFactsSource(factsMap(*packages.toTypedArray())),
            device = device,
            network = network,
            wifi = wifi,
            userCas = userCas,
        ),
    ).build(34)
}

fun sideloaded(pkg: String) = facts(pkg, installing = FROM_A_FILE, initiating = FROM_A_FILE, launcher = true)

/** One app that can do almost everything, plus everything wrong with the phone: the corpus entry that touches every rule. */
fun worstCase(): Pair<Report, Declarations> {
    val shadow = sideloaded("shadow.app").copy(
        hasLauncherIcon = false,
        requestedWatched = setOf(WatchedPermissions.READ_SMS, "android.permission.ACCESS_FINE_LOCATION"),
        grantedWatched = setOf(WatchedPermissions.READ_SMS, "android.permission.ACCESS_FINE_LOCATION"),
    )
    val report = phone(
        packages = listOf(shadow),
        a11y = listOf("shadow.app"), listeners = listOf("shadow.app"), admins = listOf("shadow.app"),
        device = healthyDevice(patch = ReadResult.Ok("2024-01-01"), lock = ReadResult.Ok(false), advanced = AdvancedProtectionState.Known(false)),
        network = network(transports = setOf(Transport.WIFI), privateDns = false, proxy = "10.0.0.2:3128"),
        wifi = ReadResult.Ok(WifiReading(true, WifiSecurity.OPEN)),
        userCas = ReadResult.Ok(UserCaReading(listOf("ab".repeat(32)))),
    )
    return report to Declarations(smsCodes = Told.YES, publicWifi = Told.YES)
}

