package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.blindspots.BlindSpotKind
import io.github.h_sib.declared.core.blindspots.BlindSpots
import io.github.h_sib.declared.core.integrity.ReferenceData
import io.github.h_sib.declared.core.integrity.RevocationList
import io.github.h_sib.declared.core.sensor.AttestationEvidence
import io.github.h_sib.declared.core.sensor.ReadResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class BlindSpotsTest {
    private fun report(api: Int = 34, packages: List<io.github.h_sib.declared.core.sensor.PackageFacts> = listOf(facts("a.b")), integrity: Boolean = false) =
        builder(
            sources(
                packages = FakePackageFactsSource(factsMap(*packages.toTypedArray())),
                attestation = if (integrity) ReadResult.Ok(AttestationEvidence(Fixture.chain(), Fixture.challenge())) else ReadResult.Refused("not exercised"),
                reference = if (integrity) ReadResult.Ok(ReferenceData(listOf(Fixture.chain().last()), RevocationList(emptyMap()), LocalDate.of(2026, 10, 8))) else ReadResult.Refused("not exercised"),
            ),
        ).build(api)

    private val bannedClaims = listOf("is safe", "protects you", "no threats", "nothing to worry", "all clear")

    @Test
    fun theStaticHalfIsAlwaysThereAndNamesTheBigLimits() {
        val ids = BlindSpots.of(report()).filter { it.kind == BlindSpotKind.ALWAYS }.map { it.id }
        assertEquals(
            listOf("other-apps-behavior", "network-traffic", "deep-compromise", "altered-phone", "outside-the-phone", "screen-capture", "play-protect"),
            ids,
        )
    }

    @Test
    fun theHiddenAppLimitIsStatedWithTheCountAndTheMeasurementOnAndroid11AndLater() {
        val s = BlindSpots.of(report(api = 34, packages = listOf(facts("a.b"), facts("c.d")))).first { it.id == "hidden-apps" }
        assertEquals(BlindSpotKind.THIS_PHONE, s.kind)
        assertTrue(s.text.contains("2 apps are visible"))
        assertTrue(s.text.contains("invisible"))
        assertTrue(s.text.contains("restricts"))
        assertTrue(s.measured.orEmpty().let { it.contains("Android 12 to 15") && it.contains("16 and 17") })
    }

    @Test
    fun onAndroidTenEveryAppIsVisibleAndTheMapSaysSoInsteadOfWarning() {
        val s = BlindSpots.of(report(api = 29)).first { it.id == "hidden-apps" }
        assertTrue(s.text.contains("every installed app is visible"))
        assertFalse(s.text.contains("invisible"))
    }

    @Test
    fun theFilteringBoundaryIsAndroid11() {
        assertEquals(30, BlindSpots.FIRST_FILTERED_API)
        assertTrue(BlindSpots.of(report(api = 30)).first { it.id == "hidden-apps" }.text.contains("hides apps"))
        assertTrue(BlindSpots.of(report(api = 29)).first { it.id == "hidden-apps" }.text.contains("every installed app is visible"))
    }

    @Test
    fun everyUnreadableSignalOnThisPhoneAppearsWithItsReasonAndNothingIsDropped() {
        val r = BlindSpots.of(report())
        val unreadable = r.first { it.id == "unreadable-signals" }
        // Advanced Protection (below Android 16) and the hardware check (nothing was provided to judge) are both unreadable here.
        assertTrue(unreadable.text, unreadable.text.contains("Advanced Protection") && unreadable.text.contains("Android 15 and older"))
        assertTrue(unreadable.text, unreadable.text.contains("Hardware key check"))
    }

    @Test
    fun theHardwareCheckEntryChangesWithWhatWasEstablished() {
        val none = BlindSpots.of(report()).first { it.id == "device-side-attestation" }
        assertTrue(none.text.contains("could not establish"))
        val software = BlindSpots.of(report(integrity = true)).first { it.id == "device-side-attestation" }
        assertTrue(software.text.contains("could not establish")) // software keys: degraded, so nothing vouches
    }

    @Test
    fun theBackgroundLimitStatesWhatWasMeasuredAndWhereAndWhatStaysOnTheList() {
        val s = BlindSpots.of(report()).first { it.id == "background-changes" }
        assertTrue(s.measured.orEmpty().contains("one Android 14 emulator") && s.measured.orEmpty().contains("0.06 to 0.3 seconds"))
        assertTrue(s.text.contains("30 seconds"))
        assertTrue(s.text.contains("I have seen these changes"))
    }

    @Test
    fun noEntryClaimsSafetyOrSpeaksAboutScoresAndNoneUsesDashes() {
        for (s in BlindSpots.of(report(integrity = true)) + BlindSpots.of(report(api = 29))) {
            val all = (s.title + " " + s.text + " " + s.measured.orEmpty()).lowercase()
            for (claim in bannedClaims) assertFalse("'$claim' in ${s.id}", all.contains(claim))
            assertFalse("dash in ${s.id}", all.any { it.code == 0x2014 || it.code == 0x2013 })
            assertTrue(s.id, s.text.length > 40)
        }
    }

    @Test
    fun entryIdsAreUniqueSoTheScreenCanKeyThem() {
        val ids = BlindSpots.of(report(integrity = true)).map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }
}
