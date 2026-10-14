package io.github.h_sib.declared.core.sensor

import io.github.h_sib.declared.core.sensor.DefaultNetworkGuess.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultNetworkGuessTest {
    private fun net(vararg t: Transport, internet: Boolean = true, validated: Boolean = true) = Candidate(t.toSet(), internet, validated)

    @Test
    fun wifiBeatsCellularWhenBothAreConfirmed() {
        assertEquals(1, DefaultNetworkGuess.pick(listOf(net(Transport.CELLULAR), net(Transport.WIFI))))
        assertEquals(0, DefaultNetworkGuess.pick(listOf(net(Transport.WIFI), net(Transport.CELLULAR))))
    }

    @Test
    fun aConfirmedNetworkBeatsAnUnconfirmedOneWhateverItsKind() {
        assertEquals(1, DefaultNetworkGuess.pick(listOf(net(Transport.WIFI, validated = false), net(Transport.CELLULAR))))
    }

    @Test
    fun aVpnThatCarriesTrafficIsTheDefaultAndWiredBeatsWifi() {
        assertEquals(2, DefaultNetworkGuess.pick(listOf(net(Transport.WIFI), net(Transport.ETHERNET), net(Transport.VPN))))
        assertEquals(1, DefaultNetworkGuess.pick(listOf(net(Transport.WIFI), net(Transport.ETHERNET))))
    }

    @Test
    fun aNetworkWithoutInternetIsNeverPickedAndNothingToPickGivesNull() {
        assertEquals(1, DefaultNetworkGuess.pick(listOf(net(Transport.WIFI, internet = false), net(Transport.CELLULAR))))
        assertNull(DefaultNetworkGuess.pick(listOf(net(Transport.WIFI, internet = false))))
        assertNull(DefaultNetworkGuess.pick(emptyList()))
    }

    @Test
    fun equalNetworksKeepTheirOrderSoTheAnswerDoesNotChangeBetweenReadings() {
        assertEquals(0, DefaultNetworkGuess.pick(listOf(net(Transport.WIFI), net(Transport.WIFI))))
        assertEquals(0, DefaultNetworkGuess.pick(listOf(net(Transport.OTHER), net(Transport.OTHER))))
    }
}
