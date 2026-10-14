package io.github.h_sib.declared.core

import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.model.Availability
import io.github.h_sib.declared.core.model.CapabilityKind
import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.Provenance
import io.github.h_sib.declared.core.model.Signals
import io.github.h_sib.declared.core.model.Value
import io.github.h_sib.declared.core.sensor.AccessibilityReading
import io.github.h_sib.declared.core.sensor.PackageFacts
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.tier.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sensor contract tests for the accessibility path: the pipeline against scripted fakes, including every refusal path. */
class ReportBuilderTest {

    private fun build(
        accessibility: ReadResult<AccessibilityReading>,
        packages: Map<String, ReadResult<PackageFacts>> = emptyMap(),
        api: Int = 34,
    ) = builder(sources(accessibility = accessibility, packages = FakePackageFactsSource(packages))).build(api)

    private fun enabledStatus(report: io.github.h_sib.declared.core.report.Report) =
        report.signals.first { it.id == Signals.ACCESSIBILITY_ENABLED }.availability

    @Test
    fun aServiceOnlyTheSettingsListIsShownAsSwitchedOnWithLowerConfidenceNotHidden() {
        // Found by conformance (D-037): a test tool attached to the screen suspends every service, so Android's own list of
        // running services loses it for a moment while the settings value still has it. Hiding it would be the unsafe error.
        val svc = service("a.b")
        val report = build(
            ReadResult.Ok(reading(listOf(svc), enabled = emptySet(), setting = setOf(svc.component))),
            mapOf("a.b" to ReadResult.Ok(facts("a.b"))),
        )
        val f = report.findings.single()
        assertEquals(Confidence.MEDIUM, f.confidence)
        assertTrue(f.claim.first().limits.any { it.contains("settings say it is switched on") })
        assertTrue(report.holders.single().active)
    }

    @Test
    fun anEnabledServiceFromUnknownOriginBecomesALookFirstFindingWithEvidence() {
        val svc = service("lab.fixture.a11y")
        val report = build(
            ReadResult.Ok(reading(listOf(svc), setOf(svc.component))),
            mapOf("lab.fixture.a11y" to ReadResult.Ok(facts("lab.fixture.a11y", label = "Fixture accessibility", installing = null, initiating = null))),
        )

        assertEquals(1, report.findings.size)
        val f = report.findings.single()
        assertEquals("accessibility:lab.fixture.a11y/lab.fixture.a11y.Service", f.id)
        assertEquals(Attention.LOOK_FIRST, f.attention)
        assertEquals(Confidence.HIGH, f.confidence)
        assertEquals(setOf(Signals.ACCESSIBILITY_ENABLED, Signals.ACCESSIBILITY_CAPABILITIES), f.claim.map { it.id }.toSet())
        assertEquals(Provenance.OBSERVED, f.claim.first { it.id == Signals.ACCESSIBILITY_ENABLED }.provenance)
        assertEquals(Provenance.DECLARED, f.claim.first { it.id == Signals.ACCESSIBILITY_CAPABILITIES }.provenance)
        assertEquals(Value.Items(listOf("window-content", "gestures")), f.claim.first { it.id == Signals.ACCESSIBILITY_CAPABILITIES }.value)
        assertTrue(f.claim.all { it.limits.isNotEmpty() && it.source.api.isNotBlank() })
        assertEquals(Provenance.INFERRED, f.context.first { it.id == Signals.APP_INSTALL_ORIGIN }.provenance)
        assertEquals(report.takenAt, FIXED_CLOCK.instant())
        assertEquals(Availability.Available, enabledStatus(report))
    }

    @Test
    fun theClaimsConfidenceIsNotLoweredByAnUnverifiedInstaller() {
        // Context carries its own confidence (STORE is MEDIUM) and must not drag the claim down.
        val svc = service("a.b")
        val f = build(ReadResult.Ok(reading(listOf(svc), setOf(svc.component))), mapOf("a.b" to ReadResult.Ok(facts("a.b")))).findings.single()
        assertEquals(Confidence.HIGH, f.confidence)
        assertEquals(Confidence.MEDIUM, f.context.first { it.id == Signals.APP_INSTALL_ORIGIN }.confidence)
        assertEquals(Attention.LOOK, f.attention)
    }

    @Test
    fun aFindingTakesTheWeakestConfidenceAmongItsClaimEvidence() {
        val svc = service("a.b")
        val report = build(
            ReadResult.Ok(reading(listOf(svc), enabled = setOf(svc.component), setting = emptySet())),
            mapOf("a.b" to ReadResult.Ok(facts("a.b"))),
        )
        assertEquals(Confidence.MEDIUM, report.findings.single().confidence)
        assertTrue(enabledStatus(report) is Availability.Degraded)
    }

