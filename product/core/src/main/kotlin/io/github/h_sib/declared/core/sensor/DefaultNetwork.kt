package io.github.h_sib.declared.core.sensor

/**
 * Which of the networks an app can still see is most likely the phone's default one. Needed because Android 17 hides the active
 * network (`getActiveNetwork()` is null and the connection reads "blocked") from an app that cannot use the network, which is
 * this app's case on purpose: it asks for no internet permission. The networks and their properties stay readable.
 */
object DefaultNetworkGuess {
    data class Candidate(val transports: Set<Transport>, val internet: Boolean, val validated: Boolean)

    private fun rank(c: Candidate): Int = when {
        Transport.VPN in c.transports -> 0
        Transport.ETHERNET in c.transports -> 1
        Transport.WIFI in c.transports -> 2
        Transport.CELLULAR in c.transports -> 3
        else -> 4
    }

    /** The index of the likeliest default network, or null when none of them could carry traffic. Android confirmed beats unconfirmed, then VPN, wired, Wi-Fi, cellular. */
    fun pick(candidates: List<Candidate>): Int? =
        candidates.withIndex().filter { it.value.internet }
            .minWithOrNull(compareBy<IndexedValue<Candidate>>({ if (it.value.validated) 0 else 1 }, { rank(it.value) }, { it.index }))?.index
}
