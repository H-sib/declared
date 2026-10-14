package io.github.h_sib.declared.sensors

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.h_sib.declared.core.sensor.InstallOrigin
import io.github.h_sib.declared.core.sensor.PackageFacts
import io.github.h_sib.declared.core.sensor.PackageFactsSource
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.WatchedPermissions

/**
 * Provenance logic ported from the lab probe (lab V2, V8; the fields it read were checked against `dumpsys package`),
 * plus the watched permissions (lab P9). Only package facts are collected here. Deciding what they mean is the pure
 * TierClassifier in core. What this app can list at all is limited by the `<queries>` entries (blind spot B1).
 */
class AndroidPackageFactsSource(private val context: Context) : PackageFactsSource {

    private val flags = PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.GET_PERMISSIONS

    override fun facts(pkg: String): ReadResult<PackageFacts> = guarded {
        val pm = context.packageManager
        toFacts(pm, Compat.packageInfo(pm, pkg, flags), hasLauncherIcon(pm, pkg))
    }

    /** Every visible package. One launcher query for all of them: a query per package would be a thousand calls on a big phone. */
    override fun all(): ReadResult<List<PackageFacts>> = guarded {
        val pm = context.packageManager
        val launchers = Compat.activities(pm, Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER))
            .mapNotNull { it.activityInfo?.packageName }.toHashSet()
        Compat.installedPackages(pm, flags).mapNotNull { info ->
            try {
                toFacts(pm, info, info.packageName in launchers)
            } catch (e: RuntimeException) {
                null // one package that cannot be read must not hide the rest; it simply is not listed
            }
        }
    }

    private fun toFacts(pm: PackageManager, info: PackageInfo, launcher: Boolean): PackageFacts {
        val pkg = info.packageName
        val app = checkNotNull(info.applicationInfo) { "no ApplicationInfo for $pkg" }

        val platformSigned: Boolean? = try {
            pm.checkSignatures("android", pkg) == PackageManager.SIGNATURE_MATCH
        } catch (e: RuntimeException) {
            null
        }

        var installing: String? = null
        var initiating: String? = null
        var origin: InstallOrigin? = null
        var updateOwner: String? = null
        var readable = true
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val source = pm.getInstallSourceInfo(pkg)
                installing = source.installingPackageName
                initiating = source.initiatingPackageName
                if (Build.VERSION.SDK_INT >= 33) origin = originOf(source.packageSource)
                if (Build.VERSION.SDK_INT >= 34) updateOwner = source.updateOwnerPackageName
            } else {
                @Suppress("DEPRECATION")
                installing = pm.getInstallerPackageName(pkg)
            }
        } catch (e: RuntimeException) {
            readable = false
        } catch (e: PackageManager.NameNotFoundException) {
            readable = false
        }

        val requested = info.requestedPermissions.orEmpty()
        val grantFlags = info.requestedPermissionsFlags
        val requestedWatched = requested.filter { it in WatchedPermissions.ALL }.toSet()
        val grantedWatched = if (grantFlags == null) {
            emptySet()
        } else {
            requested.indices
                .filter { it < grantFlags.size && grantFlags[it] and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }
                .map { requested[it] }.filter { it in WatchedPermissions.ALL }.toSet()
        }

        return PackageFacts(
            pkg = pkg,
            label = pm.getApplicationLabel(app).toString(),
            uid = app.uid,
            system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            updatedSystem = app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
            platformSigned = platformSigned,
            installingPackage = installing,
            initiatingPackage = initiating,
            installOrigin = origin,
            updateOwner = updateOwner,
            installRecordReadable = readable,
            signerSha256 = signers(info),
            hasLauncherIcon = launcher,
            requestedWatched = requestedWatched,
            grantedWatched = grantedWatched,
        )
    }

    private fun originOf(source: Int): InstallOrigin = when (source) {
        1 -> InstallOrigin.OTHER
        2 -> InstallOrigin.STORE
        3 -> InstallOrigin.LOCAL_FILE
        4 -> InstallOrigin.DOWNLOADED_FILE
        else -> InstallOrigin.UNSPECIFIED
    }

    /** SHA-256 of each current signing certificate, lowercase hex (the app identity used for baseline review). */
    private fun signers(info: PackageInfo): List<String> {
        val certs = info.signingInfo?.apkContentsSigners ?: return emptyList()
        return certs.map { Hashing.sha256Hex(it.toByteArray()) }.sorted()
    }

    private fun hasLauncherIcon(pm: PackageManager, pkg: String): Boolean {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg)
        return Compat.activities(pm, intent).isNotEmpty()
    }
}
