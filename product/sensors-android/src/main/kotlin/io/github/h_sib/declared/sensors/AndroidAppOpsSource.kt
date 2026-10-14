package io.github.h_sib.declared.sensors

import android.app.AppOpsManager
import android.content.Context
import io.github.h_sib.declared.core.sensor.AppOpsSource
import io.github.h_sib.declared.core.sensor.OpMode
import io.github.h_sib.declared.core.sensor.ReadResult
import io.github.h_sib.declared.core.sensor.SpecialAccess

/**
 * Another app's special-access state, from `AppOpsManager.unsafeCheckOpNoThrow` (lab P7: readable from Android 12,
 * refused with a SecurityException on Android 10, Android 11 not measured). A refusal comes back as
 * [ReadResult.Refused] for the whole package so the pipeline can report "Android refuses this on this version".
 */
class AndroidAppOpsSource(context: Context) : AppOpsSource {

    private val ops = context.getSystemService(AppOpsManager::class.java)

    override fun modes(pkg: String, uid: Int): ReadResult<Map<SpecialAccess, OpMode>> = guarded {
        // Deprecated in the newest SDK but still the call lab P7 verified on Android 12 to 17. The replacement has not
        // been measured, so the suppression stays until it is.
        @Suppress("DEPRECATION")
        SpecialAccess.entries.associateWith { access -> modeOf(ops.unsafeCheckOpNoThrow(opName(access), uid, pkg)) }
    }

    private fun opName(access: SpecialAccess): String = when (access) {
        SpecialAccess.OVERLAY -> "android:system_alert_window"
        SpecialAccess.USAGE_ACCESS -> "android:get_usage_stats"
        SpecialAccess.INSTALL_UNKNOWN_APPS -> "android:request_install_packages"
    }

    private fun modeOf(mode: Int): OpMode = when (mode) {
        AppOpsManager.MODE_ALLOWED -> OpMode.ALLOWED
        AppOpsManager.MODE_IGNORED -> OpMode.IGNORED
        AppOpsManager.MODE_ERRORED -> OpMode.ERRORED
        AppOpsManager.MODE_DEFAULT -> OpMode.DEFAULT
        AppOpsManager.MODE_FOREGROUND -> OpMode.FOREGROUND
        else -> OpMode.UNKNOWN
    }
}
