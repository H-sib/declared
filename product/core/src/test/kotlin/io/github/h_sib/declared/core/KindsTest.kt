package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.admin.DevicePolicy
import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.model.Availability
import io.github.h_sib.declared.core.model.CapabilityKind
import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.Signals
import io.github.h_sib.declared.core.model.Value
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.sensor.DeviceAdminFact
import io.github.h_sib.declared.core.sensor.DeviceAdminReading
import io.github.h_sib.declared.core.sensor.InputMethodFact
import io.github.h_sib.declared.core.sensor.InputMethodReading
import io.github.h_sib.declared.core.sensor.ListenerFact
import io.github.h_sib.declared.core.sensor.NotificationListenerReading
import io.github.h_sib.declared.core.sensor.OpMode
import io.github.h_sib.declared.core.sensor.PackageFacts
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.SpecialAccess
import io.github.h_sib.declared.core.sensor.WatchedPermissions
import io.github.h_sib.declared.core.tier.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sensor contract tests for notification listeners, device admins, keyboards, special access and text-message grants. */
class KindsTest {

    private fun status(report: Report, id: io.github.h_sib.declared.core.model.SignalId) = report.signals.first { it.id == id }.availability

    private val adbFacts = { pkg: String -> facts(pkg, installing = null, initiating = "com.android.shell") }

    // ------------------------------------------------------------ notification listeners

    @Test
    fun anEnabledNotificationListenerFromAStoreIsWorthALook() {
        val l = ListenerFact(component("n.app", "n.app.Listener"), "Mirror")
        val report = builder(
            sources(
                listeners = ReadResult.Ok(NotificationListenerReading(listOf(l), setOf(l.component))),
                packages = FakePackageFactsSource(factsMap(facts("n.app", label = "Notify App"))),
            ),
        ).build(34)
        val f = report.findings.single()
        assertEquals("notification-listener:n.app/n.app.Listener", f.id)
        assertEquals(CapabilityKind.NOTIFICATION_LISTENER, report.holders.single().kind)
        assertEquals(Attention.LOOK, f.attention)
        assertEquals(Confidence.HIGH, f.confidence)
        assertEquals("Notify App can read every notification you get", f.title)
        assertEquals(Availability.Available, status(report, Signals.NOTIFICATION_ENABLED))
    }

    @Test
    fun aDisabledListenerIsListedWithoutAFindingAndAStaleEntryIsReported() {
        val l = ListenerFact(component("n.app", "n.app.Listener"))
        val ghost = component("gone", "gone.Listener")
        val report = builder(
            sources(
                listeners = ReadResult.Ok(NotificationListenerReading(listOf(l), setOf(ghost))),
                packages = FakePackageFactsSource(factsMap(facts("n.app"))),
            ),
        ).build(34)
        assertTrue(report.findings.isEmpty())
        assertFalse(report.holders.single().active)
        assertTrue((status(report, Signals.NOTIFICATION_ENABLED) as Availability.Degraded).reason.contains("match no installed listener"))
    }

    @Test
    fun aRefusedListenerReadIsUnavailable() {
        val report = builder(sources(listeners = ReadResult.Refused("nope"))).build(34)
        assertTrue(status(report, Signals.NOTIFICATION_ENABLED) is Availability.Unavailable)
    }

    // ------------------------------------------------------------ device admins

    @Test
    fun anActiveAdminDeclaringForceLockFromAdbIsLookFirstAndSaysItBlocksUninstall() {
        val a = component("lab.fixture.admin", "lab.fixture.FixtureAdminReceiver")
        val report = builder(
            sources(
                admins = ReadResult.Ok(DeviceAdminReading(listOf(DeviceAdminFact(a, DevicePolicy.bitsOf(listOf(3)), "Fixture admin")), setOf(a))),
                packages = FakePackageFactsSource(factsMap(adbFacts("lab.fixture.admin"))),
            ),
        ).build(34)
        val f = report.findings.single()
        assertEquals(Attention.LOOK_FIRST, f.attention)
        assertEquals(Confidence.HIGH, f.confidence)
        assertEquals(Value.Items(listOf("force-lock")), f.claim.first { it.id == Signals.DEVICE_ADMIN_POLICIES }.value)
        assertTrue(f.meaning.contains("will not let you uninstall it"))
        assertEquals(CapabilityKind.DEVICE_ADMIN, report.holders.single().kind)
    }

