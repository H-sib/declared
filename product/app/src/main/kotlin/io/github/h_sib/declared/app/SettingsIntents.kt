package io.github.h_sib.declared.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import io.github.h_sib.declared.core.model.SettingsLink
import io.github.h_sib.declared.core.model.SettingsTarget

/** Turns the pure `SettingsLink` from core into an Android intent. Exhaustive over `SettingsTarget`, checked by the compiler. */
object SettingsIntents {

    fun of(link: SettingsLink): Intent = when (link.target) {
        SettingsTarget.ACCESSIBILITY -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        SettingsTarget.NOTIFICATION_ACCESS -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        SettingsTarget.SECURITY -> Intent(Settings.ACTION_SECURITY_SETTINGS)
        SettingsTarget.KEYBOARD -> Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        SettingsTarget.USAGE_ACCESS -> Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        SettingsTarget.OVERLAY -> Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri(link))
        SettingsTarget.INSTALL_UNKNOWN -> Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri(link))
        SettingsTarget.APP_DETAILS -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(link))
        SettingsTarget.DEVELOPER -> Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        SettingsTarget.DEVICE_INFO -> Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
        SettingsTarget.WIFI -> Intent(Settings.ACTION_WIFI_SETTINGS)
        SettingsTarget.NETWORK -> Intent(Settings.ACTION_WIRELESS_SETTINGS)
    }

    private fun packageUri(link: SettingsLink): Uri = Uri.fromParts("package", checkNotNull(link.pkg), null)
}
