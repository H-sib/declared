package lab.fixture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Declares an SMS-received receiver. It never touches the message: onReceive does nothing. */
class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = Unit
}