    @Test
    fun anInstalledButInactiveAdminHasNoFinding() {
        val a = component("x.admin", "x.Admin")
        val report = builder(
            sources(
                admins = ReadResult.Ok(DeviceAdminReading(listOf(DeviceAdminFact(a, 8, null)), emptySet())),
                packages = FakePackageFactsSource(factsMap(facts("x.admin"))),
            ),
        ).build(34)
        assertTrue(report.findings.isEmpty())
        assertFalse(report.holders.single().active)
    }

    @Test
    fun anActiveAdminInAHiddenPackageIsStillReportedWithLowConfidenceAndAStatedLimit() {
        val hidden = component("hidden.pkg", "hidden.Admin")
        val report = builder(
            sources(admins = ReadResult.Ok(DeviceAdminReading(emptyList(), setOf(hidden)))),
        ).build(34)
        val f = report.findings.single()
        assertEquals(Tier.UNKNOWN, report.holders.single().tier.tier)
        assertEquals(Confidence.LOW, f.confidence)
        assertTrue(f.claim.any { o -> o.limits.any { it.contains("package may be hidden") } })
        assertTrue((status(report, Signals.DEVICE_ADMIN_ACTIVE) as Availability.Degraded).reason.contains("cannot see"))
    }

    @Test
    fun unreadablePoliciesOfAnInstalledAdminLowerTheClaimNotHideIt() {
        val a = component("x.admin", "x.Admin")
        val report = builder(
            sources(
                admins = ReadResult.Ok(DeviceAdminReading(listOf(DeviceAdminFact(a, null, null)), setOf(a))),
                packages = FakePackageFactsSource(factsMap(facts("x.admin"))),
            ),
        ).build(34)
        assertEquals(Confidence.LOW, report.findings.single().confidence)
    }

    // ------------------------------------------------------------ keyboards

    private fun keyboardReport(enabled: Boolean, isDefault: Boolean, f: PackageFacts = facts("k.app")): Report {
        val ime = component("k.app", "k.app.Ime")
        return builder(
            sources(
                keyboards = ReadResult.Ok(InputMethodReading(listOf(InputMethodFact(ime, "Fancy keys")), if (enabled) setOf(ime) else emptySet(), if (isDefault) ime else null)),
                packages = FakePackageFactsSource(factsMap(f)),
            ),
        ).build(34)
    }

    @Test
    fun theKeyboardInUseFromAStoreIsWorthALook() {
        val report = keyboardReport(enabled = true, isDefault = true)
        val f = report.findings.single()
        assertEquals("k.app is your keyboard, so it sees everything you type", f.title)
        assertEquals(Attention.LOOK, f.attention)
        assertTrue(f.claim.any { it.id == Signals.INPUT_METHOD_DEFAULT })
    }

    @Test
    fun aKeyboardThatIsOnButNotInUseIsOnlyANote() {
        val f = keyboardReport(enabled = true, isDefault = false).findings.single()
        assertEquals("k.app is switched on as a keyboard and could see what you type", f.title)
        assertEquals(Attention.NOTE, f.attention)
        assertFalse(f.claim.any { it.id == Signals.INPUT_METHOD_DEFAULT })
    }

    @Test
    fun aKeyboardInUseFromAnUnknownOriginIsLookFirst() {
        val f = keyboardReport(enabled = true, isDefault = true, f = facts("k.app", installing = null, initiating = null)).findings.single()
        assertEquals(Attention.LOOK_FIRST, f.attention)
    }

    @Test
    fun aKeyboardThatIsOffHasNoFinding() {
        assertTrue(keyboardReport(enabled = false, isDefault = false).findings.isEmpty())
    }

