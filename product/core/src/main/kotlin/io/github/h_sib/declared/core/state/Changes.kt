package io.github.h_sib.declared.core.state

import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.model.CapabilityKind
import io.github.h_sib.declared.core.model.FactGroup
import io.github.h_sib.declared.core.model.FactKind
import io.github.h_sib.declared.core.report.Describe
import io.github.h_sib.declared.core.tier.Tier

enum class ChangeKind {
    NEW_FINDING, ESCALATED_FINDING, FINDING_GONE, SIGNER_CHANGED, APP_ADDED, APP_REMOVED, GRANTS_ADDED, TIER_CHANGED, SWITCHED_OFF, SETTING_CHANGED, NETWORK_CHANGED,
}

data class ChangeItem(val attention: Attention, val kind: ChangeKind, val title: String, val keys: List<String>)

/** What changed between two snapshots, in the words a person reads. [rawChanges] is how many records differ underneath. */
data class ChangeSummary(val items: List<ChangeItem>, val rawChanges: Int) {
    val needsAttention: Boolean get() = items.any { it.attention != Attention.NOTE }
}

/**
 * Turns the record-level [Diff] into plain items. Built-in apps are counted but not itemized (an operating-system update
 * rewrites dozens of them and none of that is for the user to review); everything an outside party can add, change or
 * re-sign is itemized. New findings carry the attention of the finding itself, so the "what changed" screen never rates
 * something differently from the screen that explains it.
 */
object Changes {
    private val BUILT_IN = setOf(Tier.PLATFORM.name, Tier.SYSTEM.name)
    private val UNUSUAL_ORIGINS = setOf(Tier.SIDELOAD.name, Tier.ADB.name, Tier.UNKNOWN.name)

    fun summarize(old: Snapshot, new: Snapshot): ChangeSummary {
        val changes = Diff.of(old.records, new.records)
        val items = ArrayList<ChangeItem>()
        for (c in changes) {
            when {
                c.key.startsWith(Keys.FINDING) -> findingItem(c)?.let(items::add)
                c.key.startsWith(Keys.APP) -> items += appItems(c)
                c.key.startsWith(Keys.HOLDER) -> holderItem(c)?.let(items::add)
                c.key.startsWith(Keys.FACT) -> factItem(c)?.let(items::add)
            }
        }
        val sorted = items.sortedWith(compareByDescending<ChangeItem> { it.attention }.thenBy { it.kind.ordinal }.thenBy { it.title.lowercase() }.thenBy { it.keys.firstOrNull().orEmpty() })
        return ChangeSummary(sorted, changes.size)
    }

    private fun attentionOf(name: String?): Attention = Attention.entries.firstOrNull { it.name == name } ?: Attention.NOTE

    private fun tierText(name: String?): String = Tier.entries.firstOrNull { it.name == name }?.let(Describe::tier) ?: "unknown origin"

    private fun findingItem(c: Change): ChangeItem? = when (c) {
        is Change.Added -> ChangeItem(attentionOf(c.record.fields["attention"]), ChangeKind.NEW_FINDING, "New: ${c.record.fields["title"].orEmpty()}", listOf(c.key))
        is Change.Removed -> ChangeItem(Attention.NOTE, ChangeKind.FINDING_GONE, "No longer the case: ${c.record.fields["title"].orEmpty()}", listOf(c.key))
        is Change.Modified -> {
            val before = attentionOf(c.before.fields["attention"])
            val after = attentionOf(c.after.fields["attention"])
            if (after > before) {
                ChangeItem(after, ChangeKind.ESCALATED_FINDING, "${c.after.fields["title"].orEmpty()} (now: ${Describe.attention(after).lowercase()})", listOf(c.key))
            } else {
                null // a finding that got quieter is not news
            }
        }
    }

