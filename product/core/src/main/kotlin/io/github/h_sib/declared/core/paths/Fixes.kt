package io.github.h_sib.declared.core.paths

import java.util.PriorityQueue

/**
 * The smallest set of changes that stops the paths this app can see (STAGE2_DESIGN.md section 3).
 *
 * Choosing levers so that no target path stays true is a hitting-set problem and is NP-hard, so the search is exact while it
 * stays small and says so when it stops being exact:
 *
 * - **Exact.** A bounded search tree. If a target goal is still TRUE, one derivation (witness) of it exists whose leaves are all
 *   TRUE, and any fix must contain a lever that flips one of those leaves; branch on those levers. Levers tried and passed over
 *   are excluded from later siblings, so each candidate set is visited once. Witnesses that share no usable lever each need a
 *   lever of their own, which gives a lower bound that prunes branches that cannot finish in the depth left. Depth is raised one
 *   lever at a time, so the first depth that works is the minimum count, and within it the cheapest total cost wins, ties broken
 *   by lever ids.
 * - **Greedy.** When the search would exceed its budget the answer is the greedy one (most paths cut per lever, then the most
 *   true steps removed, then cheapest), with redundant levers removed, and the plan is labelled approximate.
 *
 * Paths no combination of levers can stop (every derivation rests only on facts nobody can change) are reported, not searched.
 *
 * Both searches work on arrays and change only the part of the graph above a flipped fact, so a search step costs the size of
 * that part and not of the whole graph.
 */
data class FixPlan(
    /** The levers to apply, ordered by id. */
    val levers: List<Lever>,
    /** Paths that are TRUE now and are not TRUE once the levers are applied. */
    val cut: List<Goal>,
    /** Paths that are TRUE now and stay TRUE with these levers. Only paths nothing can stop, unless the search was cut short. */
    val remaining: List<Goal>,
    /** Paths that nothing the app knows about would stop. */
    val unstoppable: List<Goal>,
    /** True when [levers] is a proven minimum; false when the search ran out of budget and this is the greedy answer. */
    val exact: Boolean,
    /** Search steps spent, for the record of when exact search stops being practical. */
    val evaluations: Int,
) {
    val totalCost: Int get() = levers.sumOf { it.cost }
}

object Fixes {
    /** Search steps the exact search may spend before the answer is labelled approximate (about one second on a graph of 1,800 nodes on a laptop; measured in `HardFixesTest`). Named here and in the screen's wording. */
    const val DEFAULT_BUDGET = 20_000

    fun minimum(graph: AttackGraph, baseline: Evaluation = Evaluator.evaluate(graph), budget: Int = DEFAULT_BUDGET, onlyGoals: Set<NodeId>? = null): FixPlan {
        val now = baseline.paths.filter { onlyGoals == null || it.node in onlyGoals }
        val everything = Evaluator.evaluate(graph, graph.levers.map { it.id }.toSet())
        val unstoppable = now.filter { everything.value(it.node) == Tri.TRUE }
        val targets = now - unstoppable.toSet()
        if (targets.isEmpty()) return FixPlan(emptyList(), emptyList(), unstoppable, unstoppable, exact = true, evaluations = 0)

        val search = Search(graph, targets, budget)
        val exactAnswer = search.exact()
        val chosen = exactAnswer ?: search.greedy()
        val finalEval = Evaluator.evaluate(graph, chosen.map { it.id }.toSet())
        return FixPlan(
            levers = chosen.sortedBy { it.id },
            cut = now.filter { finalEval.value(it.node) != Tri.TRUE },
            remaining = now.filter { finalEval.value(it.node) == Tri.TRUE },
            unstoppable = unstoppable,
            exact = exactAnswer != null,
            evaluations = search.steps,
        )
    }

    /** What happens to the paths if the person did these things: the same evaluation as the main screen, set against it. */
    data class Preview(
        val applied: Set<String>,
        val before: List<Goal>,
        val after: List<Goal>,
        /** True before and not true after. */
        val stopped: List<Goal>,
        /** Not true after, but held open by something unreadable. */
        val nowNotRuledOut: List<Goal>,
        val stillThere: List<Goal>,
    )

    fun preview(graph: AttackGraph, applied: Set<String>, baseline: Evaluation = Evaluator.evaluate(graph)): Preview {
        val after = Evaluator.evaluate(graph, applied)
        val before = baseline.paths
        val still = before.filter { after.value(it.node) == Tri.TRUE }
        return Preview(
            applied = applied,
            before = before,
            after = after.paths,
            stopped = before - still.toSet(),
            nowNotRuledOut = before.filter { after.value(it.node) == Tri.UNKNOWN },
            stillThere = still,
        )
    }

    private const val FALSE: Byte = 0
    private const val UNKNOWN: Byte = 1
    private const val TRUE: Byte = 2

