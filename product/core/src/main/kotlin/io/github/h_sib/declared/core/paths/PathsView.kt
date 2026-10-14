package io.github.h_sib.declared.core.paths

import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.Declarations
import io.github.h_sib.declared.core.model.Profile
import io.github.h_sib.declared.core.model.Provenance
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.state.SavedState

/** One link of a path as it is shown: the fact, where it came from, and how sure the app is of it. */
data class Step(val text: String, val provenance: Provenance, val confidence: Confidence)

/** A path that exists given what the app observed and what the person said. */
data class PathView(
    val id: String,
    val title: String,
    val asset: Asset,
    val severity: Severity,
    /** The weakest link of the best-supported way in. */
    val confidence: Confidence,
    val steps: List<Step>,
    /** True when at least one link is the person's own word, so the path is only as good as that answer. */
    val restsOnWhatYouSaid: Boolean,
    val techniques: List<String>,
    val cannotSee: List<String>,
    /** Changes that stop this path by themselves, gentlest first. Empty when it takes more than one change. */
    val stoppedBy: List<Lever>,
)

/** A path this app could not rule out because something it needs could not be read or was not answered. */
data class OpenPathView(
    val id: String,
    val title: String,
    val severity: Severity,
    /** What kept it open, in words that can be shown. */
    val because: List<String>,
    /** True when the reason is a question the person has not answered (it can be settled on the "About you" screen). */
    val needsAnAnswer: Boolean,
)

data class PathsView(
    val profile: Profile,
    val paths: List<PathView>,
    val notRuledOut: List<OpenPathView>,
    /** The smallest set of changes that stops every path that can be stopped; null when there is no path to stop. */
    val plan: FixPlan?,
) {
    val pathCount: Int get() = paths.size
}

/**
 * Everything the paths screens need from one reading of the phone, worked out once: the graph, what is true in it now, what
 * is shown, and a way to ask "what if" against the same evaluation.
 */
class PathsAnalysis private constructor(val graph: AttackGraph, val baseline: Evaluation, val view: PathsView) {
    /** Previews changes. Ids that are not levers of this graph (a stale tick from an earlier reading) are ignored, never an error. */
    fun preview(applied: Set<String>): Fixes.Preview = Fixes.preview(graph, applied.filterTo(HashSet()) { graph.lever(it) != null }, baseline)

    companion object {
        fun of(report: Report, saved: SavedState?, declarations: Declarations): PathsAnalysis {
            val graph = RuleBook.build(report, saved, declarations)
            val baseline = Evaluator.evaluate(graph)
            return PathsAnalysis(graph, baseline, PathsPresenter.present(graph, declarations.profile, baseline))
        }
    }
}

/**
 * Turns an evaluation into what the screens show. Pure and deterministic: the same graph always gives the same order, so
 * a path never jumps about between two readings of an unchanged phone.
 */
object PathsPresenter {
    fun present(graph: AttackGraph, profile: Profile, baseline: Evaluation = Evaluator.evaluate(graph), budget: Int = Fixes.DEFAULT_BUDGET): PathsView {
        val paths = baseline.paths.map { goal ->
            val witness = baseline.witness(goal.node)!!
            val leaves = graph.order.filter { it in witness.leaves }.map { graph.node(it) as Leaf }
            PathView(
                id = goal.node.value,
                title = goal.title,
                asset = goal.asset,
                severity = goal.severityFor(profile),
                confidence = baseline.confidence(goal.node)!!,
                steps = leaves.map { Step(it.text, it.provenance, it.confidence) },
                restsOnWhatYouSaid = leaves.any { it.provenance == Provenance.TOLD },
                techniques = goal.techniques,
                cannotSee = goal.cannotSee,
                stoppedBy = singleStops(graph, goal, witness),
            )
        }.sortedWith(compareByDescending<PathView> { it.severity }.thenByDescending { it.confidence }.thenBy { it.title.lowercase() }.thenBy { it.id })

        val open = baseline.notRuledOut.map { goal ->
            val causes = baseline.openedBy(goal.node).map { graph.node(it) as Leaf }
            OpenPathView(
                id = goal.node.value,
                title = goal.title,
                severity = goal.severityFor(profile),
                because = causes.mapNotNull { it.reason }.distinct(),
                needsAnAnswer = causes.any { it.provenance == Provenance.TOLD },
            )
        }.sortedWith(compareByDescending<OpenPathView> { it.severity }.thenBy { it.title.lowercase() }.thenBy { it.id })

        val plan = if (paths.isEmpty()) null else Fixes.minimum(graph, baseline, budget)
        return PathsView(profile, paths, open, plan)
    }

    /** Levers that stop this goal on their own: only those that change a fact of the derivation shown can, so only those are tried. */
    private fun singleStops(graph: AttackGraph, goal: Goal, witness: Witness): List<Lever> =
        graph.levers
            .filter { l -> l.flips.any { it in witness.leaves } }
            .filter { l -> Evaluator.evaluate(graph, setOf(l.id)).value(goal.node) != Tri.TRUE }
            .sortedWith(compareBy<Lever>({ it.cost }, { it.id }))
}
