package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.model.Availability
import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.FactKind
import io.github.h_sib.declared.core.model.Finding
import io.github.h_sib.declared.core.model.SignalId
import io.github.h_sib.declared.core.model.Signals
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.sensor.AdvancedProtectionState
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.Transport
import io.github.h_sib.declared.core.sensor.UserCaReading
import io.github.h_sib.declared.core.sensor.WifiReading
import io.github.h_sib.declared.core.sensor.WifiSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Contract tests for device facts, network facts and the findings made from them. The fixed clock is 2026-10-09. */
class ConditionsTest {

    private val today = LocalDate.parse("2026-10-09")

    private fun report(
        device: io.github.h_sib.declared.core.sensor.DeviceFactsReading = healthyDevice(),
        network: ReadResult<io.github.h_sib.declared.core.sensor.ActiveNetwork> = QUIET_NETWORK,
        wifi: ReadResult<WifiReading> = NOT_ON_WIFI,
        cas: ReadResult<UserCaReading> = NO_USER_CAS,
    ): Report = builder(sources(device = device, network = network, wifi = wifi, userCas = cas)).build(34)

    private fun conditionFindings(r: Report): Map<String, Finding> = r.findings.filter { it.id.startsWith("condition:") }.associateBy { it.id }

    private fun fact(r: Report, kind: FactKind) = r.facts.single { it.kind == kind }

    private fun status(r: Report, id: SignalId) = r.signals.first { it.id == id }.availability

    private fun patchedDaysAgo(days: Long) = healthyDevice(patch = ReadResult.Ok(today.minusDays(days).toString()))

    // ------------------------------------------------------------ the quiet phone

    @Test
    fun aHealthyPhoneProducesNoConditionFindingsButEveryRow() {
        val r = report()
        assertTrue(conditionFindings(r).isEmpty())
        assertEquals(FactKind.entries.toList(), r.facts.map { it.kind })
        assertTrue(r.facts.all { it.summary.isNotBlank() })
    }

    // ------------------------------------------------------------ patch level

    @Test
    fun theSecurityPatchThresholdsAreExactlyWhereTheyAreDocumented() {
        fun attention(days: Long) = conditionFindings(report(device = patchedDaysAgo(days)))["condition:patch-level"]?.attention
        assertNull(attention(0))
        assertNull(attention(179))
        assertEquals(Attention.LOOK, attention(180))
        assertEquals(Attention.LOOK, attention(364))
        assertEquals(Attention.LOOK_FIRST, attention(365))
        assertEquals(Attention.LOOK_FIRST, attention(1500))
    }

    @Test
    fun theOldPatchFindingStatesItsMonthsAndWhatItCannotTell() {
        val f = conditionFindings(report(device = patchedDaysAgo(400)))["condition:patch-level"]!!
        assertTrue(f.title, f.title.contains("about 13 months ago"))
        assertTrue(f.meaning.contains("end of its support"))
        assertEquals(Confidence.HIGH, f.confidence)
        assertTrue(f.whatItCannotTell.size >= 3)
        assertEquals("DEVICE_INFO", f.settings.target.name)
    }

    @Test
    fun aPatchDateInTheFutureOrUnreadableMakesNoFindingAndSaysWhy() {
        val future = report(device = healthyDevice(patch = ReadResult.Ok("2027-01-01")))
        assertTrue(conditionFindings(future).isEmpty())
        assertTrue(fact(future, FactKind.PATCH_LEVEL).summary.contains("in the future"))

        for (bad in listOf("", "   ", null, "October 2026", "2026-13-40")) {
            val r = report(device = healthyDevice(patch = ReadResult.Ok(bad)))
            assertTrue("for '$bad'", conditionFindings(r).isEmpty())
            assertTrue("for '$bad'", status(r, Signals.PATCH_LEVEL) is Availability.Degraded)
        }
    }

    @Test
    fun aRefusedPatchReadIsUnavailable() {
        val r = report(device = healthyDevice(patch = ReadResult.Refused("nope")))
        assertTrue(status(r, Signals.PATCH_LEVEL) is Availability.Unavailable)
        assertTrue(fact(r, FactKind.PATCH_LEVEL).summary.contains("not readable"))
    }

    // ------------------------------------------------------------ screen lock and debugging

    @Test
    fun noScreenLockIsLookFirstAndALockIsNot() {
        val none = conditionFindings(report(device = healthyDevice(lock = ReadResult.Ok(false))))["condition:screen-lock"]!!
        assertEquals(Attention.LOOK_FIRST, none.attention)
        assertEquals("SECURITY", none.settings.target.name)
        assertTrue(conditionFindings(report()).isEmpty())
    }

    @Test
    fun debuggingSwitchesMakeFindingsOnlyWhenOn() {
        val r = report(device = healthyDevice(usb = ReadResult.Ok(1), wireless = ReadResult.Ok(1), developer = ReadResult.Ok(1)))
        val f = conditionFindings(r)
        assertEquals(Attention.LOOK, f["condition:usb-debugging"]!!.attention)
        assertEquals(Attention.LOOK_FIRST, f["condition:wireless-debugging"]!!.attention)
        assertEquals(Attention.NOTE, f["condition:developer-options"]!!.attention)
        assertTrue(fact(r, FactKind.USB_DEBUGGING).summary.endsWith("on."))
    }

