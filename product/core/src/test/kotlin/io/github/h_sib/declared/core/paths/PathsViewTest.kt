package io.github.h_sib.declared.core.paths

import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.Declarations
import io.github.h_sib.declared.core.model.Profile
import io.github.h_sib.declared.core.model.Provenance
import io.github.h_sib.declared.core.model.Told
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathsViewTest {
    private fun view(report: io.github.h_sib.declared.core.report.Report, told: Declarations): PathsView {
        val g = RuleBook.build(report, null, told)
        return PathsPresenter.present(g, told.profile)
    }

    @Test
    fun aCleanPhoneHasNoPathsNothingKeptOpenAndNoPlan() {
        val v = view(phone(), Declarations())
        assertEquals(0, v.pathCount)
        assertTrue(v.notRuledOut.isEmpty())
        assertNull(v.plan)
    }

    @Test
    fun pathsAreOrderedBySeverityThenConfidenceThenTitleAndTheOrderIsStable() {
        val (report, told) = worstCase()
        val a = view(report, told)
        val b = view(report, told)
        assertEquals(a.paths.map { it.id }, b.paths.map { it.id })
        val severities = a.paths.map { it.severity }
        assertEquals("a path of lower severity never comes before a higher one", severities.sortedDescending(), severities)
        assertEquals("the one informational path is last", "p6-advanced-protection", a.paths.last().id)
        assertEquals(9, a.pathCount)
    }

    @Test
    fun theSameFindingsAreWeightedHigherForTheHigherRiskProfile() {
        val (report, told) = worstCase()
        val general = view(report, told.copy(profile = Profile.GENERAL))
        val high = view(report, told.copy(profile = Profile.HIGH_RISK))
        assertEquals(general.paths.map { it.id }.toSet(), high.paths.map { it.id }.toSet())
        assertEquals(Severity.HIGH, general.paths.first().severity)
        assertEquals(Severity.CRITICAL, high.paths.first().severity)
        assertEquals(Severity.LOW, general.paths.single { it.id == "p6-advanced-protection" }.severity)
        assertEquals(Severity.HIGH, high.paths.single { it.id == "p6-advanced-protection" }.severity)
    }

    @Test
    fun eachStepSaysWhereItCameFromAndHowSureTheAppIsAndTheChainIsTheFactsOfOneDerivation() {
        val (report, told) = worstCase()
        val p1 = view(report, told).paths.single { it.id == "p1:shadow.app" }
        val texts = p1.steps.map { it.text }
        assertTrue(texts.toString(), texts.any { it.contains("Accessibility is switched on for shadow.app") })
        assertTrue(texts.any { it.contains("may read the text and layout") })
        assertTrue(p1.steps.any { it.provenance == Provenance.OBSERVED })
        assertTrue("the service's own statement", p1.steps.any { it.provenance == Provenance.DECLARED })
        assertTrue("nothing in this chain is the person's own word", p1.steps.none { it.provenance == Provenance.TOLD })
        assertFalse(p1.restsOnWhatYouSaid)
        assertTrue(p1.steps.any { it.provenance == Provenance.INFERRED })
        assertEquals("the weakest link", p1.steps.minOf { it.confidence }, p1.confidence)
    }

    @Test
    fun aPathThatRestsOnYourAnswerSaysSoAndAPathOnTheFactsAloneDoesNot() {
        val (report, told) = worstCase()
        val v = view(report, told)
        assertTrue(v.paths.single { it.id == "p2:shadow.app" }.restsOnWhatYouSaid)
        assertFalse(v.paths.single { it.id == "p4-no-lock" }.restsOnWhatYouSaid)
        assertEquals(Confidence.MEDIUM, v.paths.single { it.id == "p2:shadow.app" }.confidence)
    }

    @Test
    fun theChangesThatStopAPathByThemselvesAreListedGentlestFirst() {
        val (report, told) = worstCase()
        val v = view(report, told)
        assertEquals(listOf("clear-proxy"), v.paths.single { it.id == "p3-proxy" }.stoppedBy.map { it.id })
        assertEquals(listOf("set-lock"), v.paths.single { it.id == "p4-no-lock" }.stoppedBy.map { it.id })
        assertEquals(
            "moving the accounts or removing the app each stop the code path alone; turning off one of its three ways in does not",
            listOf("authenticator", "remove:shadow.app"), v.paths.single { it.id == "p2:shadow.app" }.stoppedBy.map { it.id },
        )
        assertEquals("a Wi-Fi path has two ways in, and one change closes both", listOf("private-dns-on"), v.paths.single { it.id == "p3-wifi" }.stoppedBy.map { it.id })
    }

    @Test
    fun aPathHeldOpenNamesWhatKeepsItOpenAndSaysWhenAnAnswerWouldSettleIt() {
        val report = phone(packages = listOf(sideloaded("spy.app")), listeners = listOf("spy.app"))
        val v = view(report, Declarations())
        assertEquals(0, v.paths.count { it.id.startsWith("p2:") })
        val open = v.notRuledOut.single { it.id == "p2:spy.app" }
        assertTrue(open.because.toString(), open.because.any { it.contains("You have not said whether your accounts send codes by text message") })
        assertTrue(open.needsAnAnswer)
    }

    @Test
    fun aPathHeldOpenByAnUnreadableSettingDoesNotAskForAnAnswer() {
        val report = phone(device = io.github.h_sib.declared.core.healthyDevice(lock = io.github.h_sib.declared.core.sensor.ReadResult.Refused("denied")))
        val open = view(report, Declarations()).notRuledOut.single { it.id == "p4-no-lock" }
        assertFalse(open.needsAnAnswer)
        assertTrue(open.because.single().contains("denied"))
    }

    @Test
    fun theSmallestPlanStopsEveryPathAndSaysHowManyItRemoves() {
        val (report, told) = worstCase()
        val v = view(report, told)
        val plan = v.plan!!
        assertTrue(plan.exact)
        assertEquals(7, plan.levers.size)
        assertEquals(v.pathCount, plan.cut.size)
        assertTrue(plan.unstoppable.isEmpty())
    }

    @Test
    fun noWordOnAnyPathScreenIsBannedOrADash() {
        val banned = listOf("malicious", "malware", "virus", "infected", "suspicious", "trojan", "spyware", "hacked", "compromised", "danger", "safe", "secure", "score", "rating", "protected")
        val (report, told) = worstCase()
        var checked = 0
        for (profile in Profile.entries) {
            val v = view(report, told.copy(profile = profile))
            val texts = v.paths.flatMap { p -> listOf(p.title, p.asset.label) + p.steps.map { it.text } + p.cannotSee + p.stoppedBy.flatMap { listOf(it.text, it.tradeOff) } } +
                v.notRuledOut.flatMap { listOf(it.title) + it.because } + v.plan!!.levers.flatMap { listOf(it.text, it.tradeOff) }
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
    fun theWhatIfOfThePlanLeavesNothingTrue() {
        val (report, told) = worstCase()
        val g = RuleBook.build(report, null, told)
        val v = PathsPresenter.present(g, told.profile)
        val p = Fixes.preview(g, v.plan!!.levers.map { it.id }.toSet())
        assertTrue(p.stillThere.isEmpty())
        assertEquals(v.pathCount, p.stopped.size)
    }

    @Test
    fun whenTheAnswersChangeThePathsChange() {
        val report = phone(packages = listOf(sideloaded("spy.app")), listeners = listOf("spy.app"))
        assertEquals(0, view(report, Declarations(smsCodes = Told.NO)).pathCount)
        assertEquals(1, view(report, Declarations(smsCodes = Told.YES)).pathCount)
    }
}
