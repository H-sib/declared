package io.github.h_sib.declared.core.paths

import io.github.h_sib.declared.core.model.Profile
import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class GraphTest {
    private fun leaf(b: GraphBuilder, id: String, v: Tri, c: Confidence = Confidence.HIGH, p: Provenance = Provenance.OBSERVED) =
        b.leaf(id, "fact $id", v, p, c, reason = if (v == Tri.UNKNOWN) "unreadable $id" else null)

    // ------------------------------------------------------------ three-valued logic

    @Test
    fun theTablesOfAndAndOrAreKleenes() {
        val f = Tri.FALSE
        val u = Tri.UNKNOWN
        val t = Tri.TRUE
        val and = mapOf((f to f) to f, (f to u) to f, (f to t) to f, (u to u) to u, (u to t) to u, (t to t) to t)
        val or = mapOf((f to f) to f, (f to u) to u, (f to t) to t, (u to u) to u, (u to t) to t, (t to t) to t)
        for ((pair, expected) in and) {
            assertEquals("$pair and", expected, pair.first and pair.second)
            assertEquals("${pair.second to pair.first} and (commutes)", expected, pair.second and pair.first)
        }
        for ((pair, expected) in or) {
            assertEquals("$pair or", expected, pair.first or pair.second)
            assertEquals("${pair.second to pair.first} or (commutes)", expected, pair.second or pair.first)
        }
        assertEquals(Tri.TRUE, Tri.of(true))
        assertEquals(Tri.FALSE, Tri.of(false))
        assertEquals(Tri.UNKNOWN, Tri.of(null))
    }

    // ------------------------------------------------------------ building

    private fun refused(message: String, build: GraphBuilder.() -> Unit) {
        try {
            GraphBuilder().apply(build).build()
            fail("accepted: $message")
        } catch (e: IllegalArgumentException) {
            // GraphException is an IllegalArgumentException, and so are the node and lever checks
        }
    }

    @Test
    fun aGraphThatIsMalformedIsRefusedInsteadOfBuiltWrong() {
        refused("duplicate id") {
            leaf("a", "a", Tri.TRUE, Provenance.OBSERVED, Confidence.HIGH)
            leaf("a", "a", Tri.FALSE, Provenance.OBSERVED, Confidence.HIGH)
        }
        refused("missing child") { and("x", "x", NodeId("ghost")) }
        refused("goal on nothing") { goal(RandomGraphs.goalOn(NodeId("ghost"))) }
        refused("lever on an inner node") {
            val a = leaf("a", "a", Tri.TRUE, Provenance.OBSERVED, Confidence.HIGH)
            val x = or("x", "x", a)
            lever(Lever("f", "f", "t", 1, setOf(x)))
        }
        refused("duplicate lever") {
            val a = leaf("a", "a", Tri.TRUE, Provenance.OBSERVED, Confidence.HIGH)
            lever(Lever("f", "f", "t", 1, setOf(a)))
            lever(Lever("f", "f2", "t", 1, setOf(a)))
        }
        refused("child listed twice") {
            val a = leaf("a", "a", Tri.TRUE, Provenance.OBSERVED, Confidence.HIGH)
            and("x", "x", a, a)
        }
        refused("two goals on one node") {
            val a = leaf("a", "a", Tri.TRUE, Provenance.OBSERVED, Confidence.HIGH)
            goal(RandomGraphs.goalOn(a))
            goal(RandomGraphs.goalOn(a))
        }
        refused("empty AND") { and("x", "x") }
        refused("empty OR") { or("x", "x", emptyList()) }
        refused("an unknown leaf with no reason") { leaf("u", "u", Tri.UNKNOWN, Provenance.OBSERVED, Confidence.HIGH) }
        refused("a lever with nothing to flip") { lever(Lever("f", "f", "t", 1, emptySet())) }
        refused("a lever that costs nothing") {
            val a = leaf("a", "a", Tri.TRUE, Provenance.OBSERVED, Confidence.HIGH)
            lever(Lever("f", "f", "t", 0, setOf(a)))
        }
        refused("a goal without a severity for every profile") {
            val a = leaf("a", "a", Tri.TRUE, Provenance.OBSERVED, Confidence.HIGH)
            goal(Goal(a, "g", Asset.ACCOUNT, emptyList(), mapOf(Profile.GENERAL to Severity.LOW), emptyList()))
        }
    }

    @Test
    fun aCycleIsRefused() {
        // Children must exist when a node names them, so a loop can only come from a forward reference, which is a missing child
        // for the builder; the sorter's own cycle check is reached by two nodes naming each other once both exist.
        val b = GraphBuilder()
        b.leaf("seed", "seed", Tri.TRUE, Provenance.OBSERVED, Confidence.HIGH)
        b.and("first", "first", NodeId("second"))
        b.and("second", "second", NodeId("first"))
        try {
            b.build()
            fail("accepted a cycle")
        } catch (e: GraphException) {
            assertTrue(e.message!!, e.message!!.contains("cycle"))
        }
    }

    @Test
    fun aVeryDeepChainEvaluatesWithoutRunningOutOfStack() {
        val b = GraphBuilder()
        var previous = leaf(b, "start", Tri.TRUE)
        repeat(20_000) { i -> previous = b.and("n$i", "step $i", previous) }
        b.goal(RandomGraphs.goalOn(previous))
        val e = Evaluator.evaluate(b.build())
        assertEquals(Tri.TRUE, e.value(previous))
        assertEquals(Confidence.HIGH, e.confidence(previous))
    }

    // ------------------------------------------------------------ evaluating

    @Test
    fun andNeedsEveryChildAndOrNeedsOne() {
        val b = GraphBuilder()
        val t = leaf(b, "t", Tri.TRUE)
        val f = leaf(b, "f", Tri.FALSE)
        val both = b.and("both", "both", t, f)
        val either = b.or("either", "either", t, f)
        val e = Evaluator.evaluate(b.build())
        assertEquals(Tri.FALSE, e.value(both))
        assertEquals(Tri.TRUE, e.value(either))
    }

    @Test
    fun anUnreadableFactKeepsAPathOpenAndNamesItself() {
        val b = GraphBuilder()
        val known = leaf(b, "known", Tri.TRUE)
        val unreadable = leaf(b, "unreadable", Tri.UNKNOWN)
        val path = b.and("path", "path", known, unreadable)
        b.goal(RandomGraphs.goalOn(path))
        val e = Evaluator.evaluate(b.build())
        assertEquals(Tri.UNKNOWN, e.value(path))
        assertEquals(setOf(unreadable), e.openedBy(path))
        assertEquals(emptyList<Goal>(), e.paths)
        assertEquals(listOf(path), e.notRuledOut.map { it.node })
        assertNull("only a true node has a confidence", e.confidence(path))
        assertNull(e.witness(path))
    }

    @Test
    fun aFalseChildMakesAnAndFalseEvenWhenAnotherChildIsUnreadable() {
        val b = GraphBuilder()
        val no = leaf(b, "no", Tri.FALSE)
        val unreadable = leaf(b, "unreadable", Tri.UNKNOWN)
        val path = b.and("path", "path", no, unreadable)
        val e = Evaluator.evaluate(b.build())
        assertEquals("a path ruled out by one readable fact does not stay open for another", Tri.FALSE, e.value(path))
        assertEquals(emptySet<NodeId>(), e.openedBy(path))
    }

    @Test
    fun anOrWithATrueWayInIsTrueEvenIfAnotherWayIsUnreadable() {
        val b = GraphBuilder()
        val yes = leaf(b, "yes", Tri.TRUE)
        val unreadable = leaf(b, "unreadable", Tri.UNKNOWN)
        val path = b.or("path", "path", unreadable, yes)
        val e = Evaluator.evaluate(b.build())
        assertEquals(Tri.TRUE, e.value(path))
    }

    @Test
    fun confidenceIsTheWeakestLinkOfAnAndAndTheBestWayOfAnOr() {
        val b = GraphBuilder()
        val high = leaf(b, "high", Tri.TRUE, Confidence.HIGH)
        val low = leaf(b, "low", Tri.TRUE, Confidence.LOW)
        val medium = leaf(b, "medium", Tri.TRUE, Confidence.MEDIUM)
        val chain = b.and("chain", "chain", high, low, medium)
        val ways = b.or("ways", "ways", low, medium)
        val mixed = b.and("mixed", "mixed", high, ways)
        val e = Evaluator.evaluate(b.build())
        assertEquals(Confidence.LOW, e.confidence(chain))
        assertEquals(Confidence.MEDIUM, e.confidence(ways))
        assertEquals(Confidence.MEDIUM, e.confidence(mixed))
        assertEquals("the best way in is the medium one", medium, e.witness(ways)!!.children.single().node)
    }

    @Test
    fun anOrThatTiesOnConfidenceChoosesTheFirstListedWayEveryTime() {
        val b = GraphBuilder()
        val a = leaf(b, "a", Tri.TRUE, Confidence.MEDIUM)
        val c = leaf(b, "c", Tri.TRUE, Confidence.MEDIUM)
        val ways = b.or("ways", "ways", c, a)
        val e = Evaluator.evaluate(b.build())
        assertEquals(c, e.witness(ways)!!.children.single().node)
    }

    @Test
    fun aPathSaysWhenItRestsOnWhatThePersonToldTheApp() {
        val b = GraphBuilder()
        val seen = leaf(b, "seen", Tri.TRUE)
        val told = leaf(b, "told", Tri.TRUE, Confidence.MEDIUM, Provenance.DECLARED)
        val path = b.and("path", "path", seen, told)
        val observedOnly = b.and("observedOnly", "observed only", seen)
        val e = Evaluator.evaluate(b.build())
        assertTrue(e.usesProvenance(path, Provenance.DECLARED))
        assertFalse(e.usesProvenance(observedOnly, Provenance.DECLARED))
        assertTrue(e.usesProvenance(observedOnly, Provenance.OBSERVED))
    }

    @Test
    fun aLeverMakesItsLeavesFalseAndNothingElse() {
        val b = GraphBuilder()
        val a = leaf(b, "a", Tri.TRUE)
        val c = leaf(b, "c", Tri.TRUE)
        val path = b.and("path", "path", a, c)
        val other = b.or("other", "other", c)
        b.goal(RandomGraphs.goalOn(path))
        b.lever(Lever("off-a", "turn a off", "cost", 1, setOf(a)))
        val g = b.build()
        val before = Evaluator.evaluate(g)
        val after = Evaluator.evaluate(g, setOf("off-a"))
        assertEquals(Tri.TRUE, before.value(path))
        assertEquals(Tri.FALSE, after.value(path))
        assertEquals("c is untouched", Tri.TRUE, after.value(other))
        assertEquals(setOf("off-a"), after.applied)
        try {
            Evaluator.evaluate(g, setOf("ghost"))
            fail("an unknown lever must not be ignored")
        } catch (e: GraphException) {
            assertTrue(e.message!!.contains("ghost"))
        }
    }

    // ------------------------------------------------------------ properties on random graphs

    @Test
    fun applyingMoreLeversNeverMakesAnythingMoreTrue() {
        val random = Random(20261011)
        repeat(300) { run ->
            val g = RandomGraphs.make(random, leaves = 12, internals = 14, goals = 4, levers = 6)
            var applied = emptySet<String>()
            var previous = Evaluator.evaluate(g, applied)
            for (id in g.levers.map { it.id }.shuffled(random)) {
                applied = applied + id
                val next = Evaluator.evaluate(g, applied)
                for (n in g.order) {
                    assertTrue("run $run node $n went from ${previous.value(n)} to ${next.value(n)} after $id", next.value(n) <= previous.value(n))
                }
                previous = next
            }
        }
    }

    @Test
    fun aWitnessIsEnoughAndEveryLeafInItIsTrue() {
        val random = Random(20261012)
        repeat(300) { run ->
            val g = RandomGraphs.make(random, leaves = 14, internals = 16, goals = 5, levers = 0)
            val e = Evaluator.evaluate(g)
            for (goal in g.goals) {
                val w = e.witness(goal.node) ?: continue
                assertEquals(Tri.TRUE, e.value(goal.node))
                for (leaf in w.leaves) assertEquals("run $run leaf $leaf of the witness", Tri.TRUE, e.value(leaf))
                // Sufficiency: with every leaf outside the witness set FALSE, the goal is still TRUE.
                val b = GraphBuilder()
                for (n in g.order) {
                    when (val node = g.node(n)) {
                        is Leaf -> b.leaf(node.id.value, node.text, if (node.id in w.leaves) Tri.TRUE else Tri.FALSE, node.provenance, node.confidence)
                        is AndNode -> b.and(node.id.value, node.text, node.children)
                        is OrNode -> b.or(node.id.value, node.text, node.children)
                    }
                }
                val alone = Evaluator.evaluate(b.build())
                assertEquals("run $run: the witness of ${goal.node} is not enough on its own", Tri.TRUE, alone.value(goal.node))
            }
        }
    }

    @Test
    fun aTrueGoalHasAConfidenceAndAWitnessAndAnUnknownOneHasACause() {
        val random = Random(20261013)
        repeat(300) {
            val g = RandomGraphs.make(random, leaves = 12, internals = 14, goals = 6, levers = 0, unknownShare = 0.3)
            val e = Evaluator.evaluate(g)
            for (goal in g.goals) {
                when (e.value(goal.node)) {
                    Tri.TRUE -> {
                        assertNotNull(e.confidence(goal.node))
                        assertNotNull(e.witness(goal.node))
                    }
                    Tri.UNKNOWN -> {
                        assertTrue(e.openedBy(goal.node).isNotEmpty())
                        assertTrue(e.openedBy(goal.node).all { (g.node(it) as Leaf).value == Tri.UNKNOWN })
                    }
                    Tri.FALSE -> {
                        assertNull(e.confidence(goal.node))
                        assertTrue(e.openedBy(goal.node).isEmpty())
                    }
                }
            }
        }
    }
}