    @Test
    fun aSettingThatDoesNotExistIsNotSetAndNeverOff() {
        val r = report(device = healthyDevice(usb = ReadResult.Ok(null)))
        assertTrue(fact(r, FactKind.USB_DEBUGGING).summary.contains("not set"))
        assertEquals(Availability.Available, status(r, Signals.USB_DEBUGGING))
        assertTrue(conditionFindings(r).isEmpty())
    }

    @Test
    fun anUnexpectedSettingValueIsDegradedNotGuessed() {
        val r = report(device = healthyDevice(developer = ReadResult.Ok(7)))
        assertTrue(status(r, Signals.DEVELOPER_OPTIONS) is Availability.Degraded)
        assertTrue(conditionFindings(r).isEmpty())
    }

    @Test
    fun oneRefusedPartDoesNotEraseTheOthers() {
        // Lab defect D-002 in the product: every part is read and reported on its own.
        val r = report(
            device = healthyDevice(patch = ReadResult.Refused("a"), lock = ReadResult.Failed("b"), usb = ReadResult.Ok(1)),
        )
        assertTrue(status(r, Signals.PATCH_LEVEL) is Availability.Unavailable)
        assertTrue(status(r, Signals.SCREEN_LOCK) is Availability.Unavailable)
        assertEquals(Availability.Available, status(r, Signals.USB_DEBUGGING))
        assertEquals(setOf("condition:usb-debugging"), conditionFindings(r).keys)
    }

    // ------------------------------------------------------------ Advanced Protection

    @Test
    fun advancedProtectionIsAFactInEveryStateAndNeverAFinding() {
        val cases = listOf(
            AdvancedProtectionState.Known(true) to Availability.Available,
            AdvancedProtectionState.Known(false) to Availability.Available,
            AdvancedProtectionState.Unsupported to Availability.Unavailable("Android 15 and older have no Advanced Protection mode."),
        )
        for ((state, expected) in cases) {
            val r = report(device = healthyDevice(advanced = state))
            assertEquals(expected, status(r, Signals.ADVANCED_PROTECTION))
            assertTrue(conditionFindings(r).isEmpty())
        }
        assertTrue(status(report(device = healthyDevice(advanced = AdvancedProtectionState.NotPermitted)), Signals.ADVANCED_PROTECTION) is Availability.Unavailable)
        assertTrue(status(report(device = healthyDevice(advanced = AdvancedProtectionState.Failed("x"))), Signals.ADVANCED_PROTECTION) is Availability.Unavailable)
        assertTrue(fact(report(device = healthyDevice(advanced = AdvancedProtectionState.Known(true))), FactKind.ADVANCED_PROTECTION).summary.contains("on"))
    }

    // ------------------------------------------------------------ the network

    @Test
    fun noActiveNetworkIsAvailableAndNeedsNoFinding() {
        val r = report(network = network(present = false, transports = emptySet()))
        assertTrue(fact(r, FactKind.CONNECTION).summary.contains("not connected"))
        assertEquals(Availability.Available, status(r, Signals.NETWORK_ACTIVE))
        assertTrue(conditionFindings(r).isEmpty())
    }

    @Test
    fun openWifiIsLookAndWepIsLookFirstAndOthersAreOnlyFacts() {
        fun security(s: WifiSecurity) = conditionFindings(
            report(network = network(transports = setOf(Transport.WIFI)), wifi = ReadResult.Ok(WifiReading(true, s))),
        )["condition:wifi-security"]?.attention
        assertEquals(Attention.LOOK, security(WifiSecurity.OPEN))
        assertEquals(Attention.LOOK_FIRST, security(WifiSecurity.WEP))
        for (quiet in listOf(WifiSecurity.WPA_PSK, WifiSecurity.WPA3_PSK, WifiSecurity.ENTERPRISE, WifiSecurity.OWE, WifiSecurity.WAPI, WifiSecurity.OTHER, WifiSecurity.UNKNOWN)) {
            assertNull(quiet.name, security(quiet))
        }
    }

    @Test
    fun wifiSecurityUnreadableBelowAndroid12IsUnavailableWithTheReason() {
        val note = "On Android 11 and older Android tells apps the security type only with the location permission."
        val r = report(wifi = ReadResult.Ok(WifiReading(connected = true, security = null, note = note)))
        val a = status(r, Signals.WIFI_SECURITY) as Availability.Unavailable
        assertEquals(note, a.reason)
        assertTrue(conditionFindings(r).isEmpty())
    }

    @Test
    fun notBeingOnWifiIsNotAnError() {
        val r = report(wifi = ReadResult.Ok(WifiReading(connected = false, security = null)))
        assertEquals(Availability.Available, status(r, Signals.WIFI_SECURITY))
        assertTrue(fact(r, FactKind.WIFI_SECURITY).summary.contains("not connected"))
    }

