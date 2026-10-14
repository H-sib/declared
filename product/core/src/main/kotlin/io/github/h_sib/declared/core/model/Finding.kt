package io.github.h_sib.declared.core.model

/**
 * An ordinal prompt for the user's attention, not a score. It is always explained in words next to the finding.
 * NOTE = listed for completeness, LOOK = worth knowing about, LOOK_FIRST = strong abilities and an origin this app cannot vouch for.
 */
enum class Attention { NOTE, LOOK, LOOK_FIRST }

/** Something the user can do, always paired with what it costs (DESIGN.md principle 3). */
data class Action(val text: String, val tradeOff: String)

/**
 * The glass box: what was seen ([claim] and [context] with their sources), what it means, how sure we are,
 * what this reading cannot tell, and what the user can do.
 *
 * [confidence] belongs to the claim only. [context] (for example where the app came from) is shown with its own
 * confidence and neither raises nor lowers the claim's. Otherwise "this service is enabled", which the system states
 * outright, would be reported as MEDIUM just because the installer name is unverified.
 */
data class Finding(
    val id: String,
    val title: String,
    /** Exactly what this finding is about, unambiguous when one app has several services. */
    val subject: String,
    /** The section heading it belongs under on screen (a capability kind, "This phone's settings", ...). */
    val section: String,
    val attention: Attention,
    val claim: List<Observation>,
    val context: List<Observation>,
    val meaning: String,
    val whatItCannotTell: List<String>,
    val actions: List<Action>,
    /** Where the user can change the thing this finding is about. */
    val settings: SettingsLink,
) {
    init {
        require(claim.isNotEmpty()) { "No finding without evidence: $id has an empty claim" }
        require(whatItCannotTell.isNotEmpty()) { "A finding must say what it cannot tell: $id" }
    }

    val confidence: Confidence = Confidence.weakest(claim.map { it.confidence }) ?: Confidence.LOW
}
