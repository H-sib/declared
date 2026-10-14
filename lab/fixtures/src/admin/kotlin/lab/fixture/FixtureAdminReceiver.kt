package lab.fixture

import android.app.admin.DeviceAdminReceiver

/** Declares a device-admin receiver with a single harmless policy. Does nothing when activated. */
class FixtureAdminReceiver : DeviceAdminReceiver()
