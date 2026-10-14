package lab.fixture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Declares window-content and gesture capabilities and ignores every event. Part of the attack-path fixture. */
class ShadowA11yService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
