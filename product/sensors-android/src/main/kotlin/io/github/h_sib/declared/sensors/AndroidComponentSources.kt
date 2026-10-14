package io.github.h_sib.declared.sensors

import android.app.admin.DeviceAdminInfo
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import io.github.h_sib.declared.core.admin.DevicePolicy
import io.github.h_sib.declared.core.sensor.ComponentId
import io.github.h_sib.declared.core.sensor.DeviceAdminFact
import io.github.h_sib.declared.core.sensor.DeviceAdminReading
import io.github.h_sib.declared.core.sensor.DeviceAdminSource
import io.github.h_sib.declared.core.sensor.InputMethodFact
import io.github.h_sib.declared.core.sensor.InputMethodReading
import io.github.h_sib.declared.core.sensor.InputMethodSource
import io.github.h_sib.declared.core.sensor.ListenerFact
import io.github.h_sib.declared.core.sensor.NotificationListenerReading
import io.github.h_sib.declared.core.sensor.NotificationListenerSource
import io.github.h_sib.declared.core.sensor.ReadResult

private fun ResolveInfo.serviceComponent(): ComponentId? = serviceInfo?.let { ComponentId(it.packageName, it.name) }

/**
 * Notification listeners: installed ones through the `<queries>` entry for the listener action, enabled ones from
 * the `enabled_notification_listeners` setting (lab P3: matched the system on Android 10 and 12 to 17).
 */
class AndroidNotificationListenerSource(private val context: Context) : NotificationListenerSource {

    override fun read(): ReadResult<NotificationListenerReading> = guarded {
        val pm = context.packageManager
        val installed = Compat.services(pm, Intent("android.service.notification.NotificationListenerService")).mapNotNull { ri ->
            ri.serviceComponent()?.let { ListenerFact(it, ri.loadLabel(pm).toString().takeIf { l -> l.isNotBlank() }) }
        }
        val enabled = ComponentId.parseList(Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners"))
        NotificationListenerReading(installed, enabled)
    }
}

/**
 * Device admins: `DevicePolicyManager.getActiveAdmins` for the active ones (lab P4), and each installed admin
 * receiver's own metadata for the policies it declares. An admin whose metadata cannot be parsed keeps its place in
 * the list with unknown policies instead of vanishing.
 */
class AndroidDeviceAdminSource(private val context: Context) : DeviceAdminSource {

    override fun read(): ReadResult<DeviceAdminReading> = guarded {
        val pm = context.packageManager
        // GET_META_DATA is essential: without it the receiver's metadata is not loaded and DeviceAdminInfo cannot read
        // the declared policies (found by conformance, lab fakes cannot show it; DEFECT_LOG D-022).
        val installed = Compat.receivers(pm, Intent("android.app.action.DEVICE_ADMIN_ENABLED"), PackageManager.GET_META_DATA).mapNotNull { ri ->
            val info = ri.activityInfo ?: return@mapNotNull null
            val bits = try {
                val admin = DeviceAdminInfo(context, ri)
                DevicePolicy.bitsOf((0..9).filter { admin.usesPolicy(it) })
            } catch (e: Exception) {
                null
            }
            DeviceAdminFact(ComponentId(info.packageName, info.name), bits, ri.loadLabel(pm).toString().takeIf { l -> l.isNotBlank() })
        }
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val active = dpm.activeAdmins.orEmpty().map { ComponentId(it.packageName, it.className) }.toSet()
        DeviceAdminReading(installed, active)
    }
}

/**
 * Keyboards: installed and enabled lists from `InputMethodManager`, the one in use from `default_input_method`.
 * The raw `enabled_input_methods` setting is deliberately not used: it is blocked from Android 14 for apps
 * targeting above 33 (lab P5, refuted for the setting, confirmed for the manager).
 */
class AndroidInputMethodSource(private val context: Context) : InputMethodSource {

    override fun read(): ReadResult<InputMethodReading> = guarded {
        val pm = context.packageManager
        val imm = context.getSystemService(InputMethodManager::class.java)
        val installed = imm.inputMethodList.map { ComponentId(it.packageName, it.serviceName) to it.loadLabel(pm)?.toString() }
        val enabled = imm.enabledInputMethodList.map { ComponentId(it.packageName, it.serviceName) }.toSet()
        val default = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.let(ComponentId::parse)
        InputMethodReading(installed.map { (c, l) -> InputMethodFact(c, l?.takeIf { it.isNotBlank() }) }, enabled, default)
    }
}