    /** The graph as arrays, indexed by position in evaluation order (children before parents). */
    private class Fast(graph: AttackGraph) {
        val n = graph.order.size
        val index = HashMap<NodeId, Int>(n * 2).also { m -> graph.order.forEachIndexed { i, id -> m[id] = i } }
        val isLeaf = BooleanArray(n)
        val isAnd = BooleanArray(n)
        val kids: Array<IntArray> = Array(n) { IntArray(0) }
        val parents: Array<IntArray>
        val base = ByteArray(n)

        init {
            val up = Array(n) { ArrayList<Int>(2) }
            graph.order.forEachIndexed { i, id ->
                when (val node = graph.node(id)) {
                    is Leaf -> {
                        isLeaf[i] = true
                        base[i] = when (node.value) {
                            Tri.FALSE -> FALSE
                            Tri.UNKNOWN -> UNKNOWN
                            Tri.TRUE -> TRUE
                        }
                    }
                    is AndNode -> {
                        isAnd[i] = true
                        kids[i] = node.children.map { index.getValue(it) }.toIntArray()
                    }
                    is OrNode -> kids[i] = node.children.map { index.getValue(it) }.toIntArray()
                }
                for (k in kids[i]) up[k] += i
            }
            parents = Array(n) { up[it].toIntArray() }
            for (i in 0 until n) if (!isLeaf[i]) base[i] = compute(i, base)
        }

        fun compute(i: Int, v: ByteArray): Byte {
            var out: Byte = if (isAnd[i]) TRUE else FALSE
            for (k in kids[i]) {
                val x = v[k]
                if (isAnd[i]) { if (x < out) out = x } else { if (x > out) out = x }
            }
            return out
        }
    }

    /** A mutable assignment of values that can flip leaves, repair what is above them, and undo it all again. */
    private class State(val f: Fast) {
        val v: ByteArray = f.base.copyOf()
        private var undoIndex = IntArray(64)
        private var undoValue = ByteArray(64)
        var size = 0
            private set
        private val queued = BooleanArray(f.n)

        private fun record(i: Int) {
            if (size == undoIndex.size) {
                undoIndex = undoIndex.copyOf(size * 2)
                undoValue = undoValue.copyOf(size * 2)
            }
            undoIndex[size] = i
            undoValue[size] = v[i]
            size += 1
        }

        /** Makes these leaves FALSE and repairs the values above them. Returns the mark to [undo] to. */
        fun flip(leaves: IntArray): Int {
            val mark = size
            val queue = PriorityQueue<Int>()
            for (l in leaves) {
                if (v[l] != FALSE) {
                    record(l)
                    v[l] = FALSE
                    for (p in f.parents[l]) if (!queued[p]) { queued[p] = true; queue.add(p) }
                }
            }
            while (queue.isNotEmpty()) {
                val i = queue.poll()
                queued[i] = false
                val now = f.compute(i, v)
                if (now != v[i]) {
                    record(i)
                    v[i] = now
                    for (p in f.parents[i]) if (!queued[p]) { queued[p] = true; queue.add(p) }
                }
            }
            return mark
        }

        fun undo(mark: Int) {
            while (size > mark) {
                size -= 1
                v[undoIndex[size]] = undoValue[size]
            }
        }

        fun changedSince(mark: Int, each: (index: Int, before: Byte, after: Byte) -> Unit) {
            for (k in mark until size) each(undoIndex[k], undoValue[k], v[undoIndex[k]])
        }
    }

    private class Search(val graph: AttackGraph, val targetGoals: List<Goal>, val budget: Int) {
        var steps = 0
            private set

        private val f = Fast(graph)
        private val levers = graph.levers.sortedBy { it.id }
        private val leverLeaves: Array<IntArray> = Array(levers.size) { i -> levers[i].flips.map { f.index.getValue(it) }.toIntArray() }
        private val flipIndex: Map<Int, List<Int>> = HashMap<Int, MutableList<Int>>().also { m ->
            leverLeaves.forEachIndexed { i, leaves -> for (l in leaves) m.getOrPut(l) { ArrayList() } += i }
        }
        private val targets = targetGoals.map { f.index.getValue(it.node) }.toIntArray()
        private val isTarget = BooleanArray(f.n).also { for (t in targets) it[t] = true }

        /** Nodes that some target goal depends on: only these matter for deciding whether a lever makes progress. */
        private val inCone = BooleanArray(f.n).also { cone ->
            val stack = ArrayDeque<Int>()
            for (t in targets) if (!cone[t]) { cone[t] = true; stack.addLast(t) }
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                for (k in f.kids[i]) if (!cone[k]) { cone[k] = true; stack.addLast(k) }
            }
        }

        private class OutOfBudget : RuntimeException(null, null, false, false)

        private var stamp = IntArray(f.n)
        private var stampNow = 0

        /** The leaves of one derivation of a TRUE node: every child of an AND, the first TRUE child of an OR. */
        private fun witnessLeaves(root: Int, v: ByteArray): List<Int> {
            stampNow += 1
            val out = ArrayList<Int>()
            val stack = ArrayDeque<Int>()
            stack.addLast(root)
            while (stack.isNotEmpty()) {
                val i = stack.removeLast()
                if (stamp[i] == stampNow) continue
                stamp[i] = stampNow
                when {
                    f.isLeaf[i] -> out += i
                    f.isAnd[i] -> for (k in f.kids[i]) stack.addLast(k)
                    else -> stack.addLast(f.kids[i].first { v[it] == TRUE })
                }
            }
            return out
        }

