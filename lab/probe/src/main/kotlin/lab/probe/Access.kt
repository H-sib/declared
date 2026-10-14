package lab.probe

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityManager
import android.view.inputmethod.InputMethodManager
import org.json.JSONObject

@Suppress("DEPRECATION")
fun PackageManager.installedCompat(flags: Int): List<PackageInfo> =
    if (Build.VERSION.SDK_INT >= 33) getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
    else getInstalledPackages(flags)

@Suppress("DEPRECATION")
fun PackageManager.infoCompat(pkg: String, flags: Int): PackageInfo =
    if (Build.VERSION.SDK_INT >= 33) getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
    else getPackageInfo(pkg, flags)

/** P-series: special access and runtime state. */
object Access {

    val SENSITIVE_PERMISSIONS = setOf(
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.SEND_SMS",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_CALL_LOG",
        "android.permission.RECORD_AUDIO",
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_BACKGROUND_LOCATION",
        "android.permission.SYSTEM_ALERT_WINDOW",
        "android.permission.REQUEST_INSTALL_PACKAGES",
        "android.permission.PACKAGE_USAGE_STATS",
        "android.permission.QUERY_ALL_PACKAGES",
    )

    private fun accessibilityJson(i: AccessibilityServiceInfo): JSONObject = jo(
        "id" to rd(i.id),
        "package" to rd(i.resolveInfo?.serviceInfo?.packageName),
        "capabilities" to i.capabilities,
        "flags" to i.flags,
        "eventTypes" to i.eventTypes,
        "feedbackType" to i.feedbackType,
        "isAccessibilityTool" to if (Build.VERSION.SDK_INT >= 34) i.isAccessibilityTool else null,
    )

    /** App-ops check for another package. Whether a normal app may do this at all is what P7 and P8 measure. */
    private fun mode(ops: AppOpsManager, op: String, uid: Int, pkg: String): Any = try {
        ops.unsafeCheckOpNoThrow(op, uid, pkg)
    } catch (e: Throwable) {
        "ERR:" + e.javaClass.simpleName
    }

