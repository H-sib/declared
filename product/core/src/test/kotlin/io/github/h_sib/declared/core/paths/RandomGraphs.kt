package io.github.h_sib.declared.core.paths

import io.github.h_sib.declared.core.model.Profile
import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.Provenance
import java.util.Random

/** Random attack graphs for property tests. Deterministic for a seed, so a failure can be replayed. */
object RandomGraphs {
    val EVERY_PROFILE = Profile.entries.associateWith { Severity.HIGH }

    fun goalOn(id: NodeId, title: String = "goal $id") = Goal(id, title, Asset.ACCOUNT, listOf("T0"), EVERY_PROFILE, emptyList())

    /**
     * [leaves] facts, [internals] AND/OR nodes each over two to four earlier nodes (so sub-derivations are shared), the last
     * [goals] internal nodes as goals, and [levers] levers that each flip one to three leaves.
     */
    fun make(
        random: Random,
        leaves: Int,
        internals: Int,
        goals: Int,
        levers: Int,
        unknownShare: Double = 0.1,
        trueShare: Double = 0.6,
    ): AttackGraph {
        val b = GraphBuilder()
        val ids = ArrayList<NodeId>()
        repeat(leaves) { i ->
            val r = random.nextDouble()
            val value = when {
                r < unknownShare -> Tri.UNKNOWN
                r < unknownShare + (1 - unknownShare) * trueShare -> Tri.TRUE
                else -> Tri.FALSE
            }
            ids += b.leaf(
                "l$i", "leaf $i", value, Provenance.entries[random.nextInt(3)], Confidence.entries[random.nextInt(3)],
                reason = if (value == Tri.UNKNOWN) "could not read $i" else null,
            )
        }
        val internalIds = ArrayList<NodeId>()
        repeat(internals) { i ->
            val count = 2 + random.nextInt(3)
            val kids = LinkedHashSet<NodeId>()
            // Mostly recent nodes (deep chains), sometimes any earlier node (sharing across the graph).
            while (kids.size < minOf(count, ids.size)) {
                kids += if (ids.size > 8 && random.nextInt(3) > 0) ids[ids.size - 1 - random.nextInt(8)] else ids[random.nextInt(ids.size)]
            }
            val id = if (random.nextBoolean()) b.and("n$i", "and $i", kids.toList()) else b.or("n$i", "or $i", kids.toList())
            ids += id
            internalIds += id
        }
        for (g in internalIds.takeLast(goals)) b.goal(goalOn(g))
        repeat(levers) { i ->
            val flips = LinkedHashSet<NodeId>()
            repeat(1 + random.nextInt(3)) { flips += NodeId("l${random.nextInt(leaves)}") }
            b.lever(Lever("fix$i", "fix $i", "costs $i", 1 + random.nextInt(3), flips))
        }
        return b.build()
    }
}
