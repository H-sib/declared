package io.github.h_sib.declared.core.paths

import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.Profile
import io.github.h_sib.declared.core.model.Provenance
import io.github.h_sib.declared.core.model.SettingsLink

/**
 * The attack-path model (STAGE2_DESIGN.md sections 2 and 3): an AND/OR graph over three-valued facts. Nothing here knows
 * about Android, apps or settings; the rule book (`RuleBook.kt`) is what turns a phone into a graph, so the engine can be
 * tested on graphs of any shape and reused for other sensors.
 */

/**
 * Kleene's three-valued logic. UNKNOWN is a real value: an unreadable signal makes its leaf UNKNOWN, and a path that rests
 * on it is "not ruled out", never "absent". The order FALSE < UNKNOWN < TRUE makes AND the minimum and OR the maximum.
 */
enum class Tri {
    FALSE,
    UNKNOWN,
    TRUE,
    ;

    infix fun and(other: Tri): Tri = if (this <= other) this else other

    infix fun or(other: Tri): Tri = if (this >= other) this else other

    companion object {
        fun of(flag: Boolean?): Tri = when (flag) {
            true -> TRUE
            false -> FALSE
            null -> UNKNOWN
        }
    }
}

@JvmInline
value class NodeId(val value: String) {
    init {
        require(value.isNotBlank()) { "a node needs an id" }
    }

    override fun toString() = value
}

sealed interface Node {
    val id: NodeId

    /** Plain words for this step, written so a chain of them reads as a sentence. */
    val text: String
}

/** A fact. [reason] says why a leaf is UNKNOWN (what could not be read); it is shown wherever the leaf keeps a path open. */
data class Leaf(
    override val id: NodeId,
    override val text: String,
    val value: Tri,
    val provenance: Provenance,
    val confidence: Confidence,
    val reason: String? = null,
) : Node {
    init {
        require(value != Tri.UNKNOWN || !reason.isNullOrBlank()) { "an unknown leaf ($id) must say why it is unknown" }
    }
}

data class AndNode(override val id: NodeId, override val text: String, val children: List<NodeId>) : Node {
    init {
        require(children.isNotEmpty()) { "an AND with no children would be true for no reason ($id)" }
    }
}

data class OrNode(override val id: NodeId, override val text: String, val children: List<NodeId>) : Node {
    init {
        require(children.isNotEmpty()) { "an OR with no children would be false for no reason ($id)" }
    }
}

enum class Severity { LOW, MEDIUM, HIGH, CRITICAL }

/** What the attacker gains: the assets of THREAT_MODEL.md section 2, in words a person uses. */
enum class Asset(val label: String) {
    ACCOUNT("your main account"),
    MONEY("your money and payment apps"),
    CODES("your one-time codes"),
    PRIVATE_DATA("your messages, photos and contacts"),
    LOCATION("where you are"),
    DEVICE("control of this phone"),
    TRAFFIC("what you send over the network"),
}

/**
 * Something the attacker could achieve. [severity] is per profile and is kept apart from confidence on purpose
 * (THREAT_MODEL.md section 6). [cannotSee] is what this app cannot tell on this path, shown with it every time.
 */
data class Goal(
    val node: NodeId,
    val title: String,
    val asset: Asset,
    val techniques: List<String>,
    val severity: Map<Profile, Severity>,
    val cannotSee: List<String>,
) {
    init {
        require(Profile.entries.all { it in severity }) { "goal $node needs a severity for every profile" }
    }

    fun severityFor(profile: Profile): Severity = severity.getValue(profile)
}

/**
 * A change the person can make. Applying it makes the leaves in [flips] FALSE. [cost] orders equal-sized fixes by how much
 * they disturb the person's use of the phone (1 is gentle); [tradeOff] is what it costs, always stated.
 */
data class Lever(
    val id: String,
    val text: String,
    val tradeOff: String,
    val cost: Int,
    val flips: Set<NodeId>,
    val settings: SettingsLink? = null,
    /** True for a change in the person's own life (move an account to an authenticator) rather than a setting of the phone. */
    val outsidePhone: Boolean = false,
) {
    init {
        require(id.isNotBlank() && flips.isNotEmpty() && cost >= 1) { "a lever ($id) needs an id, a cost of at least 1 and something to change" }
    }
}

class GraphException(message: String) : IllegalArgumentException(message)

/**
 * An immutable, validated attack graph: unique ids, every reference resolves, acyclic, goals and levers point at real
 * nodes, and levers can only flip leaves. [order] lists children before parents, which is how evaluation walks it.
 */
