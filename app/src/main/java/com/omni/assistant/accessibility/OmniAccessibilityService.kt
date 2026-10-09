package com.omni.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.omni.assistant.core.logging.OmniLogger

class OmniAccessibilityService : AccessibilityService() {

    private val tag = "OmniA11yService"

    override fun onServiceConnected() {
        super.onServiceConnected()
        OmniLogger.i(tag, "Omni Accessibility Service attached and connected.")
        AccessibilityConnectionManager.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Non-blocking event receiver
        if (event == null) return
    }

    override fun onInterrupt() {
        OmniLogger.w(tag, "Omni Accessibility Service interrupted.")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        OmniLogger.i(tag, "Omni Accessibility Service unbinding.")
        AccessibilityConnectionManager.onServiceDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        OmniLogger.i(tag, "Omni Accessibility Service destroyed.")
        AccessibilityConnectionManager.onServiceDisconnected()
    }
}
