package io.github.h_sib.declared.app

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.h_sib.declared.app.ui.AutoCheckControls
import io.github.h_sib.declared.app.ui.DeclaredApp
import io.github.h_sib.declared.app.ui.DeclaredTheme
import io.github.h_sib.declared.app.ui.LocalAutoCheck
import io.github.h_sib.declared.app.ui.LocalExpandAll
import io.github.h_sib.declared.app.ui.LocalUiPrefs
import io.github.h_sib.declared.app.ui.UiPrefs
import io.github.h_sib.declared.core.flow.Event
import io.github.h_sib.declared.core.flow.Screen
import io.github.h_sib.declared.core.model.SettingsLink
import io.github.h_sib.declared.core.schedule.AutoConfig

class MainActivity : ComponentActivity() {

    /** Opens the place where the user can change what a finding is about. Falls back to Android's main settings. */
    private fun openSettings(link: SettingsLink) {
        try {
            startActivity(SettingsIntents.of(link))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private val viewModel: ReportViewModel by viewModels()

    private val uiPrefs by lazy { UiPrefs(getSharedPreferences(UiPrefs.FILE, MODE_PRIVATE), lab = Conformance.allowCapture(this)) }

    private val autoStore by lazy { AutoCheckStore(this) }
    private var autoConfig by mutableStateOf(AutoConfig())
    private var notificationsAllowed by mutableStateOf(true)

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { notificationsAllowed = canNotify() }

    private fun canNotify() = getSystemService(NotificationManager::class.java).areNotificationsEnabled()

    private fun changeAuto(config: AutoConfig) {
        autoConfig = config
        autoStore.save(config)
        AutoCheckScheduler.apply(this, config)
        // Android asks the person itself, once, when the feature is turned on; before Android 13 there is nothing to ask.
        if (config.enabled && !notificationsAllowed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openNotificationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // Screenshots are allowed unless the person turns on "Block screenshots" under More. Blocking keeps every screen, which lists what is installed,
    // out of screenshots, recordings and the recent-apps preview. It does not stop an app holding the accessibility power from reading the screen
    // (said on the blind-spot map). A lab script can always take screenshots.
    private fun applyCaptureFlag() {
        if (uiPrefs.blockScreenshots && !Conformance.allowCapture(this)) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onStart() {
        super.onStart()
        viewModel.screenStarted()
    }

    override fun onStop() {
        viewModel.screenStopped()
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        autoConfig = autoStore.config()
        AutoCheckScheduler.apply(this, autoConfig)
        notificationsAllowed = canNotify()
        applyCaptureFlag()
        Conformance.attach(this, viewModel.state)
        val expandAll = Conformance.expandAll(this)
        setContent {
            val state by viewModel.state.collectAsStateWithLifecycle()
            // A lab script (and later an app shortcut) can ask for the Check screen directly: --es start check. Done once, when there is a result to show.
            var startHandled by rememberSaveable { mutableStateOf(intent.getStringExtra("start") != "check") }
            LaunchedEffect(state.report != null, state.stack, startHandled) {
                if (!startHandled) {
                    // The reducer ignores the request while the first review is still to be done; the stack changes when it is finished and this runs again.
                    if (Screen.Check in state.stack) startHandled = true else if (state.report != null) viewModel.dispatch(Event.OpenCheck)
                }
            }
            // The user may change a setting and come back; read again so the screen is never stale.
            LaunchedEffect(uiPrefs.blockScreenshots) { applyCaptureFlag() }
            LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                viewModel.dispatch(Event.Refresh)
                notificationsAllowed = canNotify()
            }
            DeclaredTheme(large = uiPrefs.easy) {
                val auto = AutoCheckControls(autoConfig, notificationsAllowed, ::changeAuto, ::openNotificationSettings)
                CompositionLocalProvider(LocalExpandAll provides expandAll, LocalUiPrefs provides uiPrefs, LocalAutoCheck provides auto) {
                    DeclaredApp(
                        state = state,
                        onEvent = viewModel::dispatch,
                        onOpenSettings = ::openSettings,
                    )
                }
            }
        }
    }
}
