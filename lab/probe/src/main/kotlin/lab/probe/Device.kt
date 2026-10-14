package lab.probe

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import org.json.JSONObject

/** D-series: device and system signals. */
object Device {

    fun collect(ctx: Context): List<Reading> {
        val cr = ctx.contentResolver
        return listOf(
            reading("D1", "Security patch level", 1) {
                jo("securityPatch" to Build.VERSION.SECURITY_PATCH)
            },
            reading("D2", "OS and device", 1) {
                jo(
                    "release" to Build.VERSION.RELEASE,
                    "sdk" to Build.VERSION.SDK_INT,
                    "codename" to Build.VERSION.CODENAME,
                    "manufacturer" to Build.MANUFACTURER,
                    "model" to Build.MODEL,
                    "display" to Build.DISPLAY,
                )
            },
            reading("D3", "Screen lock present", 1) {
                val km = ctx.getSystemService(KeyguardManager::class.java)
                jo("isDeviceSecure" to km.isDeviceSecure, "isKeyguardSecure" to km.isKeyguardSecure)
            },
            reading("D4", "USB debugging", 1) {
                jo("adb_enabled" to Settings.Global.getInt(cr, Settings.Global.ADB_ENABLED, -1))
            },
            reading("D5", "Wireless debugging", 1) {
                jo("adb_wifi_enabled" to Settings.Global.getInt(cr, "adb_wifi_enabled", -1))
            },
            reading("D6", "Developer options", 1) {
                jo("development_settings_enabled" to Settings.Global.getInt(cr, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, -1))
            },
            reading("D7", "Advanced Protection", 1) {
                if (Build.VERSION.SDK_INT < 36) {
                    Flagged(Code.UNSUPPORTED, null, "needs API 36")
                } else {
                    val granted = ctx.checkSelfPermission("android.permission.QUERY_ADVANCED_PROTECTION_MODE") ==
                        PackageManager.PERMISSION_GRANTED
                    val manager = ctx.getSystemService(android.security.advancedprotection.AdvancedProtectionManager::class.java)
                    jo("permissionGranted" to granted, "enabled" to manager?.isAdvancedProtectionEnabled)
                }
            },
            reading("D8", "Play Protect (no official API expected)", 1) {
                jo(
                    "package_verifier_enable" to Settings.Global.getInt(cr, "package_verifier_enable", -1),
                    "verifier_verify_adb_installs" to Settings.Global.getInt(cr, "verifier_verify_adb_installs", -1),
                    "package_verifier_user_consent" to Settings.Secure.getInt(cr, "package_verifier_user_consent", -2),
                )
            },
            reading("D9", "Management state", 2) {
                val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
                val um = ctx.getSystemService(UserManager::class.java)
                jo(
                    "selfIsDeviceOwner" to dpm.isDeviceOwnerApp(ctx.packageName),
                    "selfIsProfileOwner" to dpm.isProfileOwnerApp(ctx.packageName),
                    "isManagedProfile" to if (Build.VERSION.SDK_INT >= 30) um.isManagedProfile else null,
                )
            },
            reading("D10", "Users and profiles", 2) {
                val um = ctx.getSystemService(UserManager::class.java)
                val privateProfile = try {
                    UserManager::class.java.getMethod("isPrivateProfile").invoke(um)
                } catch (e: Throwable) {
                    null
                }
                jo(
                    "profileCount" to um.userProfiles.size,
                    "isSystemUser" to um.isSystemUser,
                    "isPrivateProfile" to privateProfile,
                )
            },
        )
    }

    /**
     * D11: reads OEM settings keys found through the D12 discovery protocol.
     * @param spec comma-separated entries such as "secure:name", "global:name" or "system:name" (default scope: secure)
     */
    fun customKeys(ctx: Context, spec: String?): Reading = reading("D11", "OEM settings keys (from discovery)", 1) {
        val cr = ctx.contentResolver
        val out = JSONObject()
        spec?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.forEach { entry ->
            val parts = entry.split(":", limit = 2)
            val scope = if (parts.size == 2) parts[0] else "secure"
            val name = if (parts.size == 2) parts[1] else parts[0]
            val value: Any = try {
                when (scope) {
                    "global" -> Settings.Global.getString(cr, name)
                    "system" -> Settings.System.getString(cr, name)
                    else -> Settings.Secure.getString(cr, name)
                } ?: JSONObject.NULL
            } catch (e: Throwable) {
                "ERR:" + e.javaClass.simpleName
            }
            out.put(entry, value)
        }
        out
    }
}
