package io.github.h_sib.declared.core.schedule

import io.github.h_sib.declared.core.model.Attention
import io.github.h_sib.declared.core.state.ChangeItem

/** The person's choice about automatic checks. Off unless they turned it on; the interval is theirs, within what Android can run. */
data class AutoConfig(val enabled: Boolean = false, val intervalMinutes: Int = AutoCheck.DEFAULT_MINUTES)

enum class IntervalUnit(val minutes: Int, val label: String) {
    MINUTES(1, "minutes"),
    HOURS(60, "hours"),
    DAYS(24 * 60, "days"),
}

/** What a typed interval turned out to be. */
sealed interface Interval {
    data class Valid(val minutes: Int) : Interval
    data object NotANumber : Interval
    data object TooShort : Interval
    data object TooLong : Interval
}

/** Whether to tell the person, how many things are new to them, and what was already told (so the same change is never announced twice). */
data class Decision(val notify: Boolean, val newCount: Int, val told: Set<String>)

/**
 * Automatic checks, as rules. Android runs a repeating job no more often than every 15 minutes, and may run it later than asked to save
 * battery; both are said in the interface. Only changes that need a look (Look first or Look) are announced, never a quiet note, and
 * the announcement never names an app: a notification can be read on a locked screen.
 */
object AutoCheck {
    const val MIN_MINUTES = 15
    const val MAX_MINUTES = 365 * 24 * 60
    const val DEFAULT_MINUTES = 24 * 60

    /** Choices to tap before typing anything. */
    val presets: List<Int> = listOf(15, 60, 6 * 60, 12 * 60, 24 * 60, 7 * 24 * 60)

    fun parse(amount: String, unit: IntervalUnit): Interval {
        val n = amount.trim().toLongOrNull() ?: return Interval.NotANumber
        if (n <= 0) return Interval.TooShort
        val minutes = n * unit.minutes
        return when {
            minutes < MIN_MINUTES -> Interval.TooShort
            minutes > MAX_MINUTES -> Interval.TooLong
            else -> Interval.Valid(minutes.toInt())
        }
    }

    /** The interval in the largest whole unit that holds it exactly: 120 is "every 2 hours", 90 is "every 90 minutes". */
    fun describe(minutes: Int): String {
        fun unit(n: Int, one: String, many: String) = if (n == 1) "every $one" else "every $n $many"
        return when {
            minutes % IntervalUnit.DAYS.minutes == 0 -> unit(minutes / IntervalUnit.DAYS.minutes, "day", "days")
            minutes % IntervalUnit.HOURS.minutes == 0 -> unit(minutes / IntervalUnit.HOURS.minutes, "hour", "hours")
            else -> unit(minutes, "minute", "minutes")
        }
    }

    /** A short label for a choice to tap: "15 minutes", "1 hour", "6 hours", "1 day". */
    fun label(minutes: Int): String {
        val (n, unit) = split(minutes)
        val word = unit.label.removeSuffix("s")
        return if (n == 1) "1 $word" else "$n ${unit.label}"
    }

    /** What to type back into the box for a stored interval: the largest unit that holds it exactly. */
    fun split(minutes: Int): Pair<Int, IntervalUnit> = when {
        minutes % IntervalUnit.DAYS.minutes == 0 -> minutes / IntervalUnit.DAYS.minutes to IntervalUnit.DAYS
        minutes % IntervalUnit.HOURS.minutes == 0 -> minutes / IntervalUnit.HOURS.minutes to IntervalUnit.HOURS
        else -> minutes to IntervalUnit.MINUTES
    }

    /** Keeps a stored value inside what can run, so a damaged or old setting can never ask Android for something it refuses. */
    fun clamp(minutes: Int): Int = minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)

    fun key(item: ChangeItem): String = item.kind.name + ":" + item.keys.joinToString("|") + ":" + item.title

    /**
     * [changes] is what differs from what the person last saw. Only items that need a look count. What is new to them is what they were not
     * already told; what they were told is remembered only while it is still a change, so a change that comes back later is announced again.
     * [token] turns a change into what is remembered about it: the caller keeps only a hash, so no app name is written to disk in the clear.
     */
    fun decide(changes: List<ChangeItem>, alreadyTold: Set<String>, token: (ChangeItem) -> String = ::key): Decision {
        val urgent = changes.filter { it.attention != Attention.NOTE }.map(token).toSet()
        val fresh = urgent - alreadyTold
        return Decision(notify = fresh.isNotEmpty(), newCount = fresh.size, told = urgent)
    }

    const val NOTIFICATION_TITLE = "Declared noticed a change"

    fun notificationText(count: Int): String = if (count == 1) "1 change is worth a look." else "$count changes are worth a look."

    fun intervalError(i: Interval): String? = when (i) {
        is Interval.Valid -> null
        Interval.NotANumber -> "Type a whole number."
        Interval.TooShort -> "Android runs this at most every 15 minutes."
        Interval.TooLong -> "The longest is 365 days."
    }
}
