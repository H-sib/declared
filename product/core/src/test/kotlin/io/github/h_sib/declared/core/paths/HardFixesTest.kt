package io.github.h_sib.declared.core.paths

import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Instances built to be hard: every goal is an OR of several ANDs over a shared pool of leaves, so the fix is a hitting set
 * (every AND of every goal needs one of its leaves flipped), which is the NP-hard shape the solver exists for. The random
 * graphs in [FixesTest] are mostly trivially FALSE and say little about scale; these do.
 */
class HardFixesTest {
    private fun hard(random: Random, pool: Int, goals: Int, ways: Int, perWay: Int, extraLevers: Int, trueShare: Double = 0.9): AttackGraph {
        val b = GraphBuilder()
        val leaves = (0 until pool).map { i ->
            b.leaf("l$i", "fact $i", if (random.nextDouble() < trueShare) Tri.TRUE else Tri.FALSE, Provenance.OBSERVED, Confidence.HIGH)
        }
        repeat(goals) { g ->
            val alternatives = (0 until ways).map { w ->
                val picked = LinkedHashSet<NodeId>()
                while (picked.size < perWay) picked += leaves[random.nextInt(pool)]
                b.and("g$g-w$w", "way $w of goal $g", picked.toList())
            }
            b.goal(RandomGraphs.goalOn(b.or("g$g", "goal $g", alternatives)))
        }
        val covered = HashSet<NodeId>()
        repeat(extraLevers) { i ->
            val flips = LinkedHashSet<NodeId>()
            repeat(1 + random.nextInt(2)) { flips += leaves[random.nextInt(pool)] }
            covered += flips
            b.lever(Lever("x$i", "extra $i", "costs $i", 1 + random.nextInt(3), flips))
        }
        // Every fact has some lever that can change it, so every goal can be stopped and the search is the whole question.
        for ((i, leaf) in leaves.withIndex()) if (leaf !in covered) b.lever(Lever("s$i", "single $i", "costs $i", 2, setOf(leaf)))
        return b.build()
    }

    private fun stoppedAll(g: AttackGraph, plan: FixPlan): Boolean {
        val after = Evaluator.evaluate(g, plan.levers.map { it.id }.toSet())
        return Evaluator.evaluate(g).paths.all { it in plan.unstoppable || after.value(it.node) != Tri.TRUE }
    }

    /** The oracle: every subset of levers, smallest count then cheapest then first. Only for instances small enough to enumerate. */
    private fun bruteForce(g: AttackGraph): List<String>? {
        val base = Evaluator.evaluate(g)
        val targets = base.paths
        if (targets.isEmpty()) return emptyList()
        val levers = g.levers.sortedBy { it.id }
        var best: List<Int>? = null
        var bestCost = Int.MAX_VALUE
        for (mask in 1 until (1 shl levers.size)) {
            val chosen = (0 until levers.size).filter { mask and (1 shl it) != 0 }
            if (best != null && chosen.size > best.size) continue
            val e = Evaluator.evaluate(g, chosen.map { levers[it].id }.toSet())
            if (targets.any { e.value(it.node) == Tri.TRUE }) continue
            val cost = chosen.sumOf { levers[it].cost }
            val better = best == null || chosen.size < best.size ||
                (chosen.size == best.size && (cost < bestCost || (cost == bestCost && lexLess(chosen, best))))
            if (better) {
                best = chosen
                bestCost = cost
            }
        }
        return best?.map { levers[it].id }
    }

    private fun lexLess(a: List<Int>, b: List<Int>): Boolean {
        for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return a[i] < b[i]
        return a.size < b.size
    }

    @Test
    fun theExactSearchAgreesWithBruteForceOnSmallHardInstances() {
        val random = Random(20261020)
        var multi = 0
        repeat(60) { run ->
            val g = hard(random, pool = 10, goals = 3, ways = 3, perWay = 2, extraLevers = 3)
            if (g.levers.size > 15) return@repeat
            val expected = bruteForce(g)!!
            val plan = Fixes.minimum(g)
            assertTrue("run $run", plan.exact)
            assertEquals("run $run", expected, plan.levers.map { it.id })
            if (expected.size >= 3) multi += 1
        }
        assertTrue("only $multi instances needed three or more levers", multi > 20)
    }

