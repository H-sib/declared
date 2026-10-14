package io.github.h_sib.declared.core.sensor

/**
 * Outcome of asking Android for something. The Android adapters translate platform exceptions into these, so the
 * logic in this module never sees an Android type and every refusal path can be tested with a fake.
 */
sealed interface ReadResult<out T> {
    data class Ok<T>(val value: T) : ReadResult<T>

    /** The platform deliberately refused (for example a SecurityException). A fact about this phone and Android version. */
    data class Refused(val reason: String) : ReadResult<Nothing>

    /** Something unexpected went wrong. A bug or a vendor quirk, reported as such and never as "all clear". */
    data class Failed(val reason: String) : ReadResult<Nothing>
}

/** An Android component as `package/class`, with the class always fully qualified. */
data class ComponentId(val pkg: String, val cls: String) {
    fun flatten(): String = "$pkg/$cls"

    companion object {
        /** Parses `pkg/cls` and `pkg/.Short`, the two forms Android writes into settings values. */
        fun parse(flat: String): ComponentId? {
            val slash = flat.indexOf('/')
            if (slash <= 0 || slash == flat.length - 1) return null
            val pkg = flat.substring(0, slash)
            val cls = flat.substring(slash + 1).let { if (it.startsWith('.')) pkg + it else it }
            return ComponentId(pkg, cls)
        }

        /** Parses a colon-separated settings value such as `enabled_accessibility_services`. */
        fun parseList(raw: String?): Set<ComponentId> =
            raw.orEmpty().split(':').mapNotNull { parse(it.trim()) }.toSet()
    }
}

// ---------------------------------------------------------------- accessibility (milestone 1)

data class AccessibilityServiceFact(
    val component: ComponentId,
    /** The raw `AccessibilityServiceInfo.getCapabilities()` bitmask. */
    val capabilityBits: Int,
    /** The service's own claim to be an accessibility tool. Null below Android 14, where the platform cannot say. */
    val declaresAccessibilityTool: Boolean?,
    /** The service's own display name (one app can ship several services, for example a screen reader and a reading tool). */
    val label: String? = null,
)

data class AccessibilityReading(
    val installed: List<AccessibilityServiceFact>,
    /** What the accessibility manager reports as enabled. This is the primary source. */
    val enabledByManager: Set<ComponentId>,
    /** What the `enabled_accessibility_services` setting says. Null when it could not be read. A cross-check only. */
    val enabledBySetting: Set<ComponentId>?,
)

interface AccessibilitySource {
    fun read(): ReadResult<AccessibilityReading>
}

// ---------------------------------------------------------------- notification listeners, device admins, keyboards

data class ListenerFact(val component: ComponentId, val label: String? = null)

data class NotificationListenerReading(
    val installed: List<ListenerFact>,
    /** From the `enabled_notification_listeners` setting (lab P3: matched the system on every level). */
    val enabled: Set<ComponentId>,
)

interface NotificationListenerSource {
    fun read(): ReadResult<NotificationListenerReading>
}

data class DeviceAdminFact(
    val component: ComponentId,
    /** Bit n set when the admin declares `DeviceAdminInfo.USES_POLICY_*` number n. Null when its metadata could not be read. */
    val policyBits: Int?,
    val label: String? = null,
)

data class DeviceAdminReading(
    val installed: List<DeviceAdminFact>,
    /** `DevicePolicyManager.getActiveAdmins`. May name components of packages this app cannot see. */
    val active: Set<ComponentId>,
)

interface DeviceAdminSource {
    fun read(): ReadResult<DeviceAdminReading>
}

data class InputMethodFact(val component: ComponentId, val label: String? = null)

data class InputMethodReading(
    val installed: List<InputMethodFact>,
    /** From `InputMethodManager.getEnabledInputMethodList`. The raw setting is blocked from Android 14 for targets above 33 (lab P5). */
    val enabled: Set<ComponentId>,
    /** From `Settings.Secure.default_input_method`. Null when unset or unreadable. */
    val default: ComponentId?,
)

