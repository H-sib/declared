package io.github.h_sib.declared.core.paths

import io.github.h_sib.declared.core.model.Declarations
import io.github.h_sib.declared.core.model.Told
import io.github.h_sib.declared.core.model.Profile
import io.github.h_sib.declared.core.FakePackageFactsSource
import io.github.h_sib.declared.core.builder
import io.github.h_sib.declared.core.component
import io.github.h_sib.declared.core.facts
import io.github.h_sib.declared.core.factsMap
import io.github.h_sib.declared.core.healthyDevice
import io.github.h_sib.declared.core.NOT_ON_WIFI
import io.github.h_sib.declared.core.NO_USER_CAS
import io.github.h_sib.declared.core.QUIET_NETWORK
import io.github.h_sib.declared.core.network
import io.github.h_sib.declared.core.reading
import io.github.h_sib.declared.core.service
import io.github.h_sib.declared.core.sources
import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.Provenance
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.sensor.ActiveNetwork
import io.github.h_sib.declared.core.sensor.AdvancedProtectionState
import io.github.h_sib.declared.core.sensor.DeviceAdminFact
import io.github.h_sib.declared.core.sensor.DeviceAdminReading
import io.github.h_sib.declared.core.sensor.DeviceFactsReading
import io.github.h_sib.declared.core.sensor.ListenerFact
import io.github.h_sib.declared.core.sensor.NotificationListenerReading
import io.github.h_sib.declared.core.sensor.PackageFacts
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.Transport
import io.github.h_sib.declared.core.sensor.UserCaReading
import io.github.h_sib.declared.core.sensor.WatchedPermissions
import io.github.h_sib.declared.core.sensor.WifiReading
import io.github.h_sib.declared.core.sensor.WifiSecurity
import io.github.h_sib.declared.core.state.Answer
import io.github.h_sib.declared.core.state.Review
import io.github.h_sib.declared.core.state.SavedState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The rule book on a corpus of phones, each one a documented shape from THREAT_MODEL.md section 5 (P1 to P6) or a case that
 * must stay quiet. A clean phone raising nothing is as much a test as a bad phone raising something.
 */
class RuleBookTest {
    private val at = Instant.parse("2026-10-09T10:00:00Z")

    private fun eval(report: Report, saved: SavedState? = null, told: Declarations = Declarations()): Pair<AttackGraph, Evaluation> {
        val g = RuleBook.build(report, saved, told)
        return g to Evaluator.evaluate(g)
    }

    private fun value(e: Evaluation, id: String) = e.value(NodeId(id))

    private fun answered(report: Report, vararg answers: Pair<String, Answer>): SavedState {
        val byKey = answers.associate { (pkg, a) -> Review.key(report.holders.first { it.pkg == pkg && it.active }) to a }
        return Review.establish(report, byKey, null, at)
    }

    // ------------------------------------------------------------ the quiet cases

    @Test
    fun aCleanPhoneHasNoPathAndNothingKeptOpen() {
        val (_, e) = eval(phone())
        assertEquals(emptyList<Goal>(), e.paths)
        assertEquals("a clean phone must not keep anything open either", emptyList<Goal>(), e.notRuledOut)
    }

    @Test
    fun appsThatCameWithThePhoneMakeNoNodesAtAll() {
        val system = facts("sys.app", system = true, installing = null, initiating = null)
        val report = phone(packages = listOf(system), a11y = listOf("sys.app"), listeners = listOf("sys.app"))
        val (g, e) = eval(report)
        assertTrue(g.nodes.keys.none { it.value.contains("sys.app") })
        assertEquals(emptyList<Goal>(), e.paths)
    }

