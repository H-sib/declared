package io.github.h_sib.declared.core.state

import io.github.h_sib.declared.core.model.Action
import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.model.Confidence
import io.github.h_sib.declared.core.model.Declarations
import io.github.h_sib.declared.core.model.Finding
import io.github.h_sib.declared.core.model.Observation
import io.github.h_sib.declared.core.model.Provenance
import io.github.h_sib.declared.core.model.SettingsLink
import io.github.h_sib.declared.core.model.Signals
import io.github.h_sib.declared.core.model.SourceInfo
import io.github.h_sib.declared.core.model.Subject
import io.github.h_sib.declared.core.model.Value
import io.github.h_sib.declared.core.report.Holder
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.tier.Tier
import java.time.Instant

/** What the user says about a holder when asked at the baseline review. */
enum class Answer { RECOGNIZED, NOT_RECOGNIZED, UNSURE }

/** An answer is stored with the signing certificate of the app at that moment, so a later change of signer is itself a finding. */
data class HolderAnswer(val answer: Answer, val signerSha256: List<String>, val answeredAt: Instant)

/**
 * Everything that survives between openings of the app. [reviewed] is the snapshot the user last acknowledged, and "what
 * changed" is always measured against it: a change stays on screen until the user marks it seen, so opening the app once
 * never makes a change quietly disappear (DESIGN.md section 8, B3).
 */
data class SavedState(
    val version: Int,
    val baselineAt: Instant,
    /** Keyed `KIND:key`, see [Review.key]. */
    val answers: Map<String, HolderAnswer>,
    val reviewed: Snapshot,
    /** What the person said about their own life (version 2). A version 1 file has none, which reads as every question unanswered. */
    val declarations: Declarations = Declarations(),
) {
    companion object {
        const val CURRENT_VERSION = 2

        /** The oldest version this app still reads; its missing parts take their empty values. */
        const val OLDEST_READABLE_VERSION = 1
    }
}

/**
 * The baseline review (THREAT_MODEL.md, trust assumption 6): the first snapshot is not known to be clean, so the user is
 * asked about every holder of a power that came from outside the phone's own software. "I do not recognize this" becomes
 * a finding immediately, and so does a changed signing certificate.
 */
object Review {
    private val BUILT_IN = setOf(Tier.PLATFORM, Tier.SYSTEM)

    fun key(h: Holder): String = h.kind.name + ":" + h.key

    /** The things one app holds that are worth asking about. An app with a screen reader and notification access is one app to the person, so it is one question. */
    class AppGroup<T>(val pkg: String, val label: String, val items: List<T>)

    /** The items grouped by app, in the order each app first appears. Nothing is dropped and nothing is in two groups. */
    fun <T> groupByApp(items: List<T>, pkg: (T) -> String, label: (T) -> String): List<AppGroup<T>> =
        items.groupBy(pkg).map { (p, list) -> AppGroup(p, label(list.first()), list) }

    /** Only things that are actually switched on or granted, and only from outside the phone's own software, are worth asking about. */
    fun needsReview(h: Holder): Boolean = h.active && h.tier.tier !in BUILT_IN

    /** Holders still to be asked about: all of them at first, and later only the ones that appeared since. */
    fun pending(report: Report, saved: SavedState?): List<Holder> =
        report.holders.filter { needsReview(it) && (saved == null || key(it) !in saved.answers) }
            .sortedWith(compareBy<Holder>({ it.kind.ordinal }, { it.label.lowercase() }, { it.key }))

    data class SignerChange(val holder: Holder, val before: List<String>, val after: List<String>)

    fun signerChanges(report: Report, saved: SavedState?): List<SignerChange> {
        if (saved == null) return emptyList()
        val signers = report.apps.associate { it.pkg to it.signerSha256.sorted() }
        return report.holders.mapNotNull { h ->
            val answer = saved.answers[key(h)] ?: return@mapNotNull null
            val now = signers[h.pkg] ?: return@mapNotNull null
            val then = answer.signerSha256.sorted()
            if (then.isNotEmpty() && now.isNotEmpty() && then != now) SignerChange(h, then, now) else null
        }
    }

    /**
     * Records the user's answers. Anyone not answered is stored as UNSURE rather than guessed; older answers are kept; the
     * reviewed snapshot becomes the current one (the baseline is "what the user has now looked at").
     */
    fun establish(report: Report, answers: Map<String, Answer>, previous: SavedState?, at: Instant): SavedState {
        val signers = report.apps.associate { it.pkg to it.signerSha256.sorted() }
        val merged = LinkedHashMap(previous?.answers.orEmpty())
        for (h in pending(report, previous)) {
            merged[key(h)] = HolderAnswer(answers[key(h)] ?: Answer.UNSURE, signers[h.pkg].orEmpty(), at)
        }
        return SavedState(SavedState.CURRENT_VERSION, previous?.baselineAt ?: at, merged.toSortedMap(), SnapshotBuilder.of(report), previous?.declarations ?: Declarations())
    }