interface InputMethodSource {
    fun read(): ReadResult<InputMethodReading>
}

// ---------------------------------------------------------------- device facts and network (milestone 3)

/** Advanced Protection needs Android 16 and a permission; each way it can be unreadable is its own state. */
sealed interface AdvancedProtectionState {
    /** Below Android 16 there is no such feature. */
    data object Unsupported : AdvancedProtectionState

    /** The platform has it but the permission to ask was not granted to this app. */
    data object NotPermitted : AdvancedProtectionState

    data class Known(val enabled: Boolean) : AdvancedProtectionState

    data class Failed(val reason: String) : AdvancedProtectionState
}

/**
 * Each part is read on its own and carried as its own [ReadResult], so one refused setting never erases the others
 * (lab defect D-002). Settings that do not exist on a phone are `null`, not zero: "not set" is a different fact from "off".
 */
data class DeviceFactsReading(
    val androidRelease: String,
    val sdkInt: Int,
    /** `Build.VERSION.SECURITY_PATCH`, as the platform writes it (`yyyy-MM-dd`). */
    val securityPatch: ReadResult<String?>,
    /** `KeyguardManager.isDeviceSecure`. */
    val screenLock: ReadResult<Boolean>,
    val usbDebugging: ReadResult<Int?>,
    val wirelessDebugging: ReadResult<Int?>,
    val developerOptions: ReadResult<Int?>,
    val advancedProtection: AdvancedProtectionState,
)

interface DeviceSource {
    fun read(): DeviceFactsReading
}

enum class Transport { WIFI, CELLULAR, VPN, ETHERNET, OTHER }

data class ActiveNetwork(
    /** False when the phone has no active network at all. Everything else is then empty. */
    val present: Boolean,
    val transports: Set<Transport>,
    val internet: Boolean,
    /** Android's own check that the network really reaches the internet. */
    val validated: Boolean,
    val captivePortal: Boolean,
    val privateDnsActive: Boolean?,
    val privateDnsServerName: String?,
    /** A proxy configured for this network or for the whole phone, as text (`host:port` or a script address). Null when none. */
    val httpProxy: String?,
    /** True when Android would not name the connection in use and it was worked out from the networks this app can see ([DefaultNetworkGuess]). */
    val inferred: Boolean = false,
)

interface NetworkSource {
    fun read(): ReadResult<ActiveNetwork>
}

enum class WifiSecurity { OPEN, WEP, WPA_PSK, WPA3_PSK, ENTERPRISE, OWE, WAPI, OTHER, UNKNOWN }

data class WifiReading(
    val connected: Boolean,
    /** Null when the platform will not say without the location permission (Android 11 and older, lab Q6). */
    val security: WifiSecurity?,
    val note: String? = null,
)

interface WifiSource {
    fun read(): ReadResult<WifiReading>
}

/** The certificate authorities added by a user or an administrator, by SHA-256 of the certificate. */
data class UserCaReading(val sha256: List<String>)

interface UserCaSource {
    fun read(): ReadResult<UserCaReading>
}

// ---------------------------------------------------------------- key attestation (milestone 4)

/** What the phone returned for a freshly attested key: the certificate chain (leaf first, DER) and the challenge it was asked to sign. */
class AttestationEvidence(val chainDer: List<ByteArray>, val challenge: ByteArray)

interface AttestationSource {
    fun read(): ReadResult<AttestationEvidence>
}

/** The bundled Google roots and revocation list. A mismatch with the bundle's own checksums is a refusal, not data. */
interface ReferenceSource {
    fun read(): ReadResult<io.github.h_sib.declared.core.integrity.ReferenceData>
}

// ---------------------------------------------------------------- packages, special access, runtime grants

