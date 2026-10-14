package io.github.h_sib.declared.sensors

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build

/**
 * Version-gated wrappers around package-manager calls that changed shape in Android 13. Kept in one place so the
 * deprecation suppressions are too.
 */
internal object Compat {

    @Suppress("DEPRECATION")
    fun services(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) pm.queryIntentServices(intent, PackageManager.ResolveInfoFlags.of(0))
        else pm.queryIntentServices(intent, 0)

    @Suppress("DEPRECATION")
    fun receivers(pm: PackageManager, intent: Intent, flags: Int = 0): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) pm.queryBroadcastReceivers(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        else pm.queryBroadcastReceivers(intent, flags)

    @Suppress("DEPRECATION")
    fun activities(pm: PackageManager, intent: Intent): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        else pm.queryIntentActivities(intent, 0)

    /**
     * Lint warns that this no longer lists every app since Android 11. That is the design, not an oversight: the app
     * deliberately lists only what its `<queries>` entries make visible instead of holding QUERY_ALL_PACKAGES, and the
     * blind-spot section of the inventory says so in plain words (DESIGN.md section 7, B1).
     */
    @SuppressLint("QueryPermissionsNeeded")
    @Suppress("DEPRECATION")
    fun installedPackages(pm: PackageManager, flags: Int): List<PackageInfo> =
        if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getInstalledPackages(flags)

    @Suppress("DEPRECATION")
    fun packageInfo(pm: PackageManager, pkg: String, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
        else pm.getPackageInfo(pkg, flags)
}
