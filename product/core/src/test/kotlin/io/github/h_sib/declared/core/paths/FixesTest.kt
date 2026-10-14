package io.github.h_sib.declared.core.paths

import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FixesTest {
    private fun leaf(b: GraphBuilder, id: String, v: Tri = Tri.TRUE) =
        b.leaf(id, "fact $id", v, Provenance.OBSERVED, Confidence.HIGH, reason = if (v == Tri.UNKNOWN) "unreadable" else null)

    private fun lever(id: String, cost: Int, flip: NodeId, vararg more: String) = Lever(id, "do $id", "costs something", cost, setOf(flip) + more.map { NodeId(it) })

    /** The independent oracle: every subset of levers, the smallest that stops every stoppable path, the cheapest of those, the first by index order. */
    private fun bruteForce(graph: AttackGraph): List<String>? {
        val base = Evaluator.evaluate(graph)
        val all = Evaluator.evaluate(graph, graph.levers.map { it.id }.toSet())
        val targets = base.paths.filter { all.value(it.node) != Tri.TRUE }
        if (targets.isEmpty()) return emptyList()
        val levers = graph.levers.sortedBy { it.id }
        var best: List<Int>? = null
        var bestCost = Int.MAX_VALUE
        for (mask in 1 until (1 shl levers.size)) {
            val chosen = (0 until levers.size).filter { mask and (1 shl it) != 0 }
            val e = Evaluator.evaluate(graph, chosen.map { levers[it].id }.toSet())
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

    // ------------------------------------------------------------ hand-built cases

    @Test
    fun oneLeverThatCutsTwoPathsBeatsTwoThatCutOneEach() {
        val b = GraphBuilder()
        val shared = leaf(b, "shared")
        val a = leaf(b, "a")
        val c = leaf(b, "c")
        b.goal(RandomGraphs.goalOn(b.and("g1", "g1", shared, a)))
        b.goal(RandomGraphs.goalOn(b.and("g2", "g2", shared, c)))
        b.lever(lever("off-shared", 3, shared))
        b.lever(lever("off-a", 1, a))
        b.lever(lever("off-c", 1, c))
        val plan = Fixes.minimum(b.build())
        assertEquals(listOf("off-shared"), plan.levers.map { it.id })
        assertEquals(2, plan.cut.size)
        assertTrue(plan.exact)
        assertTrue(plan.remaining.isEmpty())
    }

    @Test
    fun anOrNeedsEveryWayInClosedSoTheFixSetIsSeveralLevers() {
        val b = GraphBuilder()
        val one = leaf(b, "one")
        val two = leaf(b, "two")
        val three = leaf(b, "three")
        b.goal(RandomGraphs.goalOn(b.or("g", "g", one, two, three)))
        b.lever(lever("f1", 1, one))
        b.lever(lever("f2", 1, two))
        b.lever(lever("f3", 1, three))
        val plan = Fixes.minimum(b.build())
        assertEquals(listOf("f1", "f2", "f3"), plan.levers.map { it.id })
    }

    @Test
    fun withEqualCountTheGentlerFixWins() {
        val b = GraphBuilder()
        val a = leaf(b, "a")
        b.goal(RandomGraphs.goalOn(b.or("g", "g", a)))
        b.lever(lever("harsh", 3, a))
        b.lever(lever("gentle", 1, a))
        assertEquals(listOf("gentle"), Fixes.minimum(b.build()).levers.map { it.id })
    }

    @Test
    fun aPathNothingCanStopIsReportedAndNotSearched() {
        val b = GraphBuilder()
        val fixable = leaf(b, "fixable")
        val fact = leaf(b, "fact-nobody-can-change")
        b.goal(RandomGraphs.goalOn(b.and("stoppable", "stoppable", fixable)))
        b.goal(RandomGraphs.goalOn(b.and("unstoppable", "unstoppable", fact)))
        b.lever(lever("fix", 1, fixable))
        val plan = Fixes.minimum(b.build())
        assertEquals(listOf("fix"), plan.levers.map { it.id })
        assertEquals(listOf("unstoppable"), plan.unstoppable.map { it.node.value })
        assertEquals(listOf("unstoppable"), plan.remaining.map { it.node.value })
        assertEquals(listOf("stoppable"), plan.cut.map { it.node.value })
    }

    @Test
    fun nothingTrueMeansNothingToDo() {
        val b = GraphBuilder()
        val no = leaf(b, "no", Tri.FALSE)
        val unknown = leaf(b, "unknown", Tri.UNKNOWN)
        b.goal(RandomGraphs.goalOn(b.and("g", "g", no, unknown)))
        b.lever(lever("f", 1, no))
        val plan = Fixes.minimum(b.build())
        assertTrue(plan.levers.isEmpty() && plan.cut.isEmpty() && plan.remaining.isEmpty())
    }

    @Test
    fun aGoalThatIsOnlyNotRuledOutIsNotATargetBecauseItIsNotAClaim() {
        val b = GraphBuilder()
        val yes = leaf(b, "yes")
        val unknown = leaf(b, "unknown", Tri.UNKNOWN)
        b.goal(RandomGraphs.goalOn(b.and("open", "open", yes, unknown)))
        b.lever(lever("f", 1, yes))
        assertTrue(Fixes.minimum(b.build()).levers.isEmpty())
    }

    @Test
    fun theWhatIfPreviewIsTheSameEvaluationSetAgainstTheCurrentOne() {
        val b = GraphBuilder()
        val a = leaf(b, "a")
        val c = leaf(b, "c")
        val u = leaf(b, "u", Tri.UNKNOWN)
        b.goal(RandomGraphs.goalOn(b.and("g1", "g1", a)))
        b.goal(RandomGraphs.goalOn(b.or("g2", "g2", c, u)))
        b.goal(RandomGraphs.goalOn(b.and("g3", "g3", c)))
        b.lever(lever("off-a", 1, a))
        b.lever(lever("off-c", 1, c))
        val g = b.build()
        val p = Fixes.preview(g, setOf("off-c"))
        assertEquals(listOf("g1", "g2", "g3"), p.before.map { it.node.value })
        assertEquals(listOf("g1"), p.after.map { it.node.value })
        assertEquals(listOf("g2", "g3"), p.stopped.map { it.node.value })
        assertEquals("g2 is no longer true but is not ruled out: the unreadable fact remains", listOf("g2"), p.nowNotRuledOut.map { it.node.value })
        assertEquals(listOf("g1"), p.stillThere.map { it.node.value })
        assertEquals(Fixes.preview(g, emptySet()).stopped, emptyList<Goal>())
    }

    // ------------------------------------------------------------ against the oracle

    @Test
    fun theExactSearchAgreesWithBruteForceOnThousandsOfRandomGraphs() {
        val random = Random(20261014)
        var checked = 0
        var nonTrivial = 0
        repeat(2500) { run ->
            val g = RandomGraphs.make(random, leaves = 10, internals = 10 + random.nextInt(6), goals = 1 + random.nextInt(4), levers = 4 + random.nextInt(7))
            val expected = bruteForce(g) ?: return@repeat
            val plan = Fixes.minimum(g)
            assertTrue("run $run: search ran out of budget on a tiny graph", plan.exact)
            assertEquals("run $run", expected, plan.levers.map { it.id })
            // The fix really does stop every stoppable path.
            val after = Evaluator.evaluate(g, plan.levers.map { it.id }.toSet())
            for (goal in Evaluator.evaluate(g).paths - plan.unstoppable.toSet()) assertFalse("run $run: ${goal.node} is still true", after.value(goal.node) == Tri.TRUE)
            checked += 1
            if (expected.size >= 2) nonTrivial += 1
        }
        assertTrue("only $checked graphs had anything to fix", checked > 1500)
        assertTrue("only $nonTrivial needed two or more levers, so the search was barely exercised", nonTrivial > 300)
    }

    @Test
    fun theGreedyAnswerIsAlwaysAValidFixNeverSmallerThanTheMinimumAndCloseToIt() {
        val random = Random(20261015)
        var exactTotal = 0
        var greedyTotal = 0
        var worst = 1.0
        repeat(1500) { run ->
            val g = RandomGraphs.make(random, leaves = 12, internals = 14, goals = 3, levers = 8)
            val exact = Fixes.minimum(g)
            val greedy = Fixes.minimum(g, budget = 1) // no room to search: the greedy answer
            if (exact.levers.isEmpty()) return@repeat
            assertTrue("run $run", exact.exact)
            assertFalse("run $run: a one-evaluation budget cannot be exact", greedy.exact)
            assertTrue("run $run: greedy used fewer levers than the proven minimum", greedy.levers.size >= exact.levers.size)
            val after = Evaluator.evaluate(g, greedy.levers.map { it.id }.toSet())
            for (goal in Evaluator.evaluate(g).paths - greedy.unstoppable.toSet()) assertFalse("run $run: greedy left ${goal.node} true", after.value(goal.node) == Tri.TRUE)
            exactTotal += exact.levers.size
            greedyTotal += greedy.levers.size
            worst = maxOf(worst, greedy.levers.size.toDouble() / exact.levers.size)
        }
        // Measured, then pinned with room to spare: on graphs of this shape greedy stays within a few percent of the minimum.
        assertTrue("greedy used $greedyTotal levers against $exactTotal", greedyTotal <= exactTotal * 1.15)
        assertTrue("worst single ratio $worst", worst <= 2.0)
    }

    @Test
    fun aGreedyAnswerHasNoRedundantLever() {
        val random = Random(20261016)
        repeat(500) { run ->
            val g = RandomGraphs.make(random, leaves = 12, internals = 14, goals = 3, levers = 8)
            val plan = Fixes.minimum(g, budget = 1)
            for (drop in plan.levers) {
                val rest = plan.levers.filter { it != drop }.map { it.id }.toSet()
                val e = Evaluator.evaluate(g, rest)
                val stillStopped = (Evaluator.evaluate(g).paths - plan.unstoppable.toSet()).none { e.value(it.node) == Tri.TRUE }
                assertFalse("run $run: ${drop.id} is not needed", stillStopped)
            }
        }
    }
}
