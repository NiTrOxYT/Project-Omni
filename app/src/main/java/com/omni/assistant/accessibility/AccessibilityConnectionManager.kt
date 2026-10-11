package com.omni.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.omni.assistant.core.logging.OmniLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

object AccessibilityConnectionManager {

    private const val TAG = "OmniA11yManager"
    private var serviceRef: WeakReference<AccessibilityService>? = null

    private val _serviceStatus = MutableStateFlow(AccessibilityStatus.NOT_ENABLED)
    val serviceStatus: StateFlow<AccessibilityStatus> = _serviceStatus.asStateFlow()

    @Volatile
    private var lastSnapshot: UiSnapshot? = null

    fun onServiceConnected(service: AccessibilityService) {
        OmniLogger.i(TAG, "OmniAccessibilityService connected.")
        serviceRef = WeakReference(service)
        _serviceStatus.value = AccessibilityStatus.CONNECTED
    }

    fun onServiceDisconnected() {
        OmniLogger.w(TAG, "OmniAccessibilityService disconnected.")
        serviceRef = null
        _serviceStatus.value = AccessibilityStatus.DISCONNECTED
    }

    fun isServiceConnected(): Boolean {
        return _serviceStatus.value == AccessibilityStatus.CONNECTED && serviceRef?.get() != null
    }

    fun checkAndRefreshStatus(context: Context) {
        val isEnabledInSettings = isServiceEnabledInSettings(context)
        val isConnected = isServiceConnected()
        _serviceStatus.value = when {
            isConnected -> AccessibilityStatus.CONNECTED
            isEnabledInSettings -> AccessibilityStatus.DISCONNECTED
            else -> AccessibilityStatus.NOT_ENABLED
        }
    }

    fun isServiceEnabledInSettings(context: Context): Boolean {
        val expectedServiceName = "${context.packageName}/${OmniAccessibilityService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val accessibilityEnabled = Settings.Secure.getInt(
            context.contentResolver,
            Settings.Secure.ACCESSIBILITY_ENABLED,
            0
        ) == 1

