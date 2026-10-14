package io.github.h_sib.declared.core.model

import java.time.Instant

/** How a fact is known (CONCEPT.md, glass-box principle). Sensors never produce [INVISIBLE]; the blind-spot registry does. */
/**
 * Where a reading comes from. DECLARED is an app's own statement about itself (its metadata); TOLD is the person's own
 * answer to a question, kept apart so a screen never calls the user's word "the app's own statement".
 */
enum class Provenance { OBSERVED, DECLARED, TOLD, INFERRED, INVISIBLE }

/** Ordinal confidence. The natural order is LOW < MEDIUM < HIGH, so "weakest link" is the minimum. */
enum class Confidence {
    LOW,
    MEDIUM,
    HIGH,
    ;

    companion object {
        fun weakest(values: Iterable<Confidence>): Confidence? = values.minOrNull()
    }
}

@JvmInline
value class SignalId(val value: String) {
    init {
        require(value.isNotBlank()) { "A signal id must not be blank" }
    }

    override fun toString(): String = value
}

/** The signals Stage 1 can produce. New sensors add entries here; nothing else in core changes. */
object Signals {
    val ACCESSIBILITY_INSTALLED = SignalId("special-access.accessibility.installed")
    val ACCESSIBILITY_ENABLED = SignalId("special-access.accessibility.enabled")
    val ACCESSIBILITY_CAPABILITIES = SignalId("special-access.accessibility.capabilities")
    val ACCESSIBILITY_DECLARED_TOOL = SignalId("special-access.accessibility.declared-tool")
    val NOTIFICATION_INSTALLED = SignalId("special-access.notification-listener.installed")
    val NOTIFICATION_ENABLED = SignalId("special-access.notification-listener.enabled")
    val DEVICE_ADMIN_ACTIVE = SignalId("special-access.device-admin.active")
    val DEVICE_ADMIN_POLICIES = SignalId("special-access.device-admin.policies")
    val INPUT_METHOD_ENABLED = SignalId("special-access.input-method.enabled")
    val INPUT_METHOD_DEFAULT = SignalId("special-access.input-method.default")
    val OVERLAY = SignalId("special-access.overlay")
    val USAGE_ACCESS = SignalId("special-access.usage-access")
    val INSTALL_UNKNOWN_APPS = SignalId("special-access.install-unknown-apps")
    val SMS_ACCESS = SignalId("grant.sms")
    val APP_INSTALL_ORIGIN = SignalId("app.install-origin")
    val PACKAGE_INVENTORY = SignalId("app.inventory")
    val PATCH_LEVEL = SignalId("device.patch-level")
    val SCREEN_LOCK = SignalId("device.screen-lock")
    val USB_DEBUGGING = SignalId("device.usb-debugging")
    val WIRELESS_DEBUGGING = SignalId("device.wireless-debugging")
    val DEVELOPER_OPTIONS = SignalId("device.developer-options")
    val ADVANCED_PROTECTION = SignalId("device.advanced-protection")
    val NETWORK_ACTIVE = SignalId("network.active")
    val PRIVATE_DNS = SignalId("network.private-dns")
    val HTTP_PROXY = SignalId("network.http-proxy")
    val WIFI_SECURITY = SignalId("network.wifi-security")
    val USER_CAS = SignalId("network.user-cas")
    val ATTESTATION_CHAIN = SignalId("integrity.attestation-chain")
    val BOOT_STATE = SignalId("integrity.boot-state")
    val BOOTLOADER = SignalId("integrity.bootloader")
    val ATTESTED_PATCH = SignalId("integrity.attested-patch")
    val ATTESTATION_REFERENCE = SignalId("integrity.reference-data")
    val BASELINE_ANSWER = SignalId("baseline.answer")
    val BASELINE_SIGNER = SignalId("baseline.signer")
}

sealed interface Subject {
    data object Device : Subject

    data class App(val pkg: String) : Subject

    data object Network : Subject
}

sealed interface Value {
    data class Flag(val on: Boolean) : Value

    data class Text(val text: String) : Value

    data class Count(val n: Long) : Value

    data class Items(val items: List<String>) : Value
}

/** Where a reading came from: the API, the first Android version that has it, and what it needed. */
data class SourceInfo(
    val api: String,
    val minApi: Int,
    val permissions: List<String> = emptyList(),
)

data class Observation(
    val id: SignalId,
    val subject: Subject,
    val value: Value,
    val provenance: Provenance,
    val confidence: Confidence,
    val source: SourceInfo,
    /** Plain-language notes about what this reading cannot tell. Never empty by convention (tested per sensor). */
    val limits: List<String>,
    val observedAt: Instant,
)

/** Per signal, per phone. A reading the platform refuses is [Unavailable], never an empty or green row. */
sealed interface Availability {
    data object Available : Availability

    data class Degraded(val reason: String) : Availability

    data class Unavailable(val reason: String) : Availability
}

data class SignalStatus(
    val id: SignalId,
    val availability: Availability,
    val source: SourceInfo,
)
