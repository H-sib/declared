package io.github.h_sib.declared.core.home

import io.github.h_sib.declared.core.ledger.History
import io.github.h_sib.declared.core.ledger.HistoryState
import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.model.Availability
import io.github.h_sib.declared.core.paths.PathsView
import io.github.h_sib.declared.core.report.Report
import io.github.h_sib.declared.core.tier.Tier

/**
 * What the home screen says (UX decisions in the chat of 2026-10-11): one statement drawn from the real findings, how much was
 * actually checked, and one line for each of the three doors. Plain strings, so the wording tests reach them and the screen only lays them out.
 * Nothing here claims the phone is fine: the statement says what was found, and by how many checks.
 */
data class Coverage(val ran: Int, val couldNotRun: Int, val unsupported: Int) {
    val total: Int get() = ran + couldNotRun + unsupported

    fun words(): String = buildString {
        append("$ran of $total checks ran.")
        if (couldNotRun > 0) append(" $couldNotRun could not run.")
        if (unsupported > 0) append(" $unsupported ${if (unsupported == 1) "is" else "are"} not supported on this Android version.")
    }
}

data class HomeSummary(
    val statement: String,
    /** The worst finding in its own words, under the statement. Null when there is nothing to look at. */
    val detail: String?,
    val coverage: Coverage,
    val checkLine: String,
    val pathsLine: String,
    val historyLine: String,
    val changedLine: String?,
)

enum class AreaKind { POWERS, APPS, SETTINGS, NETWORK, INTEGRITY, HISTORY }

/** What the checks of one area found. CLEAR means only "nothing found by these checks", never that the area is fine. */
enum class AreaStatus { RISK, LOOK, NOTE, CLEAR, UNKNOWN }

data class Area(val kind: AreaKind, val title: String, val status: AreaStatus, val line: String)

object Home {
    private val POWERS = setOf("accessibility", "notification-listener", "device-admin", "input-method", "overlay", "usage-access", "install-unknown-apps", "sms-access", "review")
    private val SETTINGS = setOf("screen-lock", "patch-level", "patch-mismatch", "usb-debugging", "developer-options", "wireless-debugging")
    private val NETWORK = setOf("http-proxy", "wifi-security", "user-cas")

    private fun kindOf(findingId: String): AreaKind {
        val head = findingId.substringBefore(":")
        val tail = findingId.substringAfter(":", "")
        return when {
            head == "condition" && tail in SETTINGS -> AreaKind.SETTINGS
            head == "condition" && tail in NETWORK -> AreaKind.NETWORK
            head == "condition" -> AreaKind.INTEGRITY
            head in POWERS -> AreaKind.POWERS
            else -> AreaKind.POWERS
        }
    }

    /** One row per area: the worst thing found there, in plain words. Findings are matched to areas by their ids, so a new finding kind lands in Powers until it is placed. */
    fun areas(report: Report, history: History): List<Area> {
        fun worst(kind: AreaKind): Pair<AreaStatus, Int> {
            val here = report.findings.filter { kindOf(it.id) == kind }
            val urgent = here.filter { it.attention != Attention.NOTE }
            return when {
                here.any { it.attention == Attention.LOOK_FIRST } -> AreaStatus.RISK to urgent.size
                urgent.isNotEmpty() -> AreaStatus.LOOK to urgent.size
                here.isNotEmpty() -> AreaStatus.NOTE to here.size
                else -> AreaStatus.CLEAR to 0
            }
        }

        fun unreadable(prefix: String) = report.signals.any { it.id.value.startsWith(prefix) && it.availability is Availability.Unavailable }

        fun line(status: AreaStatus, n: Int, unknownLine: String = "Not all read.") = when (status) {
            AreaStatus.RISK, AreaStatus.LOOK -> "$n to look at."
            AreaStatus.NOTE -> "$n for your information."
            AreaStatus.CLEAR -> "Nothing found."
            AreaStatus.UNKNOWN -> unknownLine
        }

        fun area(kind: AreaKind, title: String, signalPrefix: String): Area {
            var (status, n) = worst(kind)
            if (status == AreaStatus.CLEAR && unreadable(signalPrefix)) status = AreaStatus.UNKNOWN
            return Area(kind, title, status, line(status, n))
        }

        val unusual = listOf(Tier.SIDELOAD, Tier.ADB, Tier.UNKNOWN).sumOf { report.tierCounts[it] ?: 0 }
        val apps = if (unusual > 0) Area(AreaKind.APPS, "Apps", AreaStatus.NOTE, "$unusual outside stores.") else Area(AreaKind.APPS, "Apps", AreaStatus.CLEAR, "Normal sources.")
        val historyArea = when (history.state) {
            is HistoryState.Damaged -> Area(AreaKind.HISTORY, "History", AreaStatus.RISK, "Could not be read.")
            is HistoryState.Unavailable -> Area(AreaKind.HISTORY, "History", AreaStatus.UNKNOWN, "Could not be kept.")
            HistoryState.Loading -> Area(AreaKind.HISTORY, "History", AreaStatus.UNKNOWN, "Reading.")
            HistoryState.Ready -> when {
                history.lastTripwire != null -> Area(AreaKind.HISTORY, "History", AreaStatus.LOOK, "Tripwire event.")
                history.entries == 0 -> Area(AreaKind.HISTORY, "History", AreaStatus.UNKNOWN, "Empty so far.")
                else -> Area(AreaKind.HISTORY, "History", AreaStatus.CLEAR, if (history.entries == 1) "1 entry." else "${history.entries} entries.")
            }
        }
        return listOf(
            area(AreaKind.POWERS, "Powers", "special-access."),
            apps,
            area(AreaKind.SETTINGS, "Settings", "device."),
            area(AreaKind.NETWORK, "Network", "network."),
            area(AreaKind.INTEGRITY, "Integrity", "integrity."),
            historyArea,
        )
    }