    @Test
    fun anAppFromAStoreHoldingAccessibilityIsNotAPathUntilThePersonDoubtsIt() {
        val report = phone(packages = listOf(facts("store.app")), a11y = listOf("store.app"))
        assertEquals(Tri.FALSE, value(eval(report).second, "p1:store.app"))
        val doubted = answered(report, "store.app" to Answer.NOT_RECOGNIZED)
        val (_, e) = eval(report, doubted)
        assertEquals(Tri.TRUE, value(e, "p1:store.app"))
        assertTrue("the doubt is the person's own word", e.usesProvenance(NodeId("p1:store.app"), Provenance.TOLD))
    }

    // ------------------------------------------------------------ P1

    @Test
    fun aSideloadedAppThatCanReadTheScreenIsPathP1WithSeverityPerProfile() {
        val report = phone(packages = listOf(sideloaded("evil.app")), a11y = listOf("evil.app"))
        val (g, e) = eval(report)
        assertEquals(Tri.TRUE, value(e, "p1:evil.app"))
        val goal = g.goals.single { it.node == NodeId("p1:evil.app") }
        assertEquals(Severity.HIGH, goal.severityFor(Profile.GENERAL))
        assertEquals(Severity.CRITICAL, goal.severityFor(Profile.HIGH_RISK))
        assertTrue("T1453" in goal.techniques)
        assertTrue(goal.cannotSee.isNotEmpty())
        val leaves = e.witness(NodeId("p1:evil.app"))!!.leaves.map { g.node(it).text }
        assertTrue(leaves.toString(), leaves.any { it.contains("evil.app") && it.contains("Accessibility") })
        assertTrue(leaves.any { it.contains("may read the text") })
        assertNull("accessibility alone is not a one-time-code path until the person says codes arrive by text", e.confidence(NodeId("p2:evil.app")))
    }

    @Test
    fun youSayingYouRecognizeASideloadedAppTakesItOutOfTheListButAChangedSignerBringsItBack() {
        val report = phone(packages = listOf(sideloaded("mine.app")), a11y = listOf("mine.app"))
        val recognized = answered(report, "mine.app" to Answer.RECOGNIZED)
        assertEquals(Tri.FALSE, value(eval(report, recognized).second, "p1:mine.app"))
        val resigned = phone(packages = listOf(sideloaded("mine.app").copy(signerSha256 = listOf("ff".repeat(32)))), a11y = listOf("mine.app"))
        val (_, e) = eval(resigned, recognized)
        assertEquals("a new signing certificate after you said you recognized it is a reason to look again", Tri.TRUE, value(e, "p1:mine.app"))
    }

    // ------------------------------------------------------------ P2

    @Test
    fun aCodeInterceptorNeedsTheCodesToArriveByTextAndTheAnswerDecidesIt() {
        val report = phone(packages = listOf(sideloaded("spy.app")), listeners = listOf("spy.app"))
        assertEquals(Tri.TRUE, value(eval(report, told = Declarations(smsCodes = Told.YES)).second, "p2:spy.app"))
        assertEquals(Tri.FALSE, value(eval(report, told = Declarations(smsCodes = Told.NO)).second, "p2:spy.app"))
        val (g, e) = eval(report, told = Declarations(smsCodes = Told.UNSURE))
        assertEquals("not told is not 'no': the path stays open and says why", Tri.UNKNOWN, value(e, "p2:spy.app"))
        assertEquals(setOf(NodeId("told:sms-codes")), e.openedBy(NodeId("p2:spy.app")))
        assertTrue((g.node(NodeId("told:sms-codes")) as Leaf).reason!!.contains("You have not said"))
        assertTrue(e.notRuledOut.any { it.node == NodeId("p2:spy.app") })
    }

    @Test
    fun anAppThatCanReadMessagesIsAWayToTakeCodesToo() {
        val sms = sideloaded("sms.app").copy(requestedWatched = setOf(WatchedPermissions.READ_SMS), grantedWatched = setOf(WatchedPermissions.READ_SMS))
        val (_, e) = eval(phone(packages = listOf(sms)), told = Declarations(smsCodes = Told.YES))
        assertEquals(Tri.TRUE, value(e, "p2:sms.app"))
    }

