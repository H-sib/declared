package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.sensor.InstallOrigin
import io.github.h_sib.declared.core.tier.InstallerDirectory
import io.github.h_sib.declared.core.tier.Tier
import io.github.h_sib.declared.core.tier.TierClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TierClassifierTest {
    private val classifier = TierClassifier()

    @Test
    fun platformSignedWinsOverEverything() {
        val v = classifier.classify(facts("p", platformSigned = true, system = false, installing = null))
        assertEquals(Tier.PLATFORM, v.tier)
        assertEquals(Confidence.HIGH, v.confidence)
    }

    @Test
    fun systemAndUpdatedSystemAreSystem() {
        assertEquals(Tier.SYSTEM, classifier.classify(facts("p", system = true)).tier)
        assertEquals(Tier.SYSTEM, classifier.classify(facts("p", updatedSystem = true)).tier)
    }

    @Test
    fun knownStoreIsStoreButOnlyMediumBecauseSpoofingIsUntested() {
        val v = classifier.classify(facts("p", installing = "com.android.vending"))
        assertEquals(Tier.STORE, v.tier)
        assertEquals(Confidence.MEDIUM, v.confidence)
        assertTrue(v.reason, v.reason.contains("Google Play"))
        assertTrue(v.limits.any { it.contains("claim another store") })
    }

    @Test
    fun shellInitiatorIsAdbEvenWhenNoInstallerIsRecorded() {
        // Android 14 and later: adb installs show the shell as initiating package (lab Q11).
        assertEquals(Tier.ADB, classifier.classify(facts("p", installing = null, initiating = "com.android.shell")).tier)
        assertEquals(Tier.ADB, classifier.classify(facts("p", installing = "com.android.shell", initiating = null)).tier)
    }

    @Test
    fun noInstallerAtAllIsUnknownNotSideload() {
        // Android 13 and older adb installs, and some setup flows, record nothing (lab Q11).
        val v = classifier.classify(facts("p", installing = null, initiating = null))
        assertEquals(Tier.UNKNOWN, v.tier)
        assertEquals(Confidence.LOW, v.confidence)
    }

    @Test
    fun anUnlistedInstallerIsUnknownAndNeverWordedAsSideloaded() {
        // The false positive from the real phone: a carrier setup flow installing an ordinary app.
        val v = classifier.classify(facts("p", installing = "jp.example.carrier.setup"))
        assertEquals(Tier.UNKNOWN, v.tier)
        assertFalse(v.reason.lowercase().contains("sideload"))
    }

    @Test
    fun aListedCarrierSetupInstallerIsItsOwnTier() {
        val directory = InstallerDirectory.BUNDLED.copy(oemOrCarrierSetup = mapOf("jp.example.carrier.setup" to "the carrier setup app"))
        val v = TierClassifier(directory).classify(facts("p", installing = "jp.example.carrier.setup"))
        assertEquals(Tier.OEM_OR_CARRIER_SETUP, v.tier)
    }

    @Test
    fun packageInstallerOrBrowserInitiatorIsSideload() {
        assertEquals(Tier.SIDELOAD, classifier.classify(facts("p", installing = "com.google.android.packageinstaller", initiating = "com.android.chrome")).tier)
        assertEquals(Tier.SIDELOAD, classifier.classify(facts("p", installing = "x.unknown", initiating = "org.telegram.messenger")).tier)
    }

    @Test
    fun androidThirteenFileOriginMakesItSideloadEvenWithAnUnknownInstaller() {
        assertEquals(Tier.SIDELOAD, classifier.classify(facts("p", installing = "x.unknown", origin = InstallOrigin.DOWNLOADED_FILE)).tier)
        assertEquals(Tier.SIDELOAD, classifier.classify(facts("p", installing = "x.unknown", origin = InstallOrigin.LOCAL_FILE)).tier)
    }

    @Test
    fun anUnreadableInstallRecordIsUnknown() {
        assertEquals(Tier.UNKNOWN, classifier.classify(facts("p", readable = false)).tier)
    }

    @Test
    fun aSelfInstalledAppHasNoOutsideInstaller() {
        assertEquals(Tier.UNKNOWN, classifier.classify(facts("p", installing = "p")).tier)
    }

    @Test
    fun aFailedPlatformSignatureCheckDoesNotBlockTheOtherRules() {
        assertEquals(Tier.STORE, classifier.classify(facts("p", platformSigned = null)).tier)
        assertEquals(Tier.SYSTEM, classifier.classify(facts("p", platformSigned = null, system = true)).tier)
    }

    /**
     * Property over the full cross product of the input space: the classifier is total, gives every verdict a
     * reason and limits, and keeps its promises about confidence and wording. 7,056 combinations.
     */
    @Test
    fun everyInputMapsToExactlyOneWellFormedTier() {
        val installers = listOf(null, "com.android.vending", "com.android.shell", "com.google.android.packageinstaller", "x.unknown", "self.pkg", "com.sec.android.app.samsungapps")
        val origins = listOf(null) + InstallOrigin.entries
        var combinations = 0
        for (system in listOf(false, true)) for (updated in listOf(false, true)) for (platform in listOf(true, false, null))
            for (installing in installers) for (initiating in installers) for (origin in origins) for (readable in listOf(true, false)) {
                val f = facts("self.pkg", system = system, updatedSystem = updated, platformSigned = platform, installing = installing, initiating = initiating, origin = origin, readable = readable)
                val v = classifier.classify(f)
                combinations += 1
                val where = "input=$f verdict=$v"
                assertTrue(where, v.reason.isNotBlank())
                assertTrue(where, v.limits.isNotEmpty())
                if (platform == true) assertEquals(where, Tier.PLATFORM, v.tier)
                if (platform != true && (system || updated)) assertEquals(where, Tier.SYSTEM, v.tier)
                if (v.tier == Tier.UNKNOWN) assertEquals(where, Confidence.LOW, v.confidence)
                if (v.tier in setOf(Tier.STORE, Tier.OEM_OR_CARRIER_SETUP, Tier.SIDELOAD, Tier.ADB)) assertTrue(where, v.confidence <= Confidence.MEDIUM)
                if (v.tier != Tier.SIDELOAD) assertFalse(where, v.reason.lowercase().contains("sideload"))
            }
        assertEquals(2 * 2 * 3 * installers.size * installers.size * origins.size * 2, combinations)
    }
}