    @Test
    fun aProxyIsLookAndNoProxyIsQuiet() {
        val f = conditionFindings(report(network = network(proxy = "192.168.1.5:8080")))["condition:http-proxy"]!!
        assertEquals(Attention.LOOK, f.attention)
        assertTrue(f.meaning.contains("can see, and change"))
        assertTrue(conditionFindings(report()).isEmpty())
    }

    @Test
    fun theConnectionRowSaysWhatAndroidCouldConfirm() {
        fun text(vararg args: Pair<String, Any>): String {
            val n = network(
                transports = setOf(Transport.WIFI),
                validated = args.toMap()["validated"] as? Boolean ?: true,
                captive = args.toMap()["captive"] as? Boolean ?: false,
            )
            return fact(report(network = n), FactKind.CONNECTION).summary
        }
        assertTrue(text().contains("Android confirmed"))
        assertTrue(text("validated" to false).contains("could not confirm"))
        assertTrue(text("captive" to true, "validated" to false).contains("sign in"))
    }

    @Test
    fun aVpnIsAFactWithItsMeaningAndPrivateDnsHasThreeStates() {
        val vpn = report(network = network(transports = setOf(Transport.WIFI, Transport.VPN)))
        assertTrue(fact(vpn, FactKind.VPN).summary.contains("active"))
        assertTrue(fact(report(), FactKind.VPN).summary.contains("none"))
        assertTrue(fact(report(network = network(privateDns = true, privateDnsName = "dns.example")), FactKind.PRIVATE_DNS).summary.contains("dns.example"))
        assertTrue(fact(report(network = network(privateDns = false)), FactKind.PRIVATE_DNS).summary.contains("not in use"))
        val unknown = report(network = network(privateDns = null))
        assertTrue(fact(unknown, FactKind.PRIVATE_DNS).availability is Availability.Degraded)
    }

    @Test
    fun aRefusedNetworkReadMakesEveryNetworkRowUnavailable() {
        val r = report(network = ReadResult.Refused("ACCESS_NETWORK_STATE missing"))
        for (kind in listOf(FactKind.CONNECTION, FactKind.VPN, FactKind.PRIVATE_DNS, FactKind.HTTP_PROXY)) {
            assertTrue(kind.name, fact(r, kind).availability is Availability.Unavailable)
        }
        assertTrue(status(r, Signals.HTTP_PROXY) is Availability.Unavailable)
        assertTrue(conditionFindings(r).isEmpty())
    }

    // ------------------------------------------------------------ added certificate authorities

    @Test
    fun addedCertificateAuthoritiesAreAMediumConfidenceFindingBecauseOnlyOneEmulatorWasChecked() {
        val one = conditionFindings(report(cas = ReadResult.Ok(UserCaReading(listOf("ab".repeat(32))))))["condition:user-cas"]!!
        assertEquals("1 extra certificate authority is trusted, which could let someone read encrypted traffic", one.title)
        assertEquals(Confidence.MEDIUM, one.confidence)
        assertEquals(Attention.LOOK, one.attention)
        assertTrue(one.claim.single().limits.any { it.contains("one emulator") && it.contains("not been checked on a real phone") })
        val two = conditionFindings(report(cas = ReadResult.Ok(UserCaReading(listOf("aa", "bb")))))["condition:user-cas"]!!
        assertEquals("2 extra certificate authorities are trusted, which could let someone read encrypted traffic", two.title)
    }

    @Test
    fun noAddedAuthoritiesStatesHowFarTheReadingWasChecked() {
        val none = report()
        assertTrue(fact(none, FactKind.USER_CAS).summary.contains("one emulator only"))
        assertFalse(conditionFindings(none).containsKey("condition:user-cas"))
        assertTrue(status(report(cas = ReadResult.Failed("KeyStoreException")), Signals.USER_CAS) is Availability.Unavailable)
    }

    // ------------------------------------------------------------ the whole report

    @Test
    fun everyNewSignalHasAStatusLine() {
        val ids = report().signals.map { it.id }.toSet()
        for (id in listOf(
            Signals.PATCH_LEVEL, Signals.SCREEN_LOCK, Signals.USB_DEBUGGING, Signals.WIRELESS_DEBUGGING, Signals.DEVELOPER_OPTIONS,
            Signals.ADVANCED_PROTECTION, Signals.NETWORK_ACTIVE, Signals.PRIVATE_DNS, Signals.HTTP_PROXY, Signals.WIFI_SECURITY, Signals.USER_CAS,
        )) assertTrue("missing status for $id", id in ids)
    }

    @Test
    fun conditionFindingsSitUnderTheirOwnSectionAndSortWithTheRest() {
        val r = report(
            device = healthyDevice(lock = ReadResult.Ok(false)),
            network = network(transports = setOf(Transport.WIFI)),
            wifi = ReadResult.Ok(WifiReading(true, WifiSecurity.OPEN)),
        )
        assertEquals(setOf("This phone's settings", "The network you are on"), conditionFindings(r).values.map { it.section }.toSet())
        assertEquals(listOf(Attention.LOOK_FIRST, Attention.LOOK), r.findings.map { it.attention })
    }
}