    @Test
    fun aPathThatRestsOnWhatYouToldTheAppSaysSoAndItsConfidenceIsNotHigh() {
        val report = phone(packages = listOf(sideloaded("spy.app")), listeners = listOf("spy.app"))
        val (_, e) = eval(report, told = Declarations(smsCodes = Told.YES))
        assertTrue(e.usesProvenance(NodeId("p2:spy.app"), Provenance.TOLD))
        assertEquals(Confidence.MEDIUM, e.confidence(NodeId("p2:spy.app")))
    }

    // ------------------------------------------------------------ P3

    @Test
    fun anAddedCertificateAuthorityAndAProxyAreTheirOwnPaths() {
        val report = phone(userCas = ReadResult.Ok(UserCaReading(listOf("ab".repeat(32)))), network = network(proxy = "10.0.0.2:3128"))
        val e = eval(report).second
        assertEquals(Tri.TRUE, value(e, "p3-ca"))
        assertEquals("the certificate reading is only medium: it was checked on one image", Confidence.MEDIUM, e.confidence(NodeId("p3-ca")))
        assertEquals(Tri.TRUE, value(e, "p3-proxy"))
        assertEquals(Confidence.HIGH, e.confidence(NodeId("p3-proxy")))
    }

    @Test
    fun openWifiWithoutPrivateDnsIsAPathAndPrivateDnsClosesIt() {
        val open = ReadResult.Ok(WifiReading(true, WifiSecurity.OPEN))
        val bad = phone(network = network(transports = setOf(Transport.WIFI), privateDns = false), wifi = open)
        assertEquals(Tri.TRUE, value(eval(bad).second, "p3-wifi"))
        val good = phone(network = network(transports = setOf(Transport.WIFI), privateDns = true), wifi = open)
        assertEquals(Tri.FALSE, value(eval(good).second, "p3-wifi"))
    }

    @Test
    fun usingPublicWifiOftenWithoutPrivateDnsIsAPathEvenOnAHomeNetworkAtThisMoment() {
        val home = phone(network = network(transports = setOf(Transport.WIFI), privateDns = false), wifi = ReadResult.Ok(WifiReading(true, WifiSecurity.WPA3_PSK)))
        assertEquals(Tri.FALSE, value(eval(home, told = Declarations(publicWifi = Told.NO)).second, "p3-wifi"))
        assertEquals(Tri.TRUE, value(eval(home, told = Declarations(publicWifi = Told.YES)).second, "p3-wifi"))
        assertEquals("not told keeps it open", Tri.UNKNOWN, value(eval(home, told = Declarations(publicWifi = Told.UNSURE)).second, "p3-wifi"))
    }

    @Test
    fun whenTheNetworkCannotBeReadThePathsThatNeedItStayOpenWithTheReason() {
        val report = phone(network = ReadResult.Refused("denied"), wifi = ReadResult.Failed("boom"))
        val (g, e) = eval(report, told = Declarations(publicWifi = Told.YES))
        assertEquals(Tri.UNKNOWN, value(e, "p3-wifi"))
        assertEquals(Tri.UNKNOWN, value(e, "p3-proxy"))
        val reasons = e.openedBy(NodeId("p3-wifi")).map { (g.node(it) as Leaf).reason!! }
        assertTrue(reasons.toString(), reasons.any { it.contains("denied") })
    }

    // ------------------------------------------------------------ P4, P5, P6

    @Test
    fun noScreenLockIsPathP4AndAnUnreadableLockKeepsItOpen() {
        val none = phone(device = healthyDevice(lock = ReadResult.Ok(false)))
        assertEquals(Tri.TRUE, value(eval(none).second, "p4-no-lock"))
        val unreadable = phone(device = healthyDevice(lock = ReadResult.Refused("no")))
        val (g, e) = eval(unreadable)
        assertEquals(Tri.UNKNOWN, value(e, "p4-no-lock"))
        assertTrue((g.node(NodeId("state:no-lock")) as Leaf).reason!!.contains("refused"))
        assertEquals(Tri.FALSE, value(eval(phone()).second, "p4-no-lock"))
    }

