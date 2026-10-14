package io.github.h_sib.declared.core.state

import io.github.h_sib.declared.core.model.Value
import io.github.h_sib.declared.core.report.Report
import java.time.Instant

/**
 * One keyed thing in a snapshot: an app, a holder of a capability, a setting, a finding. Plain strings, sorted by field
 * name, so two snapshots can be compared field by field and written to disk without any further interpretation.
 */
data class Record(val fields: Map<String, String>) {
    companion object {
        fun of(vararg pairs: Pair<String, String>) = Record(pairs.toMap().toSortedMap())
    }
}

/** What the phone looked like at one moment. Everything the "what changed" screen needs, nothing it does not. */
data class Snapshot(val takenAt: Instant, val androidApi: Int, val records: Map<String, Record>) {
    fun withPrefix(prefix: String): Map<String, Record> = records.filterKeys { it.startsWith(prefix) }
}

object Keys {
    const val APP = "app:"
    const val HOLDER = "holder:"
    const val FACT = "fact:"
    const val FINDING = "finding:"
}

/**
 * Turns a [Report] into a [Snapshot]. What is stored is the state (is this switched on, who signed it), not the report's
 * sentences, so that a wording change in a later version of the app never looks like a change on the phone.
 */
object SnapshotBuilder {
    fun of(report: Report): Snapshot {
        val records = LinkedHashMap<String, Record>()
        for (a in report.apps) {
            records[Keys.APP + a.pkg] = Record.of(
                "label" to a.label,
                "tier" to a.tier.tier.name,
                "signers" to a.signerSha256.sorted().joinToString(","),
                "grants" to a.grants.sorted().joinToString(","),
                "kinds" to a.kinds.map { it.name }.sorted().joinToString(","),
                "launcher" to a.hasLauncherIcon.toString(),
            )
        }
        for (h in report.holders) {
            records[Keys.HOLDER + h.kind.name + ":" + h.key] = Record.of(
                "kind" to h.kind.name,
                "pkg" to h.pkg,
                "label" to h.label,
                "sub" to (h.subLabel ?: ""),
                "active" to h.active.toString(),
                "tier" to h.tier.tier.name,
                "powers" to h.powers.powers.map { it.key }.sorted().joinToString(","),
            )
        }
        for (f in report.facts) {
            records[Keys.FACT + f.kind.name] = Record.of(
                "availability" to f.availability::class.simpleName.orEmpty(),
                "value" to (f.observation?.value?.let(::valueText) ?: ""),
            )
        }
        for (f in report.findings) {
            records[Keys.FINDING + f.id] = Record.of("attention" to f.attention.name, "title" to f.title)
        }
        return Snapshot(report.takenAt, report.androidApi, records.toSortedMap())
    }

    fun valueText(v: Value): String = when (v) {
        is Value.Flag -> v.on.toString()
        is Value.Text -> v.text
        is Value.Count -> v.n.toString()
        is Value.Items -> v.items.sorted().joinToString(",")
    }
}