    @Test
    fun anUnknownDefaultKeyboardDegradesTheSignal() {
        val report = builder(sources(keyboards = ReadResult.Ok(InputMethodReading(emptyList(), emptySet(), null)))).build(34)
        assertTrue((status(report, Signals.INPUT_METHOD_DEFAULT) as Availability.Degraded).reason.contains("could not tell which keyboard"))
        val inconsistent = component("z.z", "z.Ime")
        val report2 = builder(sources(keyboards = ReadResult.Ok(InputMethodReading(emptyList(), emptySet(), inconsistent)))).build(34)
        assertTrue(status(report2, Signals.INPUT_METHOD_DEFAULT) is Availability.Degraded)
    }

    // ------------------------------------------------------------ special access (app-ops)

    private val allWatched = WatchedPermissions.SPECIAL

    private fun specialReport(f: PackageFacts, ops: ReadResult<Map<SpecialAccess, OpMode>>, ops2: FakeAppOpsSource? = null): Pair<Report, FakeAppOpsSource> {
        val appOps = ops2 ?: FakeAppOpsSource(otherwise = ops)
        return builder(sources(appOps = appOps, packages = FakePackageFactsSource(factsMap(f)))).build(34) to appOps
    }

    @Test
    fun anExplicitOverlayAllowOnASideloadedAppIsLookFirstAtHighConfidence() {
        val f = facts("o.app", installing = null, initiating = null, requested = allWatched, uid = 10123)
        val (report, _) = specialReport(f, ReadResult.Ok(mapOf(SpecialAccess.OVERLAY to OpMode.ALLOWED)))
        val finding = report.findings.single()
        assertEquals("overlay:o.app", finding.id)
        assertEquals(Attention.LOOK_FIRST, finding.attention)
        assertEquals(Confidence.HIGH, finding.confidence)
        assertEquals(Availability.Available, status(report, Signals.OVERLAY))
    }

    @Test
    fun aDefaultModeWithAGrantedPermissionIsOnButOnlyMediumConfidence() {
        val f = facts("o.app", requested = allWatched, granted = setOf(WatchedPermissions.OVERLAY))
        val (report, _) = specialReport(f, ReadResult.Ok(mapOf(SpecialAccess.OVERLAY to OpMode.DEFAULT)))
        val finding = report.findings.single()
        assertEquals(Confidence.MEDIUM, finding.confidence)
        assertTrue(finding.claim.single().limits.any { it.contains("default") })
    }

    @Test
    fun aDefaultModeWithoutTheGrantOrAnIgnoredModeIsOff() {
        val f = facts("o.app", requested = allWatched)
        assertTrue(specialReport(f, ReadResult.Ok(mapOf(SpecialAccess.OVERLAY to OpMode.DEFAULT))).first.findings.isEmpty())
        assertTrue(specialReport(f, ReadResult.Ok(mapOf(SpecialAccess.OVERLAY to OpMode.IGNORED))).first.findings.isEmpty())
        assertTrue(specialReport(f, ReadResult.Ok(mapOf(SpecialAccess.OVERLAY to OpMode.ERRORED))).first.findings.isEmpty())
        assertTrue(specialReport(f, ReadResult.Ok(emptyMap())).first.findings.isEmpty())
    }

    @Test
    fun eachSpecialAccessMapsToItsOwnKind() {
        val f = facts("o.app", requested = allWatched, installing = null, initiating = null)
        val ops = SpecialAccess.entries.associateWith { OpMode.ALLOWED }
        val report = specialReport(f, ReadResult.Ok(ops)).first
        assertEquals(
            setOf(CapabilityKind.OVERLAY, CapabilityKind.USAGE_ACCESS, CapabilityKind.INSTALL_UNKNOWN_APPS),
            report.holders.map { it.kind }.toSet(),
        )
        assertEquals(setOf("overlay:o.app", "usage-access:o.app", "install-unknown-apps:o.app"), report.findings.map { it.id }.toSet())
    }