        return accessibilityEnabled && enabledServices.contains(context.packageName)
    }

    fun createAccessibilitySettingsIntent(): Intent {
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
    }

    private fun getActiveRoot(): AccessibilityNodeInfo? {
        val service = serviceRef?.get() ?: return null
        try {
            val windows = service.windows
            if (!windows.isNullOrEmpty()) {
                val appWindows = windows.filter { 
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION 
                }
                val targetWindow = appWindows.find { it.isActive }
                    ?: appWindows.find { it.isFocused }
                    ?: appWindows.firstOrNull()
                val windowRoot = targetWindow?.root
                if (windowRoot != null) {
                    return windowRoot
                }
            }
        } catch (e: Exception) {
            OmniLogger.w(TAG, "Error inspecting service.windows: ${e.message}")
        }
        return try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            OmniLogger.e(TAG, "Failed to get rootInActiveWindow: ${e.message}")
            null
        }
    }

    fun takeSnapshot(): UiSnapshot {
        val root = getActiveRoot()
        val snapshot = AccessibilitySnapshotEngine.createSnapshot(root)
        try {
            root?.recycle()
        } catch (_: Exception) {}
        lastSnapshot = snapshot
        return snapshot
    }

    fun clearSnapshots() {
        lastSnapshot = null
        OmniLogger.d(TAG, "Transient UI snapshots and element caches cleared.")
    }

    suspend fun performTap(snapshotId: String, elementId: String): AutomationActionResult {
        if (!isServiceConnected()) {
            return AutomationActionResult(
                success = false,
                action = "tap_element",
                message = "Accessibility service is not connected. Enable Omni in Accessibility Settings.",
                error = "SERVICE_NOT_CONNECTED"
            )
        }

        val cachedSnapshot = lastSnapshot
        if (cachedSnapshot == null || cachedSnapshot.snapshotId != snapshotId) {
            return AutomationActionResult(
                success = false,
                action = "tap_element",
                message = "Snapshot '$snapshotId' is stale or invalid. Please request a fresh UI snapshot.",
                error = "STALE_SNAPSHOT"
            )
        }

        val targetElement = cachedSnapshot.elements.find { it.id == elementId }
            ?: return AutomationActionResult(
                success = false,
                action = "tap_element",
                message = "Element '$elementId' was not found in snapshot '$snapshotId'.",
                error = "ELEMENT_NOT_FOUND"
            )

        val root = getActiveRoot() ?: return AutomationActionResult(
            success = false,
            action = "tap_element",
            message = "Unable to inspect active window. Screen may have changed or locked.",
            error = "NO_ACTIVE_WINDOW"
        )

        val nodeToClick = findMatchingNode(root, targetElement)
        if (nodeToClick == null) {
            try { root.recycle() } catch (_: Exception) {}
            return AutomationActionResult(
                success = false,
                action = "tap_element",
                message = "Element '$elementId' (${targetElement.text ?: targetElement.contentDescription ?: targetElement.className}) is no longer available on screen.",
                error = "NODE_NOT_RESOLVED"
            )
        }

        var clickAccepted = if (nodeToClick.isClickable) {
            nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            // Find clickable parent if child wasn't marked clickable
            var parent: AccessibilityNodeInfo? = nodeToClick.parent
            var accepted = false
            while (parent != null) {
                if (parent.isClickable) {
                    accepted = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    break
                }
                val nextParent = parent.parent
                try { parent.recycle() } catch (_: Exception) {}
                parent = nextParent
            }
            if (!accepted) {
                // Fallback to direct click action attempt
                nodeToClick.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            } else {
                true
            }
        }

        try { nodeToClick.recycle() } catch (_: Exception) {}
        try { root.recycle() } catch (_: Exception) {}

        if (!clickAccepted) {
            // Fallback to gesture tap at element center if accessibility click action is rejected
            val service = serviceRef?.get()
            if (service != null && targetElement.bounds.right > targetElement.bounds.left && targetElement.bounds.bottom > targetElement.bounds.top) {
                val cx = (targetElement.bounds.left + targetElement.bounds.right) / 2f
                val cy = (targetElement.bounds.top + targetElement.bounds.bottom) / 2f
                val path = Path().apply { moveTo(cx, cy) }
                val stroke = GestureDescription.StrokeDescription(path, 0, 100)
                val gesture = GestureDescription.Builder().addStroke(stroke).build()
                clickAccepted = service.dispatchGesture(gesture, null, null)
            }
        }

        if (!clickAccepted) {
            return AutomationActionResult(
                success = false,
                action = "tap_element",
                message = "Click action on '$elementId' was rejected by the system or target node.",
                error = "ACTION_REJECTED"
            )
        }

        // Allow UI transition
        delay(600)
        val freshSnapshot = takeSnapshot()

        return AutomationActionResult(
            success = true,
            action = "tap_element",
            message = "Tapped element '$elementId' (${targetElement.text ?: targetElement.contentDescription ?: targetElement.className}).",
            freshSnapshot = freshSnapshot
        )
    }

    suspend fun performEnterText(snapshotId: String, elementId: String, text: String): AutomationActionResult {
        if (!isServiceConnected()) {
            return AutomationActionResult(
                success = false,
                action = "enter_text",
                message = "Accessibility service is not connected.",
                error = "SERVICE_NOT_CONNECTED"
            )
        }

        val cachedSnapshot = lastSnapshot
        if (cachedSnapshot == null || cachedSnapshot.snapshotId != snapshotId) {
            return AutomationActionResult(
                success = false,
                action = "enter_text",
                message = "Snapshot '$snapshotId' is stale. Please request a fresh UI snapshot.",
                error = "STALE_SNAPSHOT"
            )
        }

        val targetElement = cachedSnapshot.elements.find { it.id == elementId }
            ?: return AutomationActionResult(
                success = false,
                action = "enter_text",
                message = "Element '$elementId' was not found in snapshot '$snapshotId'.",
                error = "ELEMENT_NOT_FOUND"
            )

        if (targetElement.isPassword) {
            return AutomationActionResult(
                success = false,
                action = "enter_text",
                message = "Entering credentials or password into protected fields is blocked for security.",
                error = "PASSWORD_FIELD_REJECTED"
            )
        }

        val root = getActiveRoot() ?: return AutomationActionResult(
            success = false,
            action = "enter_text",
            message = "Unable to access active window.",
            error = "NO_ACTIVE_WINDOW"
        )

        val nodeToEdit = findMatchingNode(root, targetElement)
        if (nodeToEdit == null) {
            try { root.recycle() } catch (_: Exception) {}
            return AutomationActionResult(
                success = false,
                action = "enter_text",
                message = "Editable field '$elementId' is no longer visible.",
                error = "NODE_NOT_RESOLVED"
            )
        }

        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        var textAccepted = nodeToEdit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (!textAccepted) {
            nodeToEdit.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            delay(200)
            textAccepted = nodeToEdit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }

        try { nodeToEdit.recycle() } catch (_: Exception) {}
        try { root.recycle() } catch (_: Exception) {}

        if (!textAccepted) {
            return AutomationActionResult(
                success = false,
                action = "enter_text",
                message = "Setting text on element '$elementId' was not accepted by the target node.",
                error = "ACTION_REJECTED"
            )
        }

        delay(400)
        val freshSnapshot = takeSnapshot()

        return AutomationActionResult(
            success = true,
            action = "enter_text",
            message = "Entered text into editable element '$elementId'.",
            freshSnapshot = freshSnapshot
        )
    }

    suspend fun performScroll(snapshotId: String, elementId: String?, direction: String): AutomationActionResult {
        if (!isServiceConnected()) {
            return AutomationActionResult(
                success = false,
                action = "scroll_ui",
                message = "Accessibility service is not connected.",
                error = "SERVICE_NOT_CONNECTED"
            )
        }

        val root = getActiveRoot() ?: return AutomationActionResult(
            success = false,
            action = "scroll_ui",
            message = "No active accessibility window available.",
            error = "NO_ACTIVE_WINDOW"
        )

        val isForward = direction.uppercase().let { it == "FORWARD" || it == "DOWN" || it == "NEXT" }
        val actionToPerform = if (isForward) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        }

        var scrollNode: AccessibilityNodeInfo? = null

        if (!elementId.isNullOrBlank()) {
            val cachedSnapshot = lastSnapshot
            val targetElement = cachedSnapshot?.elements?.find { it.id == elementId }
            if (targetElement != null) {
                scrollNode = findMatchingNode(root, targetElement)
            }
        }

        if (scrollNode == null) {
            // Find first scrollable container in root hierarchy
            scrollNode = findFirstScrollableNode(root)
        }

        if (scrollNode == null) {
            try { root.recycle() } catch (_: Exception) {}
            return AutomationActionResult(
                success = false,
                action = "scroll_ui",
                message = "No scrollable element found on the active screen.",
                error = "NO_SCROLLABLE_CONTAINER"
            )
        }

        val scrollAccepted = scrollNode.performAction(actionToPerform)
        try { scrollNode.recycle() } catch (_: Exception) {}
        try { root.recycle() } catch (_: Exception) {}

        if (!scrollAccepted) {
            return AutomationActionResult(
                success = false,
                action = "scroll_ui",
                message = "Scroll action ($direction) was not accepted by container.",
                error = "SCROLL_REJECTED"
            )
        }

        delay(600)
        val freshSnapshot = takeSnapshot()

        return AutomationActionResult(
            success = true,
            action = "scroll_ui",
            message = "Scrolled screen ($direction).",
            freshSnapshot = freshSnapshot
        )
    }

    suspend fun performNavigateBack(): AutomationActionResult {
        val service = serviceRef?.get() ?: return AutomationActionResult(
            success = false,
            action = "navigate_back",
            message = "Accessibility service is not connected.",
            error = "SERVICE_NOT_CONNECTED"
        )

        val backAccepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        if (!backAccepted) {
            return AutomationActionResult(
                success = false,
                action = "navigate_back",
                message = "Global back action was not accepted by the system.",
                error = "ACTION_REJECTED"
            )
        }

        delay(600)
        val freshSnapshot = takeSnapshot()

        return AutomationActionResult(
            success = true,
            action = "navigate_back",
            message = "Navigated backward successfully.",
            freshSnapshot = freshSnapshot
        )
    }

    private fun findMatchingNode(root: AccessibilityNodeInfo, target: UiElement): AccessibilityNodeInfo? {
        val targetRect = Rect(target.bounds.left, target.bounds.top, target.bounds.right, target.bounds.bottom)

        fun search(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            val nodeRect = Rect()
            node.getBoundsInScreen(nodeRect)

            val textMatches = (node.text?.toString() ?: "") == (target.text ?: "")
            val descMatches = (node.contentDescription?.toString() ?: "") == (target.contentDescription ?: "")
            val resIdMatches = !target.resourceId.isNullOrBlank() && (node.viewIdResourceName ?: "") == target.resourceId
            val classMatches = (node.className?.toString() ?: "").endsWith(target.className.substringAfterLast('.'))
            val boundsMatch = nodeRect == targetRect

            if (boundsMatch && (textMatches || descMatches || resIdMatches || classMatches)) {
                return AccessibilityNodeInfo.obtain(node)
            }

            for (i in 0 until node.childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null) {
                    val result = search(child)
                    try { child.recycle() } catch (_: Exception) {}
                    if (result != null) return result
                }
            }
            return null
        }

        return search(root)
    }

    private fun findFirstScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return AccessibilityNodeInfo.obtain(node)

        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) {
                val result = findFirstScrollableNode(child)
                try { child.recycle() } catch (_: Exception) {}
                if (result != null) return result
            }
        }
        return null
    }
}
