package io.github.h_sib.declared.core.tier

import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.sensor.InstallOrigin
import io.github.h_sib.declared.core.sensor.PackageFacts

/** Where an app came from, as far as the install record can say. See DESIGN.md section 6. */
enum class Tier { PLATFORM, SYSTEM, STORE, OEM_OR_CARRIER_SETUP, SIDELOAD, ADB, UNKNOWN }

data class TierVerdict(
    val tier: Tier,
    val confidence: Confidence,
    /** One plain sentence, shown to the user. Says "installed by X", never "suspicious". */
    val reason: String,
    val limits: List<String>,
)

/**
 * Installer allow-lists are data, not code (DESIGN.md section 6): package name to the name shown to the user.
 * They start small and grow from observations. A signed, hash-listed rule pack replaces the bundled copy in Stage 2.
 */
data class InstallerDirectory(
    val version: String,
    val stores: Map<String, String>,
    val oemOrCarrierSetup: Map<String, String>,
    /** Installers and initiators that mean "this came from a file or a download". */
    val fileSources: Map<String, String>,
) {
    companion object {
        const val SHELL = "com.android.shell"

        /**
         * The oemOrCarrierSetup list is empty on purpose. The false positive that motivated the tier (a carrier's setup
         * flow installing an ordinary app on the real test phone) was recorded without the installer's name, so there is
         * no verified entry to add yet. An unlisted installer therefore lands in UNKNOWN at LOW confidence, which is the
         * safe wording ("not recognized"), not in SIDELOAD.
         */
        val BUNDLED = InstallerDirectory(
            version = "2026-10-09",
            stores = mapOf(
                "com.android.vending" to "Google Play",
                "com.sec.android.app.samsungapps" to "Galaxy Store",
                "com.amazon.venezia" to "Amazon Appstore",
                "com.huawei.appmarket" to "Huawei AppGallery",
                "org.fdroid.fdroid" to "F-Droid",
            ),
            oemOrCarrierSetup = emptyMap(),
            fileSources = mapOf(
                "com.google.android.packageinstaller" to "the Android package installer",
                "com.android.packageinstaller" to "the Android package installer",
                "com.google.android.documentsui" to "a file manager",
                "com.android.documentsui" to "a file manager",
                "com.google.android.apps.nbu.files" to "Files by Google",
                "com.android.chrome" to "Chrome",
                "org.mozilla.firefox" to "Firefox",
                "org.telegram.messenger" to "Telegram",
                "com.whatsapp" to "WhatsApp",
            ),
        )
    }
}

/**
 * Pure function of the install record. Total: every input maps to exactly one tier (tested over a full grid).
 * Rule order matters and is the order of the table in DESIGN.md section 6.
 */
class TierClassifier(private val directory: InstallerDirectory = InstallerDirectory.BUNDLED) {

    fun classify(f: PackageFacts): TierVerdict {
        if (f.platformSigned == true) {
            return TierVerdict(
                Tier.PLATFORM, Confidence.HIGH,
                "Signed with the same key as Android itself.",
                listOf("This says who signed the app, not what the app does."),
            )
        }
        if (f.system || f.updatedSystem) {
            return TierVerdict(
                Tier.SYSTEM, Confidence.HIGH,
                "Part of the phone's built-in software, or an update to it.",
                listOf("Built-in does not mean harmless; it means the phone's maker or carrier chose to ship it."),
            )
        }
        if (!f.installRecordReadable) return withoutRecord()

        val installer = f.installingPackage
        val initiator = f.initiatingPackage
        val origin = f.installOrigin

        if (installer == InstallerDirectory.SHELL || initiator == InstallerDirectory.SHELL) {
            return TierVerdict(
                Tier.ADB, Confidence.MEDIUM,
                "Installed from a computer or the command line (adb).",
                listOf("Developers do this on purpose; so can anyone with a cable and an unlocked, debugging-enabled phone."),
            )
        }
        if (installer != null && installer == f.pkg) {
            return unknown("This app installed itself, so no outside installer is recorded.")
        }

        directory.stores[installer]?.let { name ->
            return TierVerdict(
                Tier.STORE, Confidence.MEDIUM,
                "Installed by $name.",
                listOf(
                    "The installer name is what Android recorded. This app has not tested whether an installer can claim another store's name.",
                    "A store listing is not a safety guarantee.",
                ),
            )
        }
        directory.oemOrCarrierSetup[installer]?.let { name ->
            return TierVerdict(
                Tier.OEM_OR_CARRIER_SETUP, Confidence.MEDIUM,
                "Installed by $name, part of the phone's setup.",
                listOf("Setup flows install ordinary apps too; this says how it arrived, not who wrote it."),
            )
        }

        val fileSource = directory.fileSources[installer] ?: directory.fileSources[initiator]
        val fileOrigin = origin == InstallOrigin.LOCAL_FILE || origin == InstallOrigin.DOWNLOADED_FILE
        if (fileSource != null || fileOrigin) {
            val how = fileSource?.let { "from a file or download, through $it" } ?: "from a file or download"
            return TierVerdict(
                Tier.SIDELOAD, Confidence.MEDIUM,
                "Installed $how, not from a store.",
                listOf("Many people install apps this way for good reasons. Where the file came from is not recorded."),
            )
        }

        return if (installer == null && initiator == null) {
            unknown(
                "No installer is recorded for this app.",
                "Android 13 and older record no installer for apps installed with adb, and some device-setup flows record none either.",
            )
        } else {
            unknown("Installed by ${installer ?: initiator}, which this app does not recognize.")
        }
    }

    /** The verdict for an app whose install record this app could not read at all. */
    fun withoutRecord(): TierVerdict = unknown("Android did not give this app the install record for that app.")

    private fun unknown(reason: String, vararg extra: String) = TierVerdict(
        Tier.UNKNOWN, Confidence.LOW, reason,
        listOf("This app cannot tell where the app came from, so it does not guess.") + extra,
    )
}