    @Test
    fun anAppWithAnAdministratorRoleNoIconAndALocationGrantHasTheShapeOfStalkerware() {
        val watcher = sideloaded("shadow.app").copy(hasLauncherIcon = false, requestedWatched = setOf("android.permission.ACCESS_FINE_LOCATION"), grantedWatched = setOf("android.permission.ACCESS_FINE_LOCATION"))
        val (g, e) = eval(phone(packages = listOf(watcher), admins = listOf("shadow.app")))
        assertEquals(Tri.TRUE, value(e, "p5:shadow.app"))
        assertTrue(g.goals.single { it.node == NodeId("p5:shadow.app") }.title.contains("shape of an app made to watch someone"))
        val withIcon = watcher.copy(hasLauncherIcon = true)
        val (g2, _) = eval(phone(packages = listOf(withIcon), admins = listOf("shadow.app")))
        assertFalse("an app with an icon cannot have this shape, so no node is built", g2.goals.any { it.node.value.startsWith("p5:") })
    }

    @Test
    fun theSecurityUpdateLimitIsAYearForMostPeopleAndHalfThatForTheHigherRiskProfile() {
        val old = phone(device = healthyDevice(patch = ReadResult.Ok("2026-01-01"))) // 281 days before the fixed clock
        assertEquals(Tri.FALSE, value(eval(old, told = Declarations(profile = Profile.GENERAL)).second, "p6-patch"))
        assertEquals(Tri.TRUE, value(eval(old, told = Declarations(profile = Profile.HIGH_RISK)).second, "p6-patch"))
        val ancient = phone(device = healthyDevice(patch = ReadResult.Ok("2023-09-05")))
        assertEquals(Tri.TRUE, value(eval(ancient).second, "p6-patch"))
        val unreadable = phone(device = healthyDevice(patch = ReadResult.Ok("soon")))
        assertEquals(Tri.UNKNOWN, value(eval(unreadable).second, "p6-patch"))
    }

    @Test
    fun advancedProtectionIsOnlyAPathWhereItExistsAndIsReadableAndOff() {
        assertEquals("not applicable below Android 16", Tri.FALSE, value(eval(phone()).second, "p6-advanced-protection"))
        val off = phone(device = healthyDevice(advanced = AdvancedProtectionState.Known(false)))
        val (g, e) = eval(off)
        assertEquals(Tri.TRUE, value(e, "p6-advanced-protection"))
        val goal = g.goals.single { it.node == NodeId("p6-advanced-protection") }
        assertEquals(Severity.LOW, goal.severityFor(Profile.GENERAL))
        assertEquals(Severity.HIGH, goal.severityFor(Profile.HIGH_RISK))
        assertEquals(Tri.FALSE, value(eval(phone(device = healthyDevice(advanced = AdvancedProtectionState.Known(true)))).second, "p6-advanced-protection"))
        assertEquals(Tri.UNKNOWN, value(eval(phone(device = healthyDevice(advanced = AdvancedProtectionState.NotPermitted))).second, "p6-advanced-protection"))
    }

    // ------------------------------------------------------------ fixing

    @Test
    fun theWorstCasePhoneHasThePathsOfEveryRule() {
        val (report, told) = worstCase()
        val (_, e) = eval(report, told = told)
        val ids = e.paths.map { it.node.value }.toSet()
        assertEquals(
            setOf("p1:shadow.app", "p2:shadow.app", "p5:shadow.app", "p3-ca", "p3-proxy", "p3-wifi", "p4-no-lock", "p6-patch", "p6-advanced-protection"),
            ids,
        )
    }

