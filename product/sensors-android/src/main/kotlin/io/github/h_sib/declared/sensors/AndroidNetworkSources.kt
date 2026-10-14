package io.github.h_sib.declared.sensors

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.Uri
import android.net.http.X509TrustManagerExtensions
import android.net.wifi.WifiManager
import android.os.Build
import android.net.Network
import io.github.h_sib.declared.core.sensor.ActiveNetwork
import io.github.h_sib.declared.core.sensor.DefaultNetworkGuess
import io.github.h_sib.declared.core.sensor.NetworkSource
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.Transport
import io.github.h_sib.declared.core.sensor.UserCaReading
import io.github.h_sib.declared.core.sensor.UserCaSource
import io.github.h_sib.declared.core.sensor.WifiReading
import io.github.h_sib.declared.core.sensor.WifiSecurity
import io.github.h_sib.declared.core.sensor.WifiSource
import java.security.KeyStore
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

private fun transportsOf(caps: NetworkCapabilities?): Set<Transport> = buildSet {
    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add(Transport.WIFI)
    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add(Transport.CELLULAR)
    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add(Transport.VPN)
    if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) add(Transport.ETHERNET)
    if (isEmpty() && caps != null) add(Transport.OTHER)
}

/** The connection in use and whether it had to be inferred: Android 17 gives `activeNetwork` as null to an app that cannot use the network (measured on the API 37 image). */
private class Picked(val network: Network?, val inferred: Boolean)

@Suppress("DEPRECATION")
private fun pickDefault(cm: ConnectivityManager): Picked {
    cm.activeNetwork?.let { return Picked(it, inferred = false) }
    val all = cm.allNetworks.toList()
    val index = DefaultNetworkGuess.pick(
        all.map { n ->
            val caps = cm.getNetworkCapabilities(n)
            DefaultNetworkGuess.Candidate(
                transportsOf(caps),
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            )
        },
    )
    return Picked(index?.let { all[it] }, inferred = index != null)
}

/** Transport, validation, Private DNS and proxy of the connection in use (lab N1 to N4). Needs ACCESS_NETWORK_STATE, a normal permission. */
class AndroidNetworkSource(private val context: Context) : NetworkSource {

    override fun read(): ReadResult<ActiveNetwork> = guarded {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val picked = pickDefault(cm)
        val network = picked.network ?: return@guarded ActiveNetwork(false, emptySet(), false, false, false, null, null, null)
        val caps = cm.getNetworkCapabilities(network)
        val link = cm.getLinkProperties(network)
        ActiveNetwork(
            present = true,
            transports = transportsOf(caps),
            internet = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            captivePortal = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true,
            privateDnsActive = link?.isPrivateDnsActive,
            privateDnsServerName = link?.privateDnsServerName,
            httpProxy = (link?.httpProxy ?: cm.defaultProxy)?.let(::proxyText),
            inferred = picked.inferred,
        )
    }

    private fun proxyText(p: ProxyInfo): String =
        if (p.pacFileUrl != Uri.EMPTY) "script ${p.pacFileUrl}" else "${p.host}:${p.port}"
}

/**
 * The security type of the Wi-Fi network in use. From Android 12 this needs no location permission (lab Q6, N6);
 * on Android 11 and older Android tells apps only through scan results, which need the location permission, so the
 * reading says "not readable" with that reason instead of guessing.
 */
class AndroidWifiSource(private val context: Context) : WifiSource {

    override fun read(): ReadResult<WifiReading> = guarded {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val picked = pickDefault(cm)
        val onWifi = picked.network?.let { cm.getNetworkCapabilities(it) }?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val inferredNote = "Android hides the connection in use from an app that has no internet permission, so Wi-Fi was worked out from the networks this app can still see."
        when {
            !onWifi -> WifiReading(connected = false, security = null)
            Build.VERSION.SDK_INT < 31 -> WifiReading(
                connected = true,
                security = null,
                note = "On Android 11 and older, Android tells apps the security type of a Wi-Fi network only with the location permission, which this app does not ask for.",
            )
            else -> {
                val manager = context.applicationContext.getSystemService(WifiManager::class.java)
                @Suppress("DEPRECATION")
                val info = manager.connectionInfo
                WifiReading(connected = true, security = securityOf(info.currentSecurityType), note = inferredNote.takeIf { picked.inferred })
            }
        }
    }

    /** `WifiInfo.SECURITY_TYPE_*`. Literals, because the constants are inlined and newer than the minimum version. */
    private fun securityOf(type: Int): WifiSecurity = when (type) {
        0 -> WifiSecurity.OPEN
        1 -> WifiSecurity.WEP
        2 -> WifiSecurity.WPA_PSK
        3, 8, 9, 10, 11 -> WifiSecurity.ENTERPRISE
        4 -> WifiSecurity.WPA3_PSK
        5 -> WifiSecurity.OWE
        6, 7 -> WifiSecurity.WAPI
        12 -> WifiSecurity.OTHER
        else -> WifiSecurity.UNKNOWN
    }
}

/**
 * Certificate authorities added by a user or an administrator: the `user:` entries of `AndroidCAStore`, cross-checked
 * with the trust manager's own `isUserAddedCertificate`. Not yet verified with a real added certificate (lab N5), so the
 * pipeline reports it at LOW confidence.
 */
class AndroidUserCaSource : UserCaSource {

    override fun read(): ReadResult<UserCaReading> = guarded {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val fromAliases = store.aliases().toList().filter { it.startsWith("user:") }
            .mapNotNull { (store.getCertificate(it) as? X509Certificate)?.encoded?.let(Hashing::sha256Hex) }
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
        val trust = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        val extensions = X509TrustManagerExtensions(trust)
        val fromTrust = trust.acceptedIssuers.filter { extensions.isUserAddedCertificate(it) }.map { Hashing.sha256Hex(it.encoded) }
        UserCaReading((fromAliases + fromTrust).distinct().sorted())
    }
}