    @Test
    fun aBuiltInScreenReaderIsOnlyANote() {
        val svc = service("com.google.android.marvin.talkback", bits = 0x1 or 0x2 or 0x8)
        val report = build(
            ReadResult.Ok(reading(listOf(svc), setOf(svc.component))),
            mapOf(svc.component.pkg to ReadResult.Ok(facts(svc.component.pkg, label = "Android Accessibility Suite", system = true, installing = "com.android.vending"))),
        )
        assertEquals(Attention.NOTE, report.findings.single().attention)
        assertEquals(Tier.SYSTEM, report.holders.single().tier.tier)
    }

    @Test
    fun anInstalledButDisabledServiceIsListedWithoutAFinding() {
        val svc = service("a.b")
        val report = build(ReadResult.Ok(reading(listOf(svc), emptySet())), mapOf("a.b" to ReadResult.Ok(facts("a.b"))))
        assertTrue(report.findings.isEmpty())
        val holder = report.holders.single()
        assertEquals(false, holder.active)
        assertNull(holder.findingId)
    }

    @Test
    fun findingsAreOrderedMostAttentionFirstThenByName() {
        val s1 = service("c.store"); val s2 = service("a.unknown"); val s3 = service("b.system")
        val report = build(
            ReadResult.Ok(reading(listOf(s1, s2, s3), setOf(s1.component, s2.component, s3.component))),
            mapOf(
                "c.store" to ReadResult.Ok(facts("c.store", label = "Zed Store App")),
                "a.unknown" to ReadResult.Ok(facts("a.unknown", label = "Alpha", installing = null, initiating = null)),
                "b.system" to ReadResult.Ok(facts("b.system", label = "Beta", system = true)),
            ),
        )
        assertEquals(listOf(Attention.LOOK_FIRST, Attention.LOOK, Attention.NOTE), report.findings.map { it.attention })
    }

    @Test
    fun platformRefusalBecomesUnavailableNeverAnEmptyOk() {
        val report = build(ReadResult.Refused("SecurityException: nope"))
        assertTrue(report.findings.isEmpty() && report.holders.isEmpty())
        assertTrue((enabledStatus(report) as Availability.Unavailable).reason.contains("refused"))
    }

    @Test
    fun anUnexpectedFailureIsReportedAsAFailureNotAsAllClear() {
        val report = build(ReadResult.Failed("RemoteException"))
        assertTrue((enabledStatus(report) as Availability.Unavailable).reason.contains("failed"))
    }

    @Test
    fun anUnreadablePackageStillGetsAFindingAtUnknownTier() {
        val svc = service("ghost.pkg")
        val report = build(ReadResult.Ok(reading(listOf(svc), setOf(svc.component))), mapOf("ghost.pkg" to ReadResult.Failed("NameNotFoundException")))
        val f = report.findings.single()
        assertEquals("ghost.pkg", report.holders.single().label)
        assertEquals(Tier.UNKNOWN, report.holders.single().tier.tier)
        assertEquals(Attention.LOOK_FIRST, f.attention)
        assertTrue(report.signals.first { it.id == Signals.APP_INSTALL_ORIGIN }.availability is Availability.Degraded)
    }

    @Test
    fun anEnabledEntryWithNoInstalledServiceIsNotAHolderButIsReportedAsDegraded() {
        val stale = component("gone.pkg")
        val report = build(ReadResult.Ok(reading(emptyList(), setOf(stale))))
        assertTrue(report.holders.isEmpty())
        assertTrue((enabledStatus(report) as Availability.Degraded).reason.contains("match no installed service"))
    }

    @Test
    fun anUnreadableCrossCheckSettingDegradesButDoesNotHideTheFinding() {
        val svc = service("a.b")
        val report = build(
            ReadResult.Ok(reading(listOf(svc), enabled = setOf(svc.component), setting = null)),
            mapOf("a.b" to ReadResult.Ok(facts("a.b"))),
        )
        assertEquals(1, report.findings.size)
        assertEquals(Confidence.HIGH, report.findings.single().confidence)
        assertTrue(enabledStatus(report) is Availability.Degraded)
    }