/** Mirrors `PackageInstaller.PACKAGE_SOURCE_*` (Android 13 and later). */
enum class InstallOrigin { UNSPECIFIED, OTHER, STORE, LOCAL_FILE, DOWNLOADED_FILE }

/**
 * The permissions this app looks at. Special access (app-ops) first, then the runtime permissions that matter for the
 * attack paths in THREAT_MODEL.md. Everything else an app requests is ignored on purpose (smaller data, less to leak).
 */
object WatchedPermissions {
    const val OVERLAY = "android.permission.SYSTEM_ALERT_WINDOW"
    const val USAGE_STATS = "android.permission.PACKAGE_USAGE_STATS"
    const val INSTALL_PACKAGES = "android.permission.REQUEST_INSTALL_PACKAGES"
    const val READ_SMS = "android.permission.READ_SMS"
    const val RECEIVE_SMS = "android.permission.RECEIVE_SMS"

    /** Granted-permission names shown per app in the inventory. */
    val SENSITIVE: Set<String> = setOf(
        READ_SMS, RECEIVE_SMS, "android.permission.SEND_SMS",
        "android.permission.READ_CONTACTS", "android.permission.READ_CALL_LOG",
        "android.permission.RECORD_AUDIO", "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.QUERY_ALL_PACKAGES",
    )

    /** Special-access permissions whose state lives in an app-op. */
    val SPECIAL: Set<String> = setOf(OVERLAY, USAGE_STATS, INSTALL_PACKAGES)

    val ALL: Set<String> = SENSITIVE + SPECIAL

    fun short(permission: String): String = permission.substringAfterLast('.')
}

data class PackageFacts(
    val pkg: String,
    /** Shown on the phone only. Exports redact it (DESIGN.md section 9). */
    val label: String?,
    val uid: Int = -1,
    val system: Boolean,
    val updatedSystem: Boolean,
    /** True when the package is signed with the platform key. Null when the check itself failed. */
    val platformSigned: Boolean?,
    val installingPackage: String?,
    val initiatingPackage: String?,
    /** Null below Android 13. */
    val installOrigin: InstallOrigin?,
    /** Null below Android 14. */
    val updateOwner: String?,
    /** False when Android refused to give this app the install record at all. */
    val installRecordReadable: Boolean,
    /** SHA-256 of each signing certificate, lowercase hex. App identity for baseline review (CONCEPT.md, use 3). */
    val signerSha256: List<String>,
    val hasLauncherIcon: Boolean,
    /** Of the watched permissions, the ones the app declares in its manifest. */
    val requestedWatched: Set<String> = emptySet(),
    /** Of the watched permissions, the ones Android reports as granted right now. */
    val grantedWatched: Set<String> = emptySet(),
)

interface PackageFactsSource {
    /** One package, by name. Used for a holder whose package the bulk listing did not include. */
    fun facts(pkg: String): ReadResult<PackageFacts>

    /** Every package this app can see. On Android 11 and later that is a subset of what is installed (blind spot B1). */
    fun all(): ReadResult<List<PackageFacts>>
}

/** The three special-access settings whose state lives in an app-op (lab P7). */
enum class SpecialAccess(val permission: String) {
    OVERLAY(WatchedPermissions.OVERLAY),
    USAGE_ACCESS(WatchedPermissions.USAGE_STATS),
    INSTALL_UNKNOWN_APPS(WatchedPermissions.INSTALL_PACKAGES),
}

/** `AppOpsManager.MODE_*`. DEFAULT means "follow the permission", so the effective state needs the permission's grant state too. */
enum class OpMode { ALLOWED, IGNORED, ERRORED, DEFAULT, FOREGROUND, UNKNOWN }

interface AppOpsSource {
    /**
     * Another app's special-access modes. Android 10 refuses with a SecurityException for every package (lab P7);
     * Android 12 and later answer. Android 11 has not been measured.
     */
    fun modes(pkg: String, uid: Int): ReadResult<Map<SpecialAccess, OpMode>>
}
