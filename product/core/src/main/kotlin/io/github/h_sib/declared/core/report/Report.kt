package io.github.h_sib.declared.core.report

import io.github.h_sib.declared.core.model.CapabilityKind
import io.github.h_sib.declared.core.model.DecodedPowers
import io.github.h_sib.declared.core.model.DeviceFact
import io.github.h_sib.declared.core.model.Finding
import io.github.h_sib.declared.core.model.SignalStatus
import io.github.h_sib.declared.core.paths.PhoneState
import io.github.h_sib.declared.core.tier.Tier
import io.github.h_sib.declared.core.tier.TierVerdict
import java.time.Instant

/**
 * One app that holds, or could hold, a powerful capability. Component kinds (accessibility, notification access,
 * device admin, keyboard) list every installed component, switched on or not. Grant kinds (overlay, usage access,
 * install other apps, text messages) list only apps that hold the grant.
 */
data class Holder(
    val kind: CapabilityKind,
    /** Unique within its kind: `package/class` for components, the package name for grants. */
    val key: String,
    val pkg: String,
    val label: String,
    /** The component's own name, which tells apart several services of one app. Null for grants and unnamed components. */
    val subLabel: String?,
    /** Switched on (components) or granted (grants). */
    val active: Boolean,
    val tier: TierVerdict,
    val powers: DecodedPowers,
    /** Null for holders that are not active: nothing is switched on, so there is nothing to claim. */
    val findingId: String?,
)

/** A row of the full inventory: every package this app can see, whatever it holds. Minimal on purpose (DESIGN.md section 4). */
data class AppRow(
    val pkg: String,
    val label: String,
    val tier: TierVerdict,
    val signerSha256: List<String>,
    val hasLauncherIcon: Boolean,
    val kinds: Set<CapabilityKind>,
    /** Short names of the sensitive runtime permissions Android reports as granted (for example `RECORD_AUDIO`). */
    val grants: List<String>,
)

/** What one pass over the phone produced. A value, so it can be compared, diffed and shown without further reads. */
data class Report(
    val takenAt: Instant,
    val androidApi: Int,
    val signals: List<SignalStatus>,
    val holders: List<Holder>,
    val findings: List<Finding>,
    val apps: List<AppRow> = emptyList(),
    /** Plain rows for the phone's settings and the network, whether or not each is a problem. */
    val facts: List<DeviceFact> = emptyList(),
    /** The same conditions as typed values, for the attack-path rules (STAGE2_DESIGN.md section 6). */
    val phone: PhoneState = PhoneState.UNREAD,
) {
    private val findingsById: Map<String, Finding> = findings.associateBy { it.id }
    private val holdersByFinding: Map<String, Holder> = holders.filter { it.findingId != null }.associateBy { it.findingId!! }

    /** Constant-time lookups: the screens call these once per row, so a scan here would make a long list quadratic. */
    fun finding(id: String): Finding? = findingsById[id]

    fun holderOf(findingId: String): Holder? = holdersByFinding[findingId]

    val tierCounts: Map<Tier, Int> by lazy { apps.groupingBy { it.tier.tier }.eachCount() }

    fun holdersOf(kind: CapabilityKind): List<Holder> = holders.filter { it.kind == kind }
}
