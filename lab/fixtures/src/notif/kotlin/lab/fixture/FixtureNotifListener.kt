package lab.fixture

import android.service.notification.NotificationListenerService

/** Registers as a notification listener. Overrides nothing, so it never reads a notification. */
class FixtureNotifListener : NotificationListenerService()
