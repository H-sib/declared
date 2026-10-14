package io.github.h_sib.declared.core.paths

import io.github.h_sib.declared.core.facts
import io.github.h_sib.declared.core.model.Declarations
import io.github.h_sib.declared.core.model.Told
import io.github.h_sib.declared.core.sensor.WatchedPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rule book and the solver on a phone far busier than any real one: the whole path from report to plan must stay fast. */
class PathsScaleTest {
    private fun busyPhone(apps: Int) = phone(
        packages = (0 until apps).map { i ->
            val base = if (i % 3 == 0) facts("app$i", installing = "com.android.vending") else sideloaded("app$i")
            if (i % 25 == 0) base.copy(requestedWatched = setOf(WatchedPermissions.READ_SMS), grantedWatched = setOf(WatchedPermissions.READ_SMS)) else base
        },
        a11y = (0 until apps).filter { it % 20 == 0 }.map { "app$it" },
        listeners = (0 until apps).filter { it % 10 == 0 }.map { "app$it" },
    )

    @Test
    fun aThousandAppsWithManyHoldersAreAnalysedAndPlannedInSeconds() {
        val report = busyPhone(1000)
        val told = Declarations(smsCodes = Told.YES, publicWifi = Told.NO)
        val started = System.nanoTime()
        val analysis = PathsAnalysis.of(report, null, told)
        val ms = (System.nanoTime() - started) / 1_000_000
        val plan = analysis.view.plan!!
        println("1,000 apps: ${analysis.graph.nodes.size} nodes, ${analysis.graph.levers.size} levers, ${analysis.view.pathCount} paths, plan of ${plan.levers.size} levers, exact=${plan.exact}, ${plan.evaluations} steps, $ms ms")
        assertTrue("took $ms ms", ms < 10_000)
        assertTrue(analysis.view.pathCount > 100)
        val after = analysis.preview(plan.levers.map { it.id }.toSet())
        assertEquals("every path that can be stopped is stopped by the plan", plan.unstoppable.map { it.node }, after.stillThere.map { it.node })
        assertEquals(plan.cut.size, after.stopped.size)
    }

    @Test
    fun theSameThousandAppsGiveTheSameAnswerTwice() {
        val told = Declarations(smsCodes = Told.YES)
        val a = PathsAnalysis.of(busyPhone(400), null, told).view
        val b = PathsAnalysis.of(busyPhone(400), null, told).view
        assertEquals(a.paths.map { it.id }, b.paths.map { it.id })
        assertEquals(a.plan!!.levers.map { it.id }, b.plan!!.levers.map { it.id })
    }
}