    private fun candidatePackages(ctx: Context): List<String> {
        val interesting = setOf(
            "android.permission.SYSTEM_ALERT_WINDOW",
            "android.permission.REQUEST_INSTALL_PACKAGES",
            "android.permission.PACKAGE_USAGE_STATS",
            "android.permission.READ_SMS",
        )
        return ctx.packageManager.installedCompat(PackageManager.GET_PERMISSIONS)
            .filter { pi ->
                pi.packageName.startsWith("lab.fixture.") ||
                    (pi.requestedPermissions?.any { it in interesting } == true &&
                        (pi.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM == 0)
            }
            .map { it.packageName }
            .take(25)
    }

    fun collect(ctx: Context): List<Reading> {
        val cr = ctx.contentResolver
        val pm = ctx.packageManager
        return listOf(
            reading("P1", "Accessibility services (includes P2 capability flags)", 1) {
                val am = ctx.getSystemService(AccessibilityManager::class.java)
                val enabled = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                val installed = am.installedAccessibilityServiceList
                jo(
                    "enabled" to enabled.map { accessibilityJson(it) },
                    "installedCount" to installed.size,
                    "installed" to installed.map { accessibilityJson(it) },
                    "secureSetting" to rd(Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)),
                    "accessibilityEnabled" to Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, -1),
                )
            },
            reading("P3", "Notification listeners", 1) {
                jo("secureSetting" to rd(Settings.Secure.getString(cr, "enabled_notification_listeners")))
            },
            reading("P4", "Active device admins", 1) {
                val dpm = ctx.getSystemService(DevicePolicyManager::class.java)
                jo("admins" to dpm.activeAdmins?.map { rd(it.flattenToShortString()) })
            },
            reading("P5", "Input methods", 1) {
                val imm = ctx.getSystemService(InputMethodManager::class.java)
                // Each part is read independently so one restricted source does not hide the others.
                fun part(block: () -> Any?): Any? = try {
                    block()
                } catch (e: Throwable) {
                    "ERR:" + e.javaClass.simpleName + ":" + e.message
                }
                jo(
                    "enabledMethods" to part { imm.enabledInputMethodList.map { rd(it.id) } },
                    "installedMethodCount" to part { imm.inputMethodList.size },
                    "defaultInputMethod" to part { rd(Settings.Secure.getString(cr, Settings.Secure.DEFAULT_INPUT_METHOD)) },
                    "enabledInputMethodsSetting" to part { rd(Settings.Secure.getString(cr, Settings.Secure.ENABLED_INPUT_METHODS)) },
                )
            },
            reading("P6", "Default handlers", 1) {
                val browser = pm.resolveActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("http://example.com")).addCategory(Intent.CATEGORY_BROWSABLE),
                    PackageManager.MATCH_DEFAULT_ONLY,
                )?.activityInfo?.packageName
                jo(
                    "defaultSms" to rd(Telephony.Sms.getDefaultSmsPackage(ctx)),
                    "defaultDialer" to rd(ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage),
                    "assistant" to rd(Settings.Secure.getString(cr, "assistant")),
                    "voiceInteraction" to rd(Settings.Secure.getString(cr, "voice_interaction_service")),
                    "browser" to rd(browser),
                )
            },
            reading("P7", "App-ops per app (overlay, usage stats, install, read SMS)", 1) {
                val ops = ctx.getSystemService(AppOpsManager::class.java)
                val rows = JSONObject()
                val targets = candidatePackages(ctx)
                for (p in targets) {
                    @Suppress("DEPRECATION")
                    val uid = pm.getApplicationInfo(p, 0).uid
                    rows.put(
                        rd(p) ?: p,
                        jo(
                            "systemAlertWindow" to mode(ops, "android:system_alert_window", uid, p),
                            "getUsageStats" to mode(ops, "android:get_usage_stats", uid, p),
                            "requestInstallPackages" to mode(ops, "android:request_install_packages", uid, p),
                            "readSms" to mode(ops, "android:read_sms", uid, p),
                        ),
                    )
                }
                val opKeys = listOf("systemAlertWindow", "getUsageStats", "requestInstallPackages", "readSms")
                val everyReadRefused = targets.isNotEmpty() && rows.keys().asSequence().all { k ->
                    val row = rows.getJSONObject(k)
                    opKeys.all { (row.opt(it) as? String)?.startsWith("ERR:") == true }
                }
                val result = jo("count" to targets.size, "rows" to rows)
                // On Android 10 the platform throws for every cross-app app-ops read. Report that as a refused
                // signal instead of an OK reading full of error strings.
                if (everyReadRefused) Flagged(Code.SECURITY_EXCEPTION, result, "every cross-app app-ops read was refused") else result
            },
            reading("P9", "Sensitive permissions (declared and granted), non-system apps", 1) {
                val rows = mutableListOf<JSONObject>()
                val all = pm.installedCompat(PackageManager.GET_PERMISSIONS)
                for (pi in all) {
                    val flags = pi.applicationInfo?.flags ?: 0
                    val system = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    if (system && !pi.packageName.startsWith("lab.fixture.")) continue
                    val requested = pi.requestedPermissions ?: continue
                    val grantFlags = pi.requestedPermissionsFlags ?: continue
                    val declared = requested.filter { it in SENSITIVE_PERMISSIONS }
                    if (declared.isEmpty()) continue
                    val granted = requested.indices
                        .filter { (grantFlags[it] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 }
                        .map { requested[it] }
                        .filter { it in SENSITIVE_PERMISSIONS }
                    rows.add(
                        jo(
                            "package" to rd(pi.packageName),
                            "declared" to declared.map { it.substringAfterLast('.') },
                            "granted" to granted.map { it.substringAfterLast('.') },
                        ),
                    )
                }
                jo("visiblePackages" to all.size, "rows" to rows)
            },
        )
    }
}