class AttackGraph internal constructor(
    val nodes: Map<NodeId, Node>,
    val goals: List<Goal>,
    val levers: List<Lever>,
    val order: List<NodeId>,
) {
    val leaves: List<Leaf> get() = order.mapNotNull { nodes[it] as? Leaf }

    fun node(id: NodeId): Node = nodes.getValue(id)

    fun children(id: NodeId): List<NodeId> = when (val n = nodes.getValue(id)) {
        is AndNode -> n.children
        is OrNode -> n.children
        is Leaf -> emptyList()
    }

    fun lever(id: String): Lever? = levers.firstOrNull { it.id == id }
}

class GraphBuilder {
    private val nodes = LinkedHashMap<NodeId, Node>()
    private val goals = mutableListOf<Goal>()
    private val levers = mutableListOf<Lever>()

    private fun add(node: Node) {
        if (nodes.put(node.id, node) != null) throw GraphException("duplicate node id ${node.id}")
    }

    fun leaf(id: String, text: String, value: Tri, provenance: Provenance, confidence: Confidence, reason: String? = null): NodeId {
        add(Leaf(NodeId(id), text, value, provenance, confidence, reason))
        return NodeId(id)
    }

    // A value class cannot be a vararg, so the common arities are spelled out.
    fun and(id: String, text: String, children: List<NodeId>): NodeId {
        add(AndNode(NodeId(id), text, children))
        return NodeId(id)
    }

    fun and(id: String, text: String): NodeId = and(id, text, emptyList())

    fun and(id: String, text: String, a: NodeId): NodeId = and(id, text, listOf(a))

    fun and(id: String, text: String, a: NodeId, b: NodeId): NodeId = and(id, text, listOf(a, b))

    fun and(id: String, text: String, a: NodeId, b: NodeId, c: NodeId): NodeId = and(id, text, listOf(a, b, c))

    fun and(id: String, text: String, a: NodeId, b: NodeId, c: NodeId, d: NodeId): NodeId = and(id, text, listOf(a, b, c, d))

    fun or(id: String, text: String, children: List<NodeId>): NodeId {
        add(OrNode(NodeId(id), text, children))
        return NodeId(id)
    }

    fun or(id: String, text: String, a: NodeId): NodeId = or(id, text, listOf(a))

    fun or(id: String, text: String, a: NodeId, b: NodeId): NodeId = or(id, text, listOf(a, b))

    fun or(id: String, text: String, a: NodeId, b: NodeId, c: NodeId): NodeId = or(id, text, listOf(a, b, c))

    fun goal(goal: Goal) {
        goals += goal
    }

    fun lever(lever: Lever) {
        levers += lever
    }

    fun has(id: String) = NodeId(id) in nodes

    fun build(): AttackGraph {
        for (node in nodes.values) {
            val kids = when (node) {
                is AndNode -> node.children
                is OrNode -> node.children
                is Leaf -> emptyList()
            }
            for (k in kids) if (k !in nodes) throw GraphException("${node.id} refers to ${k.value}, which does not exist")
            if (kids.toSet().size != kids.size) throw GraphException("${node.id} lists a child twice")
        }
        for (g in goals) if (g.node !in nodes) throw GraphException("goal ${g.node} is not a node")
        if (goals.map { it.node }.toSet().size != goals.size) throw GraphException("two goals on one node")
        if (levers.map { it.id }.toSet().size != levers.size) throw GraphException("duplicate lever id")
        for (l in levers) {
            for (f in l.flips) {
                if (nodes[f] !is Leaf) throw GraphException("lever ${l.id} flips ${f.value}, which is not a leaf")
            }
        }
        return AttackGraph(nodes.toMap(), goals.toList(), levers.toList(), topologicalOrder())
    }

    /** Children first. Throws on a cycle: an attack graph that depends on itself says nothing. */
    private fun topologicalOrder(): List<NodeId> {
        val state = HashMap<NodeId, Int>() // 1 = being visited, 2 = done
        val out = ArrayList<NodeId>(nodes.size)
        for (start in nodes.keys) {
            if (state[start] == 2) continue
            // Iterative depth-first walk: a graph of thousands of nodes must not depend on the thread's stack size.
            val stack = ArrayDeque<Pair<NodeId, Int>>()
            stack.addLast(start to 0)
            state[start] = 1
            while (stack.isNotEmpty()) {
                val (id, next) = stack.removeLast()
                val kids = when (val n = nodes.getValue(id)) {
                    is AndNode -> n.children
                    is OrNode -> n.children
                    is Leaf -> emptyList()
                }
                if (next < kids.size) {
                    stack.addLast(id to next + 1)
                    val child = kids[next]
                    when (state[child]) {
                        1 -> throw GraphException("cycle through ${child.value}")
                        2 -> Unit
                        else -> {
                            state[child] = 1
                            stack.addLast(child to 0)
                        }
                    }
                } else {
                    state[id] = 2
                    out += id
                }
            }
        }
        return out
    }
}