    private fun appItems(c: Change): List<ChangeItem> {
        val key = c.key
        return when (c) {
            is Change.Added -> {
                val f = c.record.fields
                if (f["tier"] in BUILT_IN) {
                    emptyList()
                } else {
                    val attention = if (f["tier"] in UNUSUAL_ORIGINS) Attention.LOOK else Attention.NOTE
                    listOf(ChangeItem(attention, ChangeKind.APP_ADDED, "New app: ${f["label"].orEmpty()} (${tierText(f["tier"])})", listOf(key)))
                }
            }
            is Change.Removed -> {
                if (c.record.fields["tier"] in BUILT_IN) emptyList()
                else listOf(ChangeItem(Attention.NOTE, ChangeKind.APP_REMOVED, "${c.record.fields["label"].orEmpty()} was removed", listOf(key)))
            }
            is Change.Modified -> {
                val b = c.before.fields
                val a = c.after.fields
                if (a["tier"] in BUILT_IN && b["tier"] in BUILT_IN) return emptyList()
                val label = a["label"].orEmpty()
                buildList {
                    if (b["signers"] != a["signers"] && !b["signers"].isNullOrEmpty() && !a["signers"].isNullOrEmpty()) {
                        add(ChangeItem(Attention.LOOK_FIRST, ChangeKind.SIGNER_CHANGED, "$label is now signed by a different certificate", listOf(key)))
                    }
                    val oldGrants = b["grants"].orEmpty().split(',').filter { it.isNotEmpty() }.toSet()
                    val added = a["grants"].orEmpty().split(',').filter { it.isNotEmpty() && it !in oldGrants }
                    if (added.isNotEmpty()) {
                        val attention = if (a["tier"] in UNUSUAL_ORIGINS) Attention.LOOK else Attention.NOTE
                        add(ChangeItem(attention, ChangeKind.GRANTS_ADDED, "$label can now use: ${added.joinToString(", ") { Describe.grant(it) }}", listOf(key)))
                    }
                    if (b["tier"] != a["tier"]) {
                        add(ChangeItem(Attention.NOTE, ChangeKind.TIER_CHANGED, "$label: ${tierText(a["tier"]).lowercase()} (was ${tierText(b["tier"]).lowercase()})", listOf(key)))
                    }
                }
            }
        }
    }

    private fun holderItem(c: Change): ChangeItem? {
        if (c !is Change.Modified) return null
        val b = c.before.fields
        val a = c.after.fields
        if (a["tier"] in BUILT_IN) return null
        if (b["active"] == "true" && a["active"] == "false") {
            val kind = CapabilityKind.entries.firstOrNull { it.name == a["kind"] }?.shortName ?: "an ability"
            return ChangeItem(Attention.NOTE, ChangeKind.SWITCHED_OFF, "${a["label"].orEmpty()} no longer has $kind switched on", listOf(c.key))
        }
        return null
    }

    private fun factItem(c: Change): ChangeItem? {
        if (c !is Change.Modified) return null
        val kind = FactKind.entries.firstOrNull { it.name == c.key.removePrefix(Keys.FACT) } ?: return null
        val before = factValue(kind, c.before.fields)
        val after = factValue(kind, c.after.fields)
        if (before == after) return null
        val network = kind.group == FactGroup.NETWORK
        return ChangeItem(Attention.NOTE, if (network) ChangeKind.NETWORK_CHANGED else ChangeKind.SETTING_CHANGED, "${kind.label}: $before, now $after", listOf(c.key))
    }

    /** A fact value the way a person says it. Unreadable is its own value, never an empty one. */
    fun factValue(kind: FactKind, fields: Map<String, String>): String {
        val availability = fields["availability"].orEmpty()
        val value = fields["value"].orEmpty()
        if (availability == "Unavailable") return "not readable"
        if (value.isEmpty()) return if (availability == "Degraded") "unclear" else "not set"
        return when (kind) {
            FactKind.SCREEN_LOCK -> if (value == "true") "set" else "none"
            FactKind.BOOTLOADER -> if (value == "true") "locked" else "unlocked"
            FactKind.USB_DEBUGGING, FactKind.WIRELESS_DEBUGGING, FactKind.DEVELOPER_OPTIONS, FactKind.ADVANCED_PROTECTION -> if (value == "true") "on" else "off"
            else -> value.lowercase().replace('_', ' ')
        }
    }
}
