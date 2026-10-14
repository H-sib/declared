package lab.fixture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Declares window-content and gesture capabilities so the probe can read the flags. Ignores every event. */
class FixtureA11yService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