    @Test
    fun theSmallestFixForTheWorstCaseIsFoundAndRemovingTheAppCutsThreePathsAtOnce() {
        val (report, told) = worstCase()
        val (g, e) = eval(report, told = told)
        val plan = Fixes.minimum(g, e)
        assertTrue(plan.exact)
        assertEquals(
            listOf("advanced-protection-on", "clear-proxy", "private-dns-on", "remove-ca", "remove:shadow.app", "set-lock", "update-phone"),
            plan.levers.map { it.id },
        )
        assertEquals(9, plan.cut.size)
        assertTrue(plan.remaining.isEmpty() && plan.unstoppable.isEmpty())
        val removeOnly = Fixes.preview(g, setOf("remove:shadow.app"), e)
        assertEquals(setOf("p1:shadow.app", "p2:shadow.app", "p5:shadow.app"), removeOnly.stopped.map { it.node.value }.toSet())
    }

    @Test
    fun movingAccountsToAnAuthenticatorCutsTheCodePathWithoutTouchingTheApp() {
        val report = phone(packages = listOf(sideloaded("spy.app")), listeners = listOf("spy.app"))
        val (g, e) = eval(report, told = Declarations(smsCodes = Told.YES))
        val p = Fixes.preview(g, setOf("authenticator"), e)
        assertEquals(listOf("p2:spy.app"), p.stopped.map { it.node.value })
        assertTrue(g.lever("authenticator")!!.outsidePhone)
    }

    // ------------------------------------------------------------ words and structure

    @Test
    fun everyLeverStatesItsTradeOffAndEveryGoalWhatItCannotSee() {
        val (report, told) = worstCase()
        val g = RuleBook.build(report, null, told)
        assertTrue(g.levers.size >= 14)
        for (l in g.levers) {
            assertTrue("${l.id} trade-off", l.tradeOff.length > 20)
            assertTrue("${l.id} text", l.text.length > 8)
        }
        for (goal in g.goals) {
            assertTrue("${goal.node} cannot-see", goal.cannotSee.isNotEmpty())
            assertTrue("${goal.node} techniques", goal.techniques.isNotEmpty())
        }
    }

    @Test
    fun noTextTheEngineCanProduceUsesABannedWordOrADash() {
        val banned = listOf("malicious", "malware", "virus", "infected", "suspicious", "trojan", "spyware", "stalkerware", "hacked", "compromised", "danger", "factory reset", "safe", "secure", "score", "rating", "root", "rooted", "flash", "wipe", "protected")
        val (report, told) = worstCase()
        val graphs = listOf(RuleBook.build(report, null, told), RuleBook.build(phone(), null, Declarations()), RuleBook.build(report, null, Declarations(profile = Profile.HIGH_RISK)))
        var checked = 0
        for (g in graphs) {
            val texts = g.nodes.values.map { it.text } + g.goals.flatMap { listOf(it.title) + it.cannotSee } + g.levers.flatMap { listOf(it.text, it.tradeOff) } +
                g.leaves.mapNotNull { it.reason } + g.goals.map { it.asset.label }
            for (t in texts) {
                val lower = t.lowercase()
                for (w in banned) assertFalse("'$w' in: $t", Regex("(?<![a-z])" + Regex.escape(w) + "(?![a-z])").containsMatchIn(lower))
                assertFalse("dash in: $t", t.any { it.code == 0x2014 || it.code == 0x2013 })
                checked += 1
            }
        }
        assertTrue(checked > 100)
    }

    @Test
    fun theSameInputsAlwaysGiveTheSameGraph() {
        val (report, told) = worstCase()
        val a = RuleBook.build(report, null, told)
        val b = RuleBook.build(report, null, told)
        assertEquals(a.order, b.order)
        assertEquals(a.levers, b.levers)
        assertEquals(a.goals, b.goals)
    }
}
