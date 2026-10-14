package lab.fixture

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** Exists only so the package has a component. Binds to nothing. */
class HiddenService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
