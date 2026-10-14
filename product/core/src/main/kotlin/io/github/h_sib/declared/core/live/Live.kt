package io.github.h_sib.declared.core.live

import java.time.Duration
import java.time.Instant

/**
 * The live view (DESIGN.md section 8): what this app hears while it is on screen. Measured on one Android 14 emulator
 * (FINDINGS.md, Q7): live only in front, mostly missed in the background, nothing once the process is gone. Nothing in this
 * package claims more than that.
 */
enum class LiveSource(val label: String) {
    SETTING("Setting"),
    APP("App"),
    NETWORK("Network"),
}

/** One thing the platform announced, already in plain words. [what] never says what the change meant, only that it happened. */
data class LiveSignal(val source: LiveSource, val what: String)

data class LiveEvent(val at: Instant, val source: LiveSource, val what: String)

/**
 * The newest events first, bounded, with the same announcement collapsed when the platform repeats it (one settings
 * change often arrives twice; a network change arrives as a burst).
 */
data class LiveLog(val watching: Boolean = false, val events: List<LiveEvent> = emptyList()) {
    fun record(event: LiveEvent): LiveLog {
        val newest = events.firstOrNull()
        if (newest != null && newest.source == event.source && newest.what == event.what &&
            Duration.between(newest.at, event.at).abs() < REPEAT_WINDOW
        ) {
            return this
        }
        return copy(events = (listOf(event) + events).take(MAX_EVENTS))
    }

    companion object {
        const val MAX_EVENTS = 100
        val REPEAT_WINDOW: Duration = Duration.ofSeconds(1)
    }
}

enum class SettingScope { SECURE, GLOBAL }

data class WatchedSetting(val scope: SettingScope, val name: String, val what: String)

enum class PackageAction { ADDED, REMOVED, REPLACED, CHANGED }

/** What is watched, and the words for it. The Android adapter registers exactly these; a test checks the words. */
object Watched {
    val settings: List<WatchedSetting> = listOf(
        WatchedSetting(SettingScope.SECURE, "enabled_accessibility_services", "The list of accessibility services that are switched on changed"),
        WatchedSetting(SettingScope.SECURE, "accessibility_enabled", "Accessibility was switched on or off as a whole"),
        WatchedSetting(SettingScope.SECURE, "enabled_notification_listeners", "The list of apps allowed to read notifications changed"),
        WatchedSetting(SettingScope.SECURE, "default_input_method", "The default keyboard changed"),
        WatchedSetting(SettingScope.SECURE, "enabled_input_methods", "The list of switched-on keyboards changed"),
        WatchedSetting(SettingScope.GLOBAL, "adb_enabled", "USB debugging was switched on or off"),
        WatchedSetting(SettingScope.GLOBAL, "adb_wifi_enabled", "Wireless debugging was switched on or off"),
        WatchedSetting(SettingScope.GLOBAL, "development_settings_enabled", "Developer options were switched on or off"),
        WatchedSetting(SettingScope.GLOBAL, "http_proxy", "The web proxy setting changed"),
        WatchedSetting(SettingScope.GLOBAL, "global_http_proxy_host", "The web proxy setting changed"),
        WatchedSetting(SettingScope.GLOBAL, "global_http_proxy_port", "The web proxy setting changed"),
        WatchedSetting(SettingScope.GLOBAL, "private_dns_mode", "The Private DNS mode changed"),
        WatchedSetting(SettingScope.GLOBAL, "private_dns_specifier", "The Private DNS server name changed"),
    )

    const val NETWORK_CHANGED = "The network connection changed"

    /**
     * The words for an app announcement, or null when it is one half of an update and the other half says it
     * (Android announces an update as a removal and an addition with the replacing flag set, then as a replacement).
     */
    fun packageEvent(action: PackageAction, replacing: Boolean, pkg: String): String? = when (action) {
        PackageAction.ADDED -> if (replacing) null else "An app was installed: $pkg"
        PackageAction.REMOVED -> if (replacing) null else "An app was removed: $pkg"
        PackageAction.REPLACED -> "An app was updated: $pkg"
        PackageAction.CHANGED -> "An app was switched on or off, or one of its parts changed: $pkg"
    }

    /** While the app is on screen the phone is also read again this often, for what no signal announces (see [REREAD_NOTE]). */
    const val POLL_SECONDS = 30L

    const val REREAD_NOTE =
        "A device administrator, the screen lock and special access (drawing over other apps, usage access, installing apps) sent no signal this app could hear " +
            "when this was tested, so while the app is on screen the phone is also read again every $POLL_SECONDS seconds."

    /** The measured expectations shown on the live view. The numbers are FINDINGS.md Q7: medians of 3 runs each, Android 14 emulator. */
    const val MEASURED =
        "Measured on one Android 14 emulator with the app in front: setting changes arrived in 0.06 to 0.3 seconds, " +
            "app installs, removals and switches in 0.1 to 0.75 seconds, and network changes in about 0.3 seconds."

    const val LIMIT_BACKGROUND =
        "With the app in the background, Android delivered almost nothing, and nothing at all once the app was no longer running. " +
            "So this view is live only while the app is on screen; changes made while it is closed are found when it is opened again."

    const val LIMIT_HIDDEN_APPS =
        "An app with no icon in the launcher and none of the four special components is never announced, " +
            "so this view can never tell you that no app was installed."
}

/** Hears the platform's change announcements and passes each on in plain words. Android's version is in `sensors-android`. */
interface ChangeWatcher {
    val running: Boolean

    /** Starts listening. Calling it while running does nothing. The sink may be called from any thread. */
    fun start(sink: (LiveSignal) -> Unit)

    /** Stops listening and releases everything it registered. Calling it while stopped does nothing. */
    fun stop()
}
