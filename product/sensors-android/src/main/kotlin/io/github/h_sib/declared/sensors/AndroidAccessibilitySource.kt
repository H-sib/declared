package io.github.h_sib.declared.sensors

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import io.github.h_sib.declared.core.sensor.AccessibilityReading
import io.github.h_sib.declared.core.sensor.AccessibilityServiceFact
import io.github.h_sib.declared.core.sensor.AccessibilitySource
import io.github.h_sib.declared.core.sensor.ComponentId
import io.github.h_sib.declared.core.sensor.ReadResult

/**
 * Reads accessibility holders the way the lab probe did (lab P1/P2, which matched adb ground truth on Android 10
 * and 12 to 17). Needs no permission. Installed services are visible through the `<queries>` entry for the
 * AccessibilityService action in the app manifest.
 */
class AndroidAccessibilitySource(private val context: Context) : AccessibilitySource {

    override fun read(): ReadResult<AccessibilityReading> = guarded {
        val manager = context.getSystemService(AccessibilityManager::class.java)
        val installed = manager.installedAccessibilityServiceList.mapNotNull(::toFact)
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .mapNotNull(::componentOf).toSet()
        val setting = try {
            ComponentId.parseList(Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
        } catch (e: SecurityException) {
            null // only the cross-check is lost
        }
        AccessibilityReading(installed, enabled, setting)
    }

    private fun componentOf(info: AccessibilityServiceInfo): ComponentId? =
        info.resolveInfo?.serviceInfo?.let { ComponentId(it.packageName, it.name) }

    private fun toFact(info: AccessibilityServiceInfo): AccessibilityServiceFact? {
        val component = componentOf(info) ?: return null
        val tool = if (Build.VERSION.SDK_INT >= 34) info.isAccessibilityTool else null
        val label = info.resolveInfo?.loadLabel(context.packageManager)?.toString()?.takeIf { it.isNotBlank() }
        return AccessibilityServiceFact(component, info.capabilities, tool, label)
    }
}
