package lab.probe

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipFile

/** V-series: package visibility, provenance, signing and trust tiers. */
object Packages {

    private val STORES = setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps",
        "com.amazon.venezia",
        "com.huawei.appmarket",
    )

    @Suppress("DEPRECATION")
    private fun servicePackages(pm: PackageManager, action: String): List<String> =
        pm.queryIntentServices(Intent(action), 0).mapNotNull { it.serviceInfo?.packageName }.distinct().sorted()

    @Suppress("DEPRECATION")
    private fun receiverPackages(pm: PackageManager, action: String): List<String> =
        pm.queryBroadcastReceivers(Intent(action), 0).mapNotNull { it.activityInfo?.packageName }.distinct().sorted()

    @Suppress("DEPRECATION")
    private fun launcherPackages(pm: PackageManager): Set<String> =
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .mapNotNull { it.activityInfo?.packageName }.toSet()

    private fun tier(system: Boolean, updated: Boolean, platform: Boolean?, installer: String?, initiating: String?): String = when {
        platform == true -> "platform"
        system || updated -> "system"
        installer in STORES -> "store"
        installer == null && initiating == null -> "adb-or-unknown"
        else -> "sideload"
    }

    private fun leInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun leLong(b: ByteArray, o: Int): Long =
        (leInt(b, o).toLong() and 0xFFFFFFFFL) or ((leInt(b, o + 4).toLong() and 0xFFFFFFFFL) shl 32)

    /** Lists the IDs found in the APK Signing Block (0x7109871a = v2, 0xf05368c0 = v3, 0x1b93ad61 = v3.1). */
    private fun signingBlockIds(f: File): List<String> {
        return RandomAccessFile(f, "r").use { raf ->
            val length = raf.length()
            val tail = minOf(length, 65557L).toInt()
            val buf = ByteArray(tail)
            raf.seek(length - tail)
            raf.readFully(buf)
            var eocd = -1
            for (i in tail - 22 downTo 0) {
                if (buf[i] == 0x50.toByte() && buf[i + 1] == 0x4b.toByte() && buf[i + 2] == 0x05.toByte() && buf[i + 3] == 0x06.toByte()) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) return emptyList()
            val centralDir = leInt(buf, eocd + 16).toLong() and 0xFFFFFFFFL
            if (centralDir < 32) return emptyList()
            val magic = ByteArray(16)
            raf.seek(centralDir - 16)
            raf.readFully(magic)
            if (String(magic, Charsets.ISO_8859_1) != "APK Sig Block 42") return emptyList()
            val sizeBytes = ByteArray(8)
            raf.seek(centralDir - 24)
            raf.readFully(sizeBytes)
            val blockSize = leLong(sizeBytes, 0)
            val start = centralDir - blockSize - 8
            if (start < 0) return emptyList()
            var pos = start + 8
            val end = centralDir - 24
            val ids = mutableListOf<String>()
            val pair = ByteArray(12)
            while (pos + 12 <= end) {
                raf.seek(pos)
                raf.readFully(pair)
                val l = leLong(pair, 0)
                ids.add("0x" + java.lang.Long.toHexString(leInt(pair, 8).toLong() and 0xFFFFFFFFL))
                if (l <= 0) break
                pos += 8 + l
            }
            ids.toList()
        }
    }

    private fun row(pm: PackageManager, pi: PackageInfo, launchers: Set<String>, special: Map<String, List<String>>): JSONObject {
        val ai = pi.applicationInfo!!
        val pkg = pi.packageName
        val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val updatedSystem = (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val platformSigned: Boolean? = try {
            pm.checkSignatures("android", pkg) == PackageManager.SIGNATURE_MATCH
        } catch (e: Exception) {
            null
        }

        var installer: String? = null
        var initiating: String? = null
        var originating: String? = null
        var packageSource: Int? = null
        var updateOwner: String? = null
        var installError: String? = null
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val s = pm.getInstallSourceInfo(pkg)
                installer = s.installingPackageName
                initiating = s.initiatingPackageName
                originating = s.originatingPackageName
                if (Build.VERSION.SDK_INT >= 33) packageSource = s.packageSource
                if (Build.VERSION.SDK_INT >= 34) updateOwner = s.updateOwnerPackageName
            } else {
                @Suppress("DEPRECATION")
                installer = pm.getInstallerPackageName(pkg)
            }
        } catch (e: Throwable) {
            installError = e.javaClass.simpleName
        }

        val signers = pi.signingInfo?.apkContentsSigners?.map { sha256Hex(it.toByteArray()) }

        return jo(
            "package" to rd(pkg),
            "system" to system,
            "updatedSystem" to updatedSystem,
            "platformSigned" to platformSigned,
            "tier" to tier(system, updatedSystem, platformSigned, installer, initiating),
            "installer" to (if (installer in STORES) installer else rd(installer)),
            "initiating" to rd(initiating),
            "originating" to rd(originating),
            "packageSource" to packageSource,
            "updateOwner" to rd(updateOwner),
            "installError" to installError,
            "signerSha256" to signers,
            "targetSdk" to ai.targetSdkVersion,
            "minSdk" to ai.minSdkVersion,
            "debuggable" to ((ai.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0),
            "cleartext" to ((ai.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC) != 0),
            "firstInstall" to pi.firstInstallTime,
            "lastUpdate" to pi.lastUpdateTime,
            "hasLauncher" to (pkg in launchers),
            "capabilities" to (special[pkg] ?: emptyList()),
        )
    }

    fun collect(ctx: Context): List<Reading> {
        val pm = ctx.packageManager
        val a11y = servicePackages(pm, "android.accessibilityservice.AccessibilityService")
        val notif = servicePackages(pm, "android.service.notification.NotificationListenerService")
        val ime = servicePackages(pm, "android.view.InputMethod")
        val admin = receiverPackages(pm, "android.app.action.DEVICE_ADMIN_ENABLED")
        val launchers = launcherPackages(pm)

        val special = HashMap<String, MutableList<String>>()
        fun mark(list: List<String>, name: String) = list.forEach { special.getOrPut(it) { mutableListOf() }.add(name) }
        mark(a11y, "accessibility")
        mark(notif, "notificationListener")
        mark(ime, "inputMethod")
        mark(admin, "deviceAdminReceiver")

        return listOf(
            reading("V1", "Capability queries vs installed packages", 1) {
                jo(
                    "accessibility" to a11y.map { rd(it) },
                    "notificationListener" to notif.map { rd(it) },
                    "inputMethod" to ime.map { rd(it) },
                    "deviceAdminReceivers" to admin.map { rd(it) },
                    // V1b: receivers of broadcasts that hidden apps commonly declare.
                    "bootReceivers" to receiverPackages(pm, Intent.ACTION_BOOT_COMPLETED).map { rd(it) },
                    "smsReceivers" to receiverPackages(pm, "android.provider.Telephony.SMS_RECEIVED").map { rd(it) },
                    "launcherPackageCount" to launchers.size,
                    "installedPackagesVisible" to pm.getInstalledPackages(0).size,
                    "holdsQueryAllPackages" to
                        (ctx.checkSelfPermission("android.permission.QUERY_ALL_PACKAGES") == PackageManager.PERMISSION_GRANTED),
                )
            },
            reading("V2", "Package rows: provenance, signing, trust tier (V3, V4, V6, V7, V8)", 1) {
                val all = pm.installedCompat(PackageManager.GET_SIGNING_CERTIFICATES)
                val rows = mutableListOf<JSONObject>()
                val tierCounts = HashMap<String, Int>()
                for (pi in all) {
                    if (pi.applicationInfo == null) continue
                    val r = row(pm, pi, launchers, special)
                    val t = r.getString("tier")
                    tierCounts[t] = (tierCounts[t] ?: 0) + 1
                    val interesting = !r.getBoolean("system") && t != "platform"
                    if (interesting || special.containsKey(pi.packageName) || pi.packageName.startsWith("lab.fixture.")) rows.add(r)
                }
                jo("visiblePackages" to all.size, "tierCounts" to tierCounts, "rowCount" to rows.size, "rows" to rows)
            },
            reading("V4", "APK readability and signing block", 1) {
                val rows = JSONObject()
                val candidates = pm.installedCompat(0)
                    .filter { it.packageName.startsWith("lab.fixture.") || ((it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .take(8)
                for (pi in candidates) {
                    val f = File(pi.applicationInfo!!.sourceDir)
                    val info = jo("canRead" to f.canRead(), "bytes" to f.length())
                    try {
                        ZipFile(f).use { z ->
                            val names = z.entries().asSequence().map { it.name }.toList()
                            info.put("entries", names.size)
                            info.put("metaInfSignatureFiles", names.count {
                                it.startsWith("META-INF/") && (it.endsWith(".SF") || it.endsWith(".RSA") || it.endsWith(".DSA") || it.endsWith(".EC"))
                            })
                        }
                        info.put("signingBlockIds", wrap(signingBlockIds(f)))
                    } catch (e: Throwable) {
                        info.put("error", e.javaClass.simpleName + ": " + e.message)
                    }
                    rows.put(rd(pi.packageName) ?: pi.packageName, info)
                }
                rows
            },
        )
    }
}
