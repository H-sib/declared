package io.github.h_sib.declared.core.blindspots

import io.github.h_sib.declared.core.live.Watched
import io.github.h_sib.declared.core.model.Availability
import io.github.h_sib.declared.core.model.Signals
import io.github.h_sib.declared.core.report.Describe
import io.github.h_sib.declared.core.report.Report

/** ALWAYS is true on every phone; THIS_PHONE is worked out from this phone's own readings. */
enum class BlindSpotKind { ALWAYS, THIS_PHONE }

data class BlindSpot(
    val id: String,
    val kind: BlindSpotKind,
    val title: String,
    val text: String,
    /** What was measured about this limit, with where, when it exists. Never a promise beyond what was tested. */
    val measured: String? = null,
)

/**
 * The map of what this app cannot see (DESIGN.md section 7). It is a first-class screen, not a footnote: a security tool
 * that hides its limits teaches people to trust it too much. Every THIS_PHONE entry is computed from the report, so an
 * unreadable signal on this phone always appears here with its reason; nothing is dropped to look better.
 */
object BlindSpots {
    /** Android 11 introduced package visibility filtering (lab Q2): below it every app is visible. */
    const val FIRST_FILTERED_API = 30

    fun of(report: Report): List<BlindSpot> = thisPhone(report) + always()

    private fun always(): List<BlindSpot> = listOf(
        BlindSpot(
            "other-apps-behavior", BlindSpotKind.ALWAYS, "What other apps do",
            "This app reads which powers an app holds and where it came from. It cannot see what an app does with them, what it reads, or what it sends.",
        ),
        BlindSpot(
            "network-traffic", BlindSpotKind.ALWAYS, "What leaves the phone",
            "Other apps' network traffic is not visible to this app, so it cannot tell whether anything is being sent anywhere. Stage 1 of this app itself has no internet permission at all, which anyone can check in its manifest.",
        ),
        BlindSpot(
            "deep-compromise", BlindSpotKind.ALWAYS, "Attacks on the system itself",
            "If the phone's own system software, its firmware or the kernel is attacked, the readings this app gets come from that same system. It also cannot see attacks that need no action from you.",
        ),
        BlindSpot(
            "altered-phone", BlindSpotKind.ALWAYS, "A phone that has been altered",
            "A phone with altered system software can report whatever it likes, to this app as to any other. The hardware check helps only when the phone's keys are held in hardware (see the hardware check below).",
        ),
        BlindSpot(
            "outside-the-phone", BlindSpotKind.ALWAYS, "What is not on the phone",
            "Cellular interception, SIM swaps, stolen passwords and accounts that are already compromised are outside what any app on the phone can see.",
        ),
        BlindSpot(
            "screen-capture", BlindSpotKind.ALWAYS, "Who can see this app's screens",
            "By default this app allows screenshots. If you turn on Block screenshots under More, it asks Android to keep its screens out of screenshots, screen recordings and the recent-apps preview. That does not stop an app that holds the accessibility power from reading the words on the screen, which is the very thing this app tells you to look for.",
        ),
        BlindSpot(
            "play-protect", BlindSpotKind.ALWAYS, "Google Play Protect",
            "Android does not let apps read whether Play Protect is on or what it found, so this app says nothing about it.",
            measured = "The related settings are absent for a normal app (lab D8).",
        ),
    )

    private fun thisPhone(r: Report): List<BlindSpot> = buildList {
        add(hiddenApps(r))
        add(
            BlindSpot(
                "background-changes", BlindSpotKind.THIS_PHONE, "Changes while this app is closed",
                "This app hears changes only while it is on screen, and re-reads the phone every ${Watched.POLL_SECONDS} seconds then. While it is closed, Android delivers almost nothing to it, so changes are found the next time you open it and compared with what you last acknowledged. They stay on the list until you press \"I have seen these changes\".",
                measured = Watched.MEASURED + " In the background almost nothing was delivered, and nothing once the app was no longer running.",
            ),
        )
        val unreadable = unreadableSignals(r)
        if (unreadable.isNotEmpty()) {
            add(
                BlindSpot(
                    "unreadable-signals", BlindSpotKind.THIS_PHONE, "What this phone does not let this app read",
                    unreadable.joinToString(" "),
                ),
            )
        }
        add(
            BlindSpot(
                "hidden-grants", BlindSpotKind.THIS_PHONE, "Built-in apps' extra access",
                "Special access and text-message grants of built-in apps are not listed, because they would bury the rest. Apps that share a user id with other apps, mostly built-in ones, show only the permissions their own manifest asks for.",
            ),
        )
        add(attestation(r))
    }

    private fun hiddenApps(r: Report): BlindSpot {
        val seen = r.apps.size
        return if (r.androidApi < FIRST_FILTERED_API) {
            BlindSpot(
                "hidden-apps", BlindSpotKind.THIS_PHONE, "Apps this app cannot see",
                "On this Android version (${r.androidApi}) every installed app is visible to this app: $seen found.",
                measured = "Android 10 shows a normal app all installed packages (lab Q1).",
            )
        } else {
            BlindSpot(
                "hidden-apps", BlindSpotKind.THIS_PHONE, "Apps this app cannot see",
                "On this Android version (${r.androidApi}), Android hides apps from this app unless they have an icon in the launcher or hold one of four special powers (accessibility, notification access, keyboard, device admin). " +
                    "$seen apps are visible here; an app with no icon and none of those powers is invisible, and an app that hides its icon is exactly what some spying apps do. Asking for more visibility was tested and did not help; seeing every app would need a permission that Google Play restricts, which this app does not ask for.",
                measured = "Every holder of the four powers was seen on Android 12 to 15, and all but one Google system package on 16 and 17; apps without an icon and without those powers stayed invisible on every version tested (lab V1, V1b).",
            )
        }
    }

    private fun unreadableSignals(r: Report): List<String> {
        val problems = r.signals.filter { it.availability != Availability.Available && it.id != Signals.ATTESTATION_REFERENCE }
        return problems.groupBy { it.availability }.map { (availability, group) ->
            val names = group.map { Describe.signalName(it.id) }.distinct().joinToString(", ")
            when (availability) {
                Availability.Available -> ""
                is Availability.Degraded -> "$names: partly readable. ${availability.reason}"
                is Availability.Unavailable -> "$names: not readable. ${availability.reason}"
            }
        }
    }

    private fun attestation(r: Report): BlindSpot {
        val chain = r.signals.firstOrNull { it.id == Signals.ATTESTATION_CHAIN }?.availability
        val text = when (chain) {
            Availability.Available ->
                "The hardware check judged this phone's key attestation on the phone itself, against reference data bundled with the app. A separate trusted server would be a stronger judge, and leaked attestation keys can fool the check until Google revokes them."
            is Availability.Degraded, is Availability.Unavailable, null ->
                "The hardware key check could not establish how this phone started (see the reason above), so nothing here vouches for the phone's system software."
        }
        return BlindSpot("device-side-attestation", BlindSpotKind.THIS_PHONE, "The hardware check", text)
    }
}