    @Test
    fun anAppThatNeverDeclaredASpecialPermissionIsNotAskedAbout() {
        val f = facts("o.app", requested = setOf(WatchedPermissions.READ_SMS))
        val (_, appOps) = specialReport(f, ReadResult.Ok(emptyMap()))
        assertTrue(appOps.asked.isEmpty())
    }

    @Test
    fun builtInAppsAreLeftOutOfTheGrantLists() {
        val f = facts("sys.app", system = true, requested = allWatched, granted = allWatched)
        val (report, appOps) = specialReport(f, ReadResult.Ok(SpecialAccess.entries.associateWith { OpMode.ALLOWED }))
        assertTrue(report.findings.isEmpty())
        assertTrue(appOps.asked.isEmpty())
    }

    @Test
    fun androidTenRefusingEveryReadIsReportedAsUnavailableNotAsNothingGranted() {
        val f = facts("o.app", requested = allWatched)
        val (report, _) = specialReport(f, ReadResult.Refused("SecurityException: uid 10123 does not have android.permission.GET_APP_OPS_STATS"))
        for (id in listOf(Signals.OVERLAY, Signals.USAGE_ACCESS, Signals.INSTALL_UNKNOWN_APPS)) {
            val a = status(report, id) as Availability.Unavailable
            assertTrue(a.reason, a.reason.contains("Android 10"))
        }
        assertTrue(report.findings.isEmpty())
    }

    @Test
    fun someAppsRefusedAndOthersAnsweredIsDegraded() {
        val good = facts("g.app", requested = allWatched, installing = null, initiating = null)
        val bad = facts("b.app", requested = allWatched)
        val appOps = FakeAppOpsSource(
            byPackage = mapOf("b.app" to ReadResult.Refused("nope")),
            otherwise = ReadResult.Ok(mapOf(SpecialAccess.OVERLAY to OpMode.ALLOWED)),
        )
        val report = builder(sources(appOps = appOps, packages = FakePackageFactsSource(factsMap(good, bad)))).build(34)
        assertTrue(status(report, Signals.OVERLAY) is Availability.Degraded)
        assertEquals(listOf("overlay:g.app"), report.findings.map { it.id })
    }

    @Test
    fun anUnexpectedAppOpsFailureForEveryAppIsUnavailable() {
        val f = facts("o.app", requested = allWatched)
        val (report, _) = specialReport(f, ReadResult.Failed("RemoteException"))
        assertTrue((status(report, Signals.OVERLAY) as Availability.Unavailable).reason.contains("failed unexpectedly"))
    }

    @Test
    fun noCandidateAppsMeansAvailableBecauseThereWasNothingToAsk() {
        val report = builder(sources(packages = FakePackageFactsSource(factsMap(facts("plain.app"))))).build(29)
        assertEquals(Availability.Available, status(report, Signals.OVERLAY))
    }

    // ------------------------------------------------------------ text-message grants

    @Test
    fun aGrantedReadSmsOnANonBuiltInAppIsAFinding() {
        val f = facts("s.app", installing = null, initiating = null, requested = setOf(WatchedPermissions.READ_SMS), granted = setOf(WatchedPermissions.READ_SMS))
        val report = builder(sources(packages = FakePackageFactsSource(factsMap(f)))).build(34)
        val finding = report.findings.single()
        assertEquals("sms-access:s.app", finding.id)
        assertEquals(Attention.LOOK_FIRST, finding.attention)
        assertEquals(Value.Items(listOf("READ_SMS")), finding.claim.single().value)
    }

    @Test
    fun receiveSmsAloneCountsAndRequestedButNotGrantedDoesNot() {
        val granted = facts("a.app", granted = setOf(WatchedPermissions.RECEIVE_SMS), requested = setOf(WatchedPermissions.RECEIVE_SMS))
        val denied = facts("b.app", granted = emptySet(), requested = setOf(WatchedPermissions.READ_SMS))
        val report = builder(sources(packages = FakePackageFactsSource(factsMap(granted, denied)))).build(34)
        assertEquals(listOf("sms-access:a.app"), report.findings.map { it.id })
        assertEquals(Value.Items(listOf("RECEIVE_SMS")), report.findings.single().claim.single().value)
    }