    /** A check "ran" when it was read, even partly; "unsupported" when this Android version cannot give it at all; anything else that was not read "could not run". */
    fun coverage(report: Report): Coverage {
        var ran = 0
        var couldNot = 0
        var unsupported = 0
        for (s in report.signals) {
            when (s.availability) {
                Availability.Available, is Availability.Degraded -> ran += 1
                is Availability.Unavailable -> if (s.source.minApi > report.androidApi) unsupported += 1 else couldNot += 1
            }
        }
        return Coverage(ran, couldNot, unsupported)
    }

    fun of(report: Report, paths: PathsView, history: History, changeCount: Int?): HomeSummary {
        val coverage = coverage(report)
        val first = report.findings.filter { it.attention == Attention.LOOK_FIRST }
        val look = report.findings.filter { it.attention == Attention.LOOK }
        val statement = when {
            first.isNotEmpty() -> if (first.size == 1) "One thing to look at first." else "${first.size} things to look at first."
            look.isNotEmpty() -> if (look.size == 1) "One thing is worth a look." else "${look.size} things are worth a look."
            else -> "Nothing was found by ${coverage.ran} checks."
        }
        val detail = (first.firstOrNull() ?: look.firstOrNull())?.title?.let(::sentence)
        val checkLine = when {
            first.isEmpty() && look.isEmpty() -> "Nothing to look at."
            first.isEmpty() -> "${look.size} worth a look."
            look.isEmpty() -> "${first.size} to look at first."
            else -> "${first.size} to look at first, ${look.size} worth a look."
        }
        val ways = when (paths.pathCount) {
            0 -> null
            1 -> "One way in."
            else -> "${paths.pathCount} ways in."
        }
        val open = paths.notRuledOut.size.takeIf { it > 0 }?.let { "$it not ruled out." }
        val pathsLine = listOfNotNull(ways, open).joinToString(" ").ifEmpty { "No way in found from the facts that could be read." }
        val historyLine = when (history.state) {
            HistoryState.Loading -> "Reading the history."
            is HistoryState.Damaged -> "The history could not be read."
            is HistoryState.Unavailable -> "The history could not be kept."
            HistoryState.Ready -> when {
                history.entries == 0 -> "Empty so far."
                history.lastTripwire != null -> "${entries(history.entries)}. A tripwire event is recorded."
                else -> "${entries(history.entries)}. No tripwire event."
            }
        }
        val changed = changeCount?.takeIf { it > 0 }?.let { if (it == 1) "One change since you last looked." else "$it changes since you last looked." }
        return HomeSummary(statement, detail, coverage, checkLine, pathsLine, historyLine, changed)
    }

    private fun entries(n: Int) = if (n == 1) "1 entry" else "$n entries"

    private fun sentence(title: String): String = if (title.endsWith(".")) title else "$title."
}

/**
 * The small pattern drawn from the newest history entry's hash: five rows of five cells, mirrored left to right, so the same hash always
 * gives the same picture and a different history gives a different one. It carries no secret; it is a way to notice at a glance that the
 * record changed.
 */
object HashGlyph {
    const val SIZE = 5

    /** Row-major, `SIZE * SIZE` cells. A blank hash gives a blank pattern. */
    fun cells(hash: String): BooleanArray {
        val out = BooleanArray(SIZE * SIZE)
        if (hash.isEmpty()) return out
        for (r in 0 until SIZE) {
            for (c in 0..2) {
                val digit = Character.digit(hash[(r * 3 + c) % hash.length], 16).coerceAtLeast(0)
                val on = (digit shr ((r + c) % 3)) and 1 == 1
                out[r * SIZE + c] = on
                out[r * SIZE + (SIZE - 1 - c)] = on
            }
        }
        return out
    }
}
