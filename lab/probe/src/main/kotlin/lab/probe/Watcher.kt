package lab.probe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.LinkProperties
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * P11: foreground-only live watcher. Registers content observers on settings entries, a package-change receiver,
 * and a default-network callback, and reports each event through [sink]. Runs only while the activity wants it,
 * which mirrors the product design (no permanent foreground service).
 */
class Watcher(private val ctx: Context, private val sink: (String) -> Unit) {

    private val handler = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<ContentObserver>()
    private var receiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    var running = false
        private set

    private val watchedSettings = listOf(
        "secure:enabled_accessibility_services",
        "secure:accessibility_enabled",
        "secure:enabled_notification_listeners",
        "secure:default_input_method",
        "secure:enabled_input_methods",
        "secure:location_mode",
        "global:adb_enabled",
        "global:adb_wifi_enabled",
        "global:development_settings_enabled",
        "global:http_proxy",
        "global:private_dns_mode",
        "global:private_dns_specifier",
    )

    fun start() {
        if (running) return
        running = true
        val cr = ctx.contentResolver

        for (entry in watchedSettings) {
            val (scope, name) = entry.split(":", limit = 2)
            val uri: Uri = if (scope == "global") Settings.Global.getUriFor(name) else Settings.Secure.getUriFor(name)
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean, uri: Uri?) {
                    sink("setting-changed $entry")
                }
            }
            cr.registerContentObserver(uri, false, observer)
            observers.add(observer)
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                sink("package ${intent.action?.substringAfterLast('.')} ${rd(intent.data?.schemeSpecificPart)}")
            }
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, filter, Context.RECEIVER_NOT_EXPORTED)
        else ctx.registerReceiver(r, filter)
        receiver = r

        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = sink("network available")
            override fun onLost(network: Network) = sink("network lost")
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                sink("network capabilities validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)} " +
                    "vpn=${caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)}")
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                sink("network link privateDns=${lp.isPrivateDnsActive} proxy=${lp.httpProxy != null} dns=${lp.dnsServers.size}")
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
        sink("watcher started")
    }

    fun stop() {
        if (!running) return
        running = false
        val cr = ctx.contentResolver
        observers.forEach { cr.unregisterContentObserver(it) }
        observers.clear()
        receiver?.let { ctx.unregisterReceiver(it) }
        receiver = null
        networkCallback?.let { ctx.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        networkCallback = null
        sink("watcher stopped")
    }
}
