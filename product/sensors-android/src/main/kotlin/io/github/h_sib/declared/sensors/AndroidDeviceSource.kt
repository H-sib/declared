package io.github.h_sib.declared.sensors

import android.app.KeyguardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.security.advancedprotection.AdvancedProtectionManager
import io.github.h_sib.declared.core.sensor.AdvancedProtectionState
import io.github.h_sib.declared.core.sensor.DeviceFactsReading
import io.github.h_sib.declared.core.sensor.DeviceSource

/**
 * The phone's own settings, as the lab measured them (D1 to D7): patch level, screen lock, the three debugging
 * switches, Advanced Protection. Every part is read on its own so one refusal cannot hide the rest (lab D-002).
 * A setting that does not exist is `null`, which is different from "off" (the emulators have no developer-options flag).
 */
class AndroidDeviceSource(private val context: Context) : DeviceSource {

    override fun read(): DeviceFactsReading {
        val resolver = context.contentResolver
        return DeviceFactsReading(
            androidRelease = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            securityPatch = guarded { Build.VERSION.SECURITY_PATCH.takeIf { it.isNotBlank() } },
            screenLock = guarded { context.getSystemService(KeyguardManager::class.java).isDeviceSecure },
            usbDebugging = guarded { globalInt(resolver, Settings.Global.ADB_ENABLED) },
            wirelessDebugging = guarded { globalInt(resolver, "adb_wifi_enabled") },
            developerOptions = guarded { globalInt(resolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) },
            advancedProtection = advancedProtection(),
        )
    }

    private fun globalInt(resolver: android.content.ContentResolver, name: String): Int? =
        try {
            Settings.Global.getInt(resolver, name)
        } catch (e: Settings.SettingNotFoundException) {
            null
        }

    private fun advancedProtection(): AdvancedProtectionState {
        if (Build.VERSION.SDK_INT < 36) return AdvancedProtectionState.Unsupported
        if (context.checkSelfPermission("android.permission.QUERY_ADVANCED_PROTECTION_MODE") != PackageManager.PERMISSION_GRANTED) {
            return AdvancedProtectionState.NotPermitted
        }
        return try {
            val manager = context.getSystemService(AdvancedProtectionManager::class.java)
                ?: return AdvancedProtectionState.Failed("the service is missing")
            AdvancedProtectionState.Known(manager.isAdvancedProtectionEnabled)
        } catch (e: Exception) {
            AdvancedProtectionState.Failed("${e.javaClass.simpleName}: ${e.message.orEmpty().take(120)}")
        }
    }
}