    /** The user has seen the changes: measure from now on. Answers are untouched. */
    fun acknowledge(saved: SavedState, report: Report): SavedState = saved.copy(reviewed = SnapshotBuilder.of(report))

    /** The report plus the findings that come from the user's own answers and from signer changes. */
    fun apply(report: Report, saved: SavedState?, at: Instant): Report {
        if (saved == null) return report
        val extra = findings(report, saved, at)
        if (extra.isEmpty()) return report
        return report.copy(findings = (report.findings + extra).sortedWith(compareByDescending<Finding> { it.attention }.thenBy { it.title.lowercase() }.thenBy { it.id }))
    }

    private val ANSWER_SOURCE = SourceInfo(api = "your answer when you reviewed this phone", minApi = 1)
    private val SIGNER_SOURCE = SourceInfo(api = "PackageInfo.signingInfo (SHA-256 of each signing certificate), compared with what was recorded when you reviewed it", minApi = 28)

    fun findings(report: Report, saved: SavedState, at: Instant): List<Finding> {
        val out = ArrayList<Finding>()
        for (h in report.holders) {
            if (!needsReview(h)) continue
            if (saved.answers[key(h)]?.answer == Answer.NOT_RECOGNIZED) out += notRecognized(h, saved.answers.getValue(key(h)), at)
        }
        for (c in signerChanges(report, saved)) out += signerChanged(c, at)
        return out
    }

    private fun notRecognized(h: Holder, answer: HolderAnswer, at: Instant): Finding {
        val name = listOfNotNull(h.label, h.subLabel?.takeIf { it != h.label }).joinToString(": ")
        val claim = Observation(
            Signals.BASELINE_ANSWER, Subject.App(h.pkg), Value.Text(Answer.NOT_RECOGNIZED.name), Provenance.TOLD, Confidence.HIGH, ANSWER_SOURCE,
            listOf("This is what you told this app on ${answer.answeredAt}. It is your statement, not a measurement."), at,
        )
        return Finding(
            id = "review:not-recognized:${key(h)}",
            title = "You do not recognize $name, but it holds powerful abilities on your phone",
            subject = "${h.kind.shortName}: ${h.pkg}",
            section = "Your own review",
            attention = Attention.LOOK_FIRST,
            claim = listOf(claim),
            context = emptyList(),
            meaning = "When you reviewed this phone you told this app that you do not recognize $name, and it still has ${h.kind.shortName} switched on or granted. " +
                "That is the whole reason this is shown: your own answer and what the phone says do not fit together. It does not mean that $name is harmful.",
            whatItCannotTell = listOf(
                "Whether $name is harmful: apps you do not recall installing are often part of something you did install.",
                "Where $name came from or who put it there: the app's origin is shown in its own finding.",
                "Whether your answer still holds: you can look again.",
            ),
            actions = listOf(
                Action(
                    "Open the setting for $name and switch it off if you do not need it.",
                    "Anything that relies on it stops until you switch it back on.",
                ),
                Action(
                    "If you are sure you did not install it on purpose, removing the app is reasonable.",
                    "Removing an app deletes its data on this phone, so copy out anything you need first.",
                ),
            ),
            settings = SettingsLink(h.kind.settings, h.pkg.takeIf { h.kind.settings.needsPackage }),
        )
    }

    private fun signerChanged(c: SignerChange, at: Instant): Finding {
        val h = c.holder
        val name = listOfNotNull(h.label, h.subLabel?.takeIf { it != h.label }).joinToString(": ")
        val claim = Observation(
            Signals.BASELINE_SIGNER, Subject.App(h.pkg), Value.Items(c.after), Provenance.OBSERVED, Confidence.HIGH, SIGNER_SOURCE,
            listOf("This compares two readings of the signing certificate. It does not say why it changed."), at,
        )
        return Finding(
            id = "review:signer-changed:${key(h)}",
            title = "$name now carries a different signature than when you reviewed it, so it may not be the same app",
            subject = "${h.pkg}: was ${c.before.joinToString(", ") { it.take(12) }}, now ${c.after.joinToString(", ") { it.take(12) }}",
            section = "Your own review",
            attention = Attention.LOOK_FIRST,
            claim = listOf(claim),
            context = emptyList(),
            meaning = "Android only lets an app be updated by the same signing certificate, so a different certificate means that the app was removed and a different build was installed under the same name. " +
                "That can be a new publisher, a re-signed copy or a replacement. It does not mean that the new build is harmful.",
            whatItCannotTell = listOf(
                "Whether the change was deliberate or who made it.",
                "Whether the new build behaves differently from the old one.",
                "Which certificate is the right one: this app knows only that the two differ.",
            ),
            actions = listOf(
                Action(
                    "If you did not reinstall $name yourself, think about where the new copy came from.",
                    "Nothing changes on the phone; this is a way of deciding how much to trust it.",
                ),
                Action(
                    "Open the setting for $name and switch it off if you do not need it.",
                    "Anything that relies on it stops until you switch it back on.",
                ),
            ),
            settings = SettingsLink(h.kind.settings, h.pkg.takeIf { h.kind.settings.needsPackage }),
        )
    }
}
