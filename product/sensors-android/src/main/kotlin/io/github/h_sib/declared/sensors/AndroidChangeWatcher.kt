package io.github.h_sib.declared.sensors

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import io.github.h_sib.declared.core.live.ChangeWatcher
import io.github.h_sib.declared.core.live.LiveSignal
import io.github.h_sib.declared.core.live.LiveSource
import io.github.h_sib.declared.core.live.PackageAction
import io.github.h_sib.declared.core.live.SettingScope
import io.github.h_sib.declared.core.live.Watched

/**
 * Listens to the platform while the app is on screen: content observers on the settings the report reads, a receiver for
 * app installs, removals, updates and switches, and a default-network callback. This is the lab probe's watcher (P11,
 * FINDINGS.md Q7) turned into a product component, with the words kept in `core` where they are tested.
 *
 * It holds no wake lock, runs no service and is stopped whenever the app leaves the screen, because the measurement says
 * nothing is delivered in the background anyway.
 */
class AndroidChangeWatcher(private val context: Context) : ChangeWatcher {
    private val handler = Handler(Looper.getMainLooper())
    private val observers = mutableListOf<ContentObserver>()
    private var receiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    override var running: Boolean = false
        private set

    override fun start(sink: (LiveSignal) -> Unit) {
        if (running) return
        running = true
        val resolver = context.contentResolver

        for (setting in Watched.settings) {
            val uri = when (setting.scope) {
                SettingScope.GLOBAL -> Settings.Global.getUriFor(setting.name)
                SettingScope.SECURE -> Settings.Secure.getUriFor(setting.name)
            }
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = sink(LiveSignal(LiveSource.SETTING, setting.what))
            }
            resolver.registerContentObserver(uri, false, observer)
            observers += observer
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        val packages = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val action = when (intent.action) {
                    Intent.ACTION_PACKAGE_ADDED -> PackageAction.ADDED
                    Intent.ACTION_PACKAGE_REMOVED -> PackageAction.REMOVED
                    Intent.ACTION_PACKAGE_REPLACED -> PackageAction.REPLACED
                    Intent.ACTION_PACKAGE_CHANGED -> PackageAction.CHANGED
                    else -> return
                }
                val pkg = intent.data?.schemeSpecificPart ?: return
                val words = Watched.packageEvent(action, intent.getBooleanExtra(Intent.EXTRA_REPLACING, false), pkg) ?: return
                sink(LiveSignal(LiveSource.APP, words))
            }
        }
        registerSystemBroadcast(packages, filter)
        receiver = packages

        // Only what the report reads is announced: the network, its transports, internet access, validation, VPN and captive
        // portal. Signal strength and link speed change all the time and are not changes this app cares about.
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        if (connectivity != null) {
            try {
                val active = connectivity.activeNetwork
                val callback = NetworkWatch(active, active?.let { connectivity.getNetworkCapabilities(it) }?.let { describe(it) } ?: NO_NETWORK, sink)
                connectivity.registerDefaultNetworkCallback(callback, handler)
                networkCallback = callback
            } catch (e: SecurityException) {
                // Without the network state permission this part of the watcher is simply absent; the rest still works.
                networkCallback = null
            }
        }
    }

    /** Remembers the default network and what was read from it, so a callback that changes nothing the report reads is silent. */
    private class NetworkWatch(
        private var network: Network?,
        private var key: String,
        private val sink: (LiveSignal) -> Unit,
    ) : ConnectivityManager.NetworkCallback() {
        private fun update(newNetwork: Network?, newKey: String) {
            if (newNetwork == network && newKey == key) return
            network = newNetwork
            key = newKey
            sink(LiveSignal(LiveSource.NETWORK, Watched.NETWORK_CHANGED))
        }

        // The capabilities callback that follows carries the detail, so this one says nothing.
        override fun onAvailable(network: Network) = Unit

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update(network, describe(capabilities))

        override fun onLost(network: Network) {
            // Android also reports the loss of a network that stopped being the default a moment ago; that is not a change.
            if (network == this.network) update(null, NO_NETWORK)
        }
    }

    override fun stop() {
        if (!running) return
        running = false
        val resolver = context.contentResolver
        observers.forEach { resolver.unregisterContentObserver(it) }
        observers.clear()
        receiver?.let { context.unregisterReceiver(it) }
        receiver = null
        networkCallback?.let { context.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) }
        networkCallback = null
    }

    /** These are protected system broadcasts, so who may send them is Android's decision, not ours: the flag only silences the lint rule below Android 13. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSystemBroadcast(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }

    private companion object {
        const val NO_NETWORK = "none"

        fun describe(capabilities: NetworkCapabilities): String {
            val transports = listOf(
                NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_ETHERNET,
                NetworkCapabilities.TRANSPORT_BLUETOOTH, NetworkCapabilities.TRANSPORT_VPN,
            ).filter { capabilities.hasTransport(it) }
            val flags = listOf(
                NetworkCapabilities.NET_CAPABILITY_INTERNET, NetworkCapabilities.NET_CAPABILITY_VALIDATED,
                NetworkCapabilities.NET_CAPABILITY_NOT_VPN, NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL,
            ).filter { capabilities.hasCapability(it) }
            return "$transports|$flags"
        }
    }
}