    @Test
    fun theDeclaredToolFlagIsOnlyContextAndOnlyWhenThePlatformCanSayIt() {
        val withTool = service("a.b", tool = true)
        val without = service("c.d", tool = null)
        val report = build(
            ReadResult.Ok(reading(listOf(withTool, without), setOf(withTool.component, without.component))),
            mapOf("a.b" to ReadResult.Ok(facts("a.b")), "c.d" to ReadResult.Ok(facts("c.d"))),
        )
        val a = report.findings.first { it.id.contains("a.b") }
        val c = report.findings.first { it.id.contains("c.d") }
        assertNotNull(a.context.firstOrNull { it.id == Signals.ACCESSIBILITY_DECLARED_TOOL })
        assertNull(c.context.firstOrNull { it.id == Signals.ACCESSIBILITY_DECLARED_TOOL })
        assertTrue(a.meaning.contains("its own statement"))
        assertTrue(c.whatItCannotTell.any { it.contains("Android 14") || it.contains("version 14") })
        // A self-declared tool flag does not lower the attention.
        assertEquals(Attention.LOOK, a.attention)
    }

    @Test
    fun twoServicesOfOneAppAreToldApartByTheirOwnLabelAndComponent() {
        // Found on the real emulator screen: TalkBack and Select to Speak are two services of one app and both rows
        // read "Android Accessibility Suite" until the service's own label and component were carried through.
        val talk = service("g.suite", label = "TalkBack").copy(component = component("g.suite", "g.suite.TalkBackService"))
        val speak = service("g.suite", label = "Select to Speak").copy(component = component("g.suite", "g.suite.SelectToSpeakService"))
        val report = build(
            ReadResult.Ok(reading(listOf(talk, speak), setOf(talk.component, speak.component))),
            mapOf("g.suite" to ReadResult.Ok(facts("g.suite", label = "Android Accessibility Suite"))),
        )
        assertEquals(setOf("TalkBack", "Select to Speak"), report.holders.map { it.subLabel }.toSet())
        val subjects = report.findings.map { it.subject }
        assertEquals(2, subjects.toSet().size)
        assertTrue(subjects.any { it.contains("TalkBack") && it.contains("g.suite/g.suite.TalkBackService") })
    }

    @Test
    fun aServiceWithNoLabelStillHasAnUnambiguousSubject() {
        val svc = service("a.b")
        val f = build(ReadResult.Ok(reading(listOf(svc), setOf(svc.component))), mapOf("a.b" to ReadResult.Ok(facts("a.b")))).findings.single()
        assertEquals("Accessibility service (a.b/a.b.Service)", f.subject)
    }

    @Test
    fun packagesFromTheBulkListingAreNotAskedForOneByOne() {
        val s1 = service("a.b").copy(component = component("a.b", "a.b.One"))
        val s2 = service("a.b").copy(component = component("a.b", "a.b.Two"))
        val fake = FakePackageFactsSource(factsMap(facts("a.b")))
        builder(sources(accessibility = ReadResult.Ok(reading(listOf(s1, s2), setOf(s1.component, s2.component))), packages = fake)).build(34)
        assertEquals(emptyList<String>(), fake.asked)
    }

    @Test
    fun aPackageMissingFromTheListingIsAskedForOnceNotOncePerService() {
        val s1 = service("a.b").copy(component = component("a.b", "a.b.One"))
        val s2 = service("a.b").copy(component = component("a.b", "a.b.Two"))
        val fake = FakePackageFactsSource(factsMap(facts("a.b")), listing = ReadResult.Ok(emptyList()))
        builder(sources(accessibility = ReadResult.Ok(reading(listOf(s1, s2), setOf(s1.component, s2.component))), packages = fake)).build(34)
        assertEquals(listOf("a.b"), fake.asked)
    }

    @Test
    fun anUnreadablePackageIsCountedOncePerPackageNotPerService() {
        val s1 = service("a.b").copy(component = component("a.b", "a.b.One"))
        val s2 = service("a.b").copy(component = component("a.b", "a.b.Two"))
        val report = build(ReadResult.Ok(reading(listOf(s1, s2), setOf(s1.component, s2.component))), mapOf("a.b" to ReadResult.Refused("hidden")))
        val d = report.signals.first { it.id == Signals.APP_INSTALL_ORIGIN }.availability as Availability.Degraded
        assertTrue(d.reason, d.reason.contains("for 1 app(s)"))
    }

    @Test
    fun accessibilityHoldersAreTaggedWithTheirKind() {
        val svc = service("a.b")
        val report = build(ReadResult.Ok(reading(listOf(svc), setOf(svc.component))), mapOf("a.b" to ReadResult.Ok(facts("a.b"))))
        assertEquals(CapabilityKind.ACCESSIBILITY, report.holders.single().kind)
        assertEquals(report.findings.single(), report.finding(report.findings.single().id))
        assertEquals(report.holders.single(), report.holderOf(report.findings.single().id))
    }
}