    @Test
    fun theLowerBoundPrunesWithoutChangingTheAnswer() {
        // The same instances must give the same fix whatever the budget allows, as long as the search finishes.
        val random = Random(20261021)
        repeat(40) { run ->
            val g = hard(random, pool = 14, goals = 4, ways = 3, perWay = 2, extraLevers = 4)
            val small = Fixes.minimum(g, budget = 500_000)
            val large = Fixes.minimum(g, budget = 5_000_000)
            assertTrue("run $run", small.exact && large.exact)
            assertEquals("run $run", small.levers.map { it.id }, large.levers.map { it.id })
            assertTrue("run $run: fix does not stop everything", stoppedAll(g, small))
        }
    }

    @Test
    fun greedyStaysCloseToTheMinimumOnMidSizeHardInstances() {
        val random = Random(20261022)
        var exactTotal = 0
        var greedyTotal = 0
        var worst = 1.0
        var exactCount = 0
        repeat(30) { run ->
            val g = hard(random, pool = 28, goals = 6, ways = 3, perWay = 2, extraLevers = 10)
            val exact = Fixes.minimum(g, budget = 400_000)
            val greedy = Fixes.minimum(g, budget = 1)
            if (!exact.exact) return@repeat // too big to prove at this budget: not a data point for the ratio
            exactCount += 1
            assertFalse(greedy.exact)
            assertTrue("run $run", stoppedAll(g, greedy))
            assertTrue("run $run: greedy is smaller than a proven minimum", greedy.levers.size >= exact.levers.size)
            exactTotal += exact.levers.size
            greedyTotal += greedy.levers.size
            worst = maxOf(worst, greedy.levers.size.toDouble() / exact.levers.size)
        }
        println("greedy against proven minimum on $exactCount hard instances: $greedyTotal levers against $exactTotal, worst ratio $worst")
        assertTrue("only $exactCount instances could be proven", exactCount >= 15)
        assertTrue("greedy used $greedyTotal against $exactTotal", greedyTotal <= exactTotal * 1.25)
        assertTrue("worst ratio $worst", worst <= 1.6)
    }

    @Test
    fun largeInstancesAreAlwaysAnsweredInTimeAndAreLabelledExactOnlyWhenProven() {
        val random = Random(20261023)
        val rows = StringBuilder()
        var approximate = 0
        for ((pool, goals, ways) in listOf(Triple(40, 10, 4), Triple(150, 20, 5), Triple(400, 50, 6), Triple(900, 100, 8))) {
            repeat(3) {
                val g = hard(random, pool = pool, goals = goals, ways = ways, perWay = 3, extraLevers = pool / 4)
                val started = System.nanoTime()
                val plan = Fixes.minimum(g)
                val ms = (System.nanoTime() - started) / 1_000_000
                if (!plan.exact) approximate += 1
                rows.append("${g.nodes.size} nodes, ${g.levers.size} levers, ${Evaluator.evaluate(g).paths.size} true paths: ${plan.levers.size} levers, exact=${plan.exact}, ${plan.evaluations} search steps, $ms ms\n")
                assertTrue("the plan does not stop every stoppable path (${g.nodes.size} nodes)", stoppedAll(g, plan))
                assertTrue("took $ms ms for ${g.nodes.size} nodes", ms < 15_000)
                assertTrue(plan.evaluations <= Fixes.DEFAULT_BUDGET + 1)
            }
        }
        println("hard scale results (budget ${Fixes.DEFAULT_BUDGET} search steps):\n$rows")
        assertTrue("the budget never mattered, so this test says nothing about the fallback:\n$rows", approximate > 0)
    }
}