        private var best: List<Int>? = null
        private var bestCost = Int.MAX_VALUE

        /** The proven minimum, or null when the budget ran out first. */
        fun exact(): List<Lever>? {
            try {
                var depth = 1
                while (depth <= levers.size) {
                    best = null
                    bestCost = Int.MAX_VALUE
                    dfs(State(f), ArrayList(), BooleanArray(levers.size), depth)
                    best?.let { return it.map { i -> levers[i] } }
                    depth += 1
                }
            } catch (e: OutOfBudget) {
                return null
            }
            return null
        }

        private fun dfs(state: State, applied: ArrayList<Int>, excluded: BooleanArray, depth: Int) {
            steps += 1
            if (steps > budget) throw OutOfBudget()
            val live = targets.filter { state.v[it] == TRUE }
            if (live.isEmpty()) {
                val cost = applied.sumOf { levers[it].cost }
                val key = applied.sorted()
                val current = best
                if (current == null || cost < bestCost || (cost == bestCost && lexLess(key, current))) {
                    best = key
                    bestCost = cost
                }
                return
            }
            if (applied.size >= depth) return
            var branch: List<Int>? = null
            val needs = ArrayList<List<Int>>(live.size)
            val inApplied = HashSet<Int>(applied)
            for (g in live) {
                val candidates = java.util.TreeSet<Int>()
                for (leaf in witnessLeaves(g, state.v)) flipIndex[leaf]?.forEach { if (!excluded[it] && it !in inApplied) candidates += it }
                val list = candidates.toList()
                if (list.isEmpty()) return // this witness can no longer be broken on this branch
                if (branch == null || list.size < branch.size) branch = list
                needs += list
            }
            // Lower bound: witnesses that share no usable lever each need a lever of their own.
            if (applied.size + disjointNeeds(needs) > depth) return
            val options = branch ?: return
            val newlyExcluded = ArrayList<Int>(options.size)
            for (c in options) {
                val mark = state.flip(leverLeaves[c])
                applied += c
                dfs(state, applied, excluded, depth)
                applied.removeAt(applied.size - 1)
                state.undo(mark)
                excluded[c] = true
                newlyExcluded += c
            }
            for (c in newlyExcluded) excluded[c] = false
        }

        /** The size of a greedy family of lever sets that are pairwise disjoint: at least that many more levers are needed. */
        private fun disjointNeeds(needs: List<List<Int>>): Int {
            val used = HashSet<Int>()
            var count = 0
            for (n in needs.sortedBy { it.size }) {
                if (n.none { it in used }) {
                    used += n
                    count += 1
                }
            }
            return count
        }

        private fun lexLess(a: List<Int>, b: List<Int>): Boolean {
            for (i in 0 until minOf(a.size, b.size)) if (a[i] != b[i]) return a[i] < b[i]
            return a.size < b.size
        }

        /** Most paths cut per lever, then the most true steps removed, then the cheapest; redundant levers dropped at the end. */
        fun greedy(): List<Lever> {
            val state = State(f)
            val applied = ArrayList<Int>()
            fun live() = targets.count { state.v[it] == TRUE }
            while (live() > 0) {
                // Only a lever that changes a fact some live path stands on can make progress.
                val useful = java.util.TreeSet<Int>()
                for (leaf in 0 until f.n) {
                    if (f.isLeaf[leaf] && state.v[leaf] == TRUE && inCone[leaf]) flipIndex[leaf]?.forEach { if (it !in applied) useful += it }
                }
                if (useful.isEmpty()) break
                var bestIndex = -1
                var bestCut = 0
                var bestRemoved = 0
                var bestCost = 0
                for (i in useful) {
                    val mark = state.flip(leverLeaves[i])
                    var cut = 0
                    var removed = 0
                    state.changedSince(mark) { idx, before, after ->
                        if (before == TRUE && after != TRUE) {
                            if (isTarget[idx]) cut += 1
                            if (inCone[idx]) removed += 1
                        }
                    }
                    state.undo(mark)
                    val cost = levers[i].cost
                    val better = bestIndex < 0 || cut > bestCut || (cut == bestCut && (removed > bestRemoved || (removed == bestRemoved && cost < bestCost)))
                    if (better) {
                        bestIndex = i
                        bestCut = cut
                        bestRemoved = removed
                        bestCost = cost
                    }
                }
                state.flip(leverLeaves[bestIndex])
                applied += bestIndex
            }
            // Drop anything the others make unnecessary, most expensive first, so the answer is irredundant.
            for (i in applied.sortedByDescending { levers[it].cost }) {
                val without = applied.filter { it != i }
                val check = State(f)
                for (j in without) check.flip(leverLeaves[j])
                if (targets.none { check.v[it] == TRUE }) applied.remove(i)
            }
            return applied.map { levers[it] }
        }
    }
}
