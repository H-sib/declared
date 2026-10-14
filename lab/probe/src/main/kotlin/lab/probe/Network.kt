package lab.probe

import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import java.security.KeyStore
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import android.net.http.X509TrustManagerExtensions
import java.security.cert.X509Certificate

/** N-series: network configuration and Wi-Fi visibility. */
object Network {

    private fun granted(ctx: Context, permission: String): Boolean =
        ctx.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun securityName(type: Int): String =
        WifiInfo::class.java.fields
            .firstOrNull { it.name.startsWith("SECURITY_TYPE_") && it.getInt(null) == type }?.name
            ?: "TYPE_$type"

    private fun classify(capabilities: String): String = when {
        capabilities.contains("SAE") -> "SAE"
        capabilities.contains("OWE") -> "OWE"
        capabilities.contains("EAP") -> "EAP"
        capabilities.contains("PSK") -> "PSK"
        capabilities.contains("WEP") -> "WEP"
        else -> "OPEN"
    }

    fun collect(ctx: Context): List<Reading> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)

        return listOf(
            reading("N1", "Active network capabilities", 1) {
                val net = cm.activeNetwork ?: return@reading Flagged(Code.OK, null, "no active network")
                val caps = cm.getNetworkCapabilities(net) ?: return@reading Flagged(Code.OK, null, "no capabilities")
                val transports = listOf(
                    "wifi" to NetworkCapabilities.TRANSPORT_WIFI,
                    "cellular" to NetworkCapabilities.TRANSPORT_CELLULAR,
                    "vpn" to NetworkCapabilities.TRANSPORT_VPN,
                    "ethernet" to NetworkCapabilities.TRANSPORT_ETHERNET,
                ).filter { caps.hasTransport(it.second) }.map { it.first }
                jo(
                    "transports" to transports,
                    "internet" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    "validated" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    "captivePortal" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL),
                    "notVpn" to caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                )
            },
            reading("N2", "DNS and Private DNS", 1) {
                val lp = cm.getLinkProperties(cm.activeNetwork) ?: return@reading Flagged(Code.OK, null, "no link properties")
                jo(
                    "dnsServers" to lp.dnsServers.map { rd(it.hostAddress) },
                    "privateDnsActive" to lp.isPrivateDnsActive,
                    "privateDnsServerName" to lp.privateDnsServerName,
                    "domains" to lp.domains,
                )
            },
            reading("N3", "HTTP proxy", 1) {
                val lp = cm.getLinkProperties(cm.activeNetwork) ?: return@reading Flagged(Code.OK, null, "no link properties")
                jo("httpProxy" to lp.httpProxy?.toString(), "globalProxy" to cm.defaultProxy?.toString())
            },
            reading("N4", "Routes and interface", 1) {
                val lp = cm.getLinkProperties(cm.activeNetwork) ?: return@reading Flagged(Code.OK, null, "no link properties")
                jo(
                    "interface" to lp.interfaceName,
                    "routes" to lp.routes.map { rd(it.toString()) },
                    "linkAddresses" to lp.linkAddresses.size,
                )
            },
            reading("N5", "User-installed CAs", 1) {
                val ks = KeyStore.getInstance("AndroidCAStore")
                ks.load(null)
                val aliases = ks.aliases().toList()
                val prefixCounts = aliases.groupingBy { it.substringBefore(':', "(none)") }.eachCount()
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)
                val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
                val ext = X509TrustManagerExtensions(tm)
                val issuers = tm.acceptedIssuers
                val userAcceptedSha = issuers.filter { ext.isUserAddedCertificate(it) }.map { sha256Hex(it.encoded) }
                val userAliasSha = aliases.filter { it.startsWith("user:") }
                    .mapNotNull { (ks.getCertificate(it) as? X509Certificate)?.let { c -> sha256Hex(c.encoded) } }
                jo(
                    "aliasCount" to aliases.size,
                    "aliasPrefixCounts" to prefixCounts,
                    "acceptedIssuerCount" to issuers.size,
                    "userAddedAmongAccepted" to userAcceptedSha,
                    "userAliasSha256" to userAliasSha,
                )
            },
            reading("N6", "Wi-Fi link and redaction state (includes N7)", 1) {
                val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
                val lm = ctx.getSystemService(LocationManager::class.java)
                @Suppress("DEPRECATION")
                val info = wm.connectionInfo
                val ssid = info.ssid
                val type = if (Build.VERSION.SDK_INT >= 31) info.currentSecurityType else null
                jo(
                    "wifiEnabled" to wm.isWifiEnabled,
                    "locationEnabled" to lm.isLocationEnabled,
                    "permFine" to granted(ctx, "android.permission.ACCESS_FINE_LOCATION"),
                    "permNearby" to granted(ctx, "android.permission.NEARBY_WIFI_DEVICES"),
                    "ssid" to rd(ssid),
                    "ssidRedacted" to (ssid == "<unknown ssid>"),
                    "bssidRedacted" to (info.bssid == null || info.bssid == "02:00:00:00:00:00"),
                    "rssi" to info.rssi,
                    "frequency" to info.frequency,
                    "linkSpeed" to info.linkSpeed,
                    "securityType" to type,
                    "securityTypeName" to type?.let { securityName(it) },
                    "wifiStandard" to if (Build.VERSION.SDK_INT >= 30) info.wifiStandard else null,
                )
            },
            reading("N8", "Wi-Fi scan summary (counts only)", 4) {
                val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
                val results: List<ScanResult> = wm.scanResults
                val bySsid = results.groupBy { it.SSID }
                jo(
                    "total" to results.size,
                    "distinctSsids" to bySsid.size,
                    "ssidsWithMultipleBssids" to bySsid.count { (_, v) -> v.map { it.BSSID }.distinct().size > 1 },
                    "ssidsWithMixedSecurity" to bySsid.count { (s, v) ->
                        s.isNotEmpty() && v.map { classify(it.capabilities) }.distinct().size > 1
                    },
                    "securityMix" to results.groupingBy { classify(it.capabilities) }.eachCount(),
                )
            },
        )
    }
}