/**
 * How a TRUE node is true: a leaf, or every child of an AND, or the chosen child of an OR. Sub-derivations are shared between
 * parents, so this is deliberately not a data class (structural equality would walk the shared parts again and again) and
 * [leaves] is worked out once per node.
 */
class Witness(val node: NodeId, val children: List<Witness>) {
    val leaves: Set<NodeId> by lazy {
        if (children.isEmpty()) setOf(node) else children.flatMapTo(LinkedHashSet()) { it.leaves }
    }
}

class Evaluation internal constructor(
    val graph: AttackGraph,
    val applied: Set<String>,
    val values: Map<NodeId, Tri>,
    private val confidences: Map<NodeId, Confidence>,
    private val unknownCauses: Map<NodeId, Set<NodeId>>,
    private val witnesses: Map<NodeId, Witness>,
) {
    fun value(id: NodeId): Tri = values.getValue(id)

    /** Only a TRUE node has a confidence: the weakest link of an AND, the best-supported way of an OR. */
    fun confidence(id: NodeId): Confidence? = confidences[id]

    /** For an UNKNOWN node: the UNKNOWN leaves that keep it open. Empty otherwise. */
    fun openedBy(id: NodeId): Set<NodeId> = unknownCauses[id].orEmpty()

    fun witness(id: NodeId): Witness? = witnesses[id]

    fun usesProvenance(id: NodeId, provenance: Provenance): Boolean =
        witness(id)?.leaves.orEmpty().any { (graph.node(it) as Leaf).provenance == provenance }

    fun goals(value: Tri): List<Goal> = graph.goals.filter { values.getValue(it.node) == value }

    val paths: List<Goal> get() = goals(Tri.TRUE)

    val notRuledOut: List<Goal> get() = goals(Tri.UNKNOWN)
}

object Evaluator {
    /**
     * Evaluates the graph with the levers in [applied] switched on. The same function answers "what is true now" and "what if I
     * did this", so the what-if screen can never disagree with the main one.
     */
    fun evaluate(graph: AttackGraph, applied: Set<String> = emptySet()): Evaluation {
        val flipped = HashSet<NodeId>()
        for (id in applied) {
            val lever = graph.lever(id) ?: throw GraphException("no lever $id")
            flipped += lever.flips
        }
        val values = HashMap<NodeId, Tri>(graph.nodes.size * 2)
        val confidences = HashMap<NodeId, Confidence>()
        val causes = HashMap<NodeId, Set<NodeId>>()
        val witnesses = HashMap<NodeId, Witness>()

        for (id in graph.order) {
            when (val node = graph.node(id)) {
                is Leaf -> {
                    val value = if (id in flipped) Tri.FALSE else node.value
                    values[id] = value
                    if (value == Tri.TRUE) {
                        confidences[id] = node.confidence
                        witnesses[id] = Witness(id, emptyList())
                    }
                    if (value == Tri.UNKNOWN) causes[id] = setOf(id)
                }

                is AndNode -> {
                    val kids = node.children
                    val value = kids.fold(Tri.TRUE) { acc, k -> acc and values.getValue(k) }
                    values[id] = value
                    when (value) {
                        Tri.TRUE -> {
                            confidences[id] = kids.minOf { confidences.getValue(it) }
                            witnesses[id] = Witness(id, kids.map { witnesses.getValue(it) })
                        }
                        Tri.UNKNOWN -> causes[id] = kids.filter { values.getValue(it) == Tri.UNKNOWN }.flatMapTo(LinkedHashSet()) { causes.getValue(it) }
                        Tri.FALSE -> Unit
                    }
                }

                is OrNode -> {
                    val kids = node.children
                    val value = kids.fold(Tri.FALSE) { acc, k -> acc or values.getValue(k) }
                    values[id] = value
                    when (value) {
                        Tri.TRUE -> {
                            // The best-supported way in is the one that counts; ties go to the first listed, so the answer is stable.
                            var best: NodeId? = null
                            for (k in kids) {
                                if (values.getValue(k) != Tri.TRUE) continue
                                if (best == null || confidences.getValue(k) > confidences.getValue(best)) best = k
                            }
                            val chosen = best!!
                            confidences[id] = confidences.getValue(chosen)
                            witnesses[id] = Witness(id, listOf(witnesses.getValue(chosen)))
                        }
                        Tri.UNKNOWN -> causes[id] = kids.filter { values.getValue(it) == Tri.UNKNOWN }.flatMapTo(LinkedHashSet()) { causes.getValue(it) }
                        Tri.FALSE -> Unit
                    }
                }
            }
        }
        return Evaluation(graph, applied, values, confidences, causes, witnesses)
    }
}
