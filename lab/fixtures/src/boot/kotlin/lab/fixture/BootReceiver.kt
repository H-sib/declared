package lab.fixture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Declares a boot-completed receiver and does nothing when it fires. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
