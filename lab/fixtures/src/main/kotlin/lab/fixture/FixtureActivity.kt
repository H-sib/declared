package lab.fixture

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Benign fixture. It only shows which role it plays and offers shortcuts to the matching Settings screens
 * so a human can set the capability up through the real UI (which also measures restricted-settings gating).
 */
class FixtureActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "Benign fixture: ${BuildConfig.ROLE}\n" +
                "Declares a capability so the observability probe has ground truth. " +
                "It ignores every event and does nothing else."
        })

        fun action(label: String, make: () -> Intent) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    try {
                        startActivity(make())
                    } catch (e: Exception) {
                        text = "$label (failed: ${e.javaClass.simpleName})"
                    }
                }
            })
        }

        val packageUri = Uri.parse("package:$packageName")
        when (BuildConfig.ROLE) {
            "a11y" -> action("Accessibility settings") { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
            "notif" -> action("Notification access settings") { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
            "admin" -> action("Request device admin") {
                Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(
                    DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                    ComponentName(packageName, "lab.fixture.FixtureAdminReceiver"),
                )
            }
            "ime" -> action("Keyboard settings") { Intent(Settings.ACTION_INPUT_METHOD_SETTINGS) }
            "perms" -> {
                action("Overlay permission") { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri) }
                action("Install unknown apps") { Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, packageUri) }
                action("Usage access") { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) }
                root.addView(Button(this).apply {
                    text = "Request runtime permissions"
                    setOnClickListener {
                        requestPermissions(
                            arrayOf(
                                Manifest.permission.READ_SMS,
                                Manifest.permission.RECEIVE_SMS,
                                Manifest.permission.READ_CONTACTS,
                                Manifest.permission.RECORD_AUDIO,
                                Manifest.permission.ACCESS_FINE_LOCATION,
                            ),
                            1,
                        )
                    }
                })
            }
        }

        setContentView(ScrollView(this).apply { addView(root) })
    }
}
