package io.github.h_sib.declared.core.state

sealed interface Change {
    val key: String

    data class Added(override val key: String, val record: Record) : Change

    data class Removed(override val key: String, val record: Record) : Change

    data class Modified(override val key: String, val before: Record, val after: Record) : Change {
        val changedFields: Set<String>
            get() = (before.fields.keys + after.fields.keys).filter { before.fields[it] != after.fields[it] }.toSortedSet()
    }
}

/**
 * The difference between two sets of keyed records. The defining property, tested over thousands of random pairs:
 * `apply(old, of(old, new)) == new`, and applying the inverse to `new` gives `old` back. A diff that cannot be applied
 * is not a diff, which is what makes "what changed" something the screen can be held to.
 */
object Diff {
    fun of(old: Map<String, Record>, new: Map<String, Record>): List<Change> {
        val out = ArrayList<Change>()
        for (key in (old.keys + new.keys).toSortedSet()) {
            val before = old[key]
            val after = new[key]
            when {
                before == null && after != null -> out += Change.Added(key, after)
                before != null && after == null -> out += Change.Removed(key, before)
                before != null && after != null && before != after -> out += Change.Modified(key, before, after)
            }
        }
        return out
    }

    /** Strict: a change whose precondition does not hold (adding what exists, removing or modifying what differs) throws. */
    fun apply(old: Map<String, Record>, changes: List<Change>): Map<String, Record> {
        val m = LinkedHashMap(old)
        for (c in changes) {
            when (c) {
                is Change.Added -> {
                    require(c.key !in m) { "cannot add ${c.key}: it already exists" }
                    m[c.key] = c.record
                }
                is Change.Removed -> {
                    require(m[c.key] == c.record) { "cannot remove ${c.key}: it is not what the change expects" }
                    m.remove(c.key)
                }
                is Change.Modified -> {
                    require(m[c.key] == c.before) { "cannot modify ${c.key}: it is not what the change expects" }
                    m[c.key] = c.after
                }
            }
        }
        return m
    }

    fun invert(changes: List<Change>): List<Change> = changes.map {
        when (it) {
            is Change.Added -> Change.Removed(it.key, it.record)
            is Change.Removed -> Change.Added(it.key, it.record)
            is Change.Modified -> Change.Modified(it.key, it.after, it.before)
        }
    }
}