    @Test
    fun aBuiltInMessagingAppIsNotAFinding() {
        val f = facts("sys.sms", system = true, granted = setOf(WatchedPermissions.READ_SMS), requested = setOf(WatchedPermissions.READ_SMS))
        assertTrue(builder(sources(packages = FakePackageFactsSource(factsMap(f)))).build(34).findings.isEmpty())
    }

    // ------------------------------------------------------------ inventory

    @Test
    fun theInventoryListsEveryVisibleAppWithTierKindsAndSensitiveGrants() {
        val plat = facts("p.plat", platformSigned = true)
        val store = facts("p.store", granted = setOf("android.permission.RECORD_AUDIO", "android.permission.CAMERA"))
        val side = facts("p.side", installing = "com.google.android.packageinstaller", granted = setOf(WatchedPermissions.READ_SMS), requested = setOf(WatchedPermissions.READ_SMS))
        val svc = service("p.side")
        val report = builder(
            sources(
                accessibility = ReadResult.Ok(reading(listOf(svc), setOf(svc.component))),
                packages = FakePackageFactsSource(factsMap(plat, store, side)),
            ),
        ).build(34)
        assertEquals(listOf("p.side", "p.store", "p.plat"), report.apps.map { it.pkg })
        assertEquals(setOf(CapabilityKind.ACCESSIBILITY, CapabilityKind.SMS_ACCESS), report.apps.first { it.pkg == "p.side" }.kinds)
        assertEquals(listOf("CAMERA", "RECORD_AUDIO"), report.apps.first { it.pkg == "p.store" }.grants)
        assertEquals(mapOf(Tier.SIDELOAD to 1, Tier.STORE to 1, Tier.PLATFORM to 1), report.tierCounts)
        assertEquals(Availability.Available, status(report, Signals.PACKAGE_INVENTORY))
    }

    @Test
    fun aFailedPackageListMakesGrantsUnavailableButKeepsComponentFindings() {
        val svc = service("a.b")
        val packages = FakePackageFactsSource(factsMap(facts("a.b")), listing = ReadResult.Refused("QUERY_ALL_PACKAGES needed"))
        val report = builder(sources(accessibility = ReadResult.Ok(reading(listOf(svc), setOf(svc.component))), packages = packages)).build(34)
        assertEquals(1, report.findings.size)
        assertTrue(report.apps.isEmpty())
        assertTrue(status(report, Signals.PACKAGE_INVENTORY) is Availability.Unavailable)
        assertTrue(status(report, Signals.SMS_ACCESS) is Availability.Unavailable)
        assertTrue(status(report, Signals.OVERLAY) is Availability.Unavailable)
    }

    @Test
    fun everySignalTheProductCanProduceHasAStatusLine() {
        val ids = builder(sources()).build(34).signals.map { it.id }.toSet()
        for (id in listOf(
            Signals.ACCESSIBILITY_ENABLED, Signals.ACCESSIBILITY_CAPABILITIES, Signals.NOTIFICATION_ENABLED, Signals.DEVICE_ADMIN_ACTIVE,
            Signals.INPUT_METHOD_ENABLED, Signals.INPUT_METHOD_DEFAULT, Signals.OVERLAY, Signals.USAGE_ACCESS, Signals.INSTALL_UNKNOWN_APPS,
            Signals.SMS_ACCESS, Signals.PACKAGE_INVENTORY, Signals.APP_INSTALL_ORIGIN, Signals.PATCH_LEVEL, Signals.NETWORK_ACTIVE,
        )) assertTrue("missing status for $id", id in ids)
    }

    @Test
    fun aHolderWithoutAFindingHasNoLookup() {
        val svc = service("a.b")
        val report = builder(sources(accessibility = ReadResult.Ok(reading(listOf(svc), emptySet())), packages = FakePackageFactsSource(factsMap(facts("a.b"))))).build(34)
        assertNull(report.holderOf("accessibility:a.b/a.b.Service"))
    }
}
