package com.omni.assistant.accessibility

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.UUID

object AccessibilitySnapshotEngine {

    const val MAX_SNAPSHOT_ELEMENTS = 120
    const val MAX_TREE_DEPTH = 64
    const val MAX_TEXT_LENGTH = 120

    private val SENSITIVE_KEYWORDS = setOf(
        "password", "passcode", "pin", "cvv", "security code",
        "otp", "one-time", "secret", "token", "ssn", "credit card"
    )

    fun createSnapshot(rootNode: AccessibilityNodeInfo?): UiSnapshot {
        val snapshotId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        if (rootNode == null) {
            return UiSnapshot(
                snapshotId = snapshotId,
                timestamp = timestamp,
                packageName = null,
                elements = emptyList(),
                totalNodesCount = 0,
                isTruncated = false,
                summary = "No active accessibility window or root node available."
            )
        }

        val packageName = rootNode.packageName?.toString()
        val collectedElements = mutableListOf<UiElement>()
        var totalNodesTraversed = 0
        var elementCounter = 1

        fun isSensitive(node: AccessibilityNodeInfo, text: String?, desc: String?): Boolean {
            if (node.isPassword) return true
            val combined = "${text ?: ""} ${desc ?: ""}".lowercase()
            return SENSITIVE_KEYWORDS.any { combined.contains(it) }
        }

        fun sanitizeText(raw: CharSequence?, isSensitive: Boolean): String? {
            if (raw == null) return null
            if (isSensitive) return "[PROTECTED]"
            val str = raw.toString().trim()
            if (str.isBlank()) return null
            return if (str.length > MAX_TEXT_LENGTH) str.take(MAX_TEXT_LENGTH) + "..." else str
        }

        fun traverse(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > MAX_TREE_DEPTH || collectedElements.size >= MAX_SNAPSHOT_ELEMENTS) {
                return
            }

            totalNodesTraversed++

            val rect = Rect()
            node.getBoundsInScreen(rect)
            val isOnScreen = rect.width() > 0 && rect.height() > 0 && rect.right > 0 && rect.bottom > 0
            val isVisible = node.isVisibleToUser || isOnScreen
            if (isVisible) {
                val rawText = node.text?.toString()
                val rawDesc = node.contentDescription?.toString()
                val isSens = isSensitive(node, rawText, rawDesc)

                val hasText = !rawText.isNullOrBlank()
                val hasDesc = !rawDesc.isNullOrBlank()
                val isClickable = node.isClickable
                val isEditable = node.isEditable || node.className?.contains("EditText", ignoreCase = true) == true
                val isScrollable = node.isScrollable
                val isCheckable = node.isCheckable
                val isFocusable = node.isFocusable

                // Include node if it is interactive or carries meaningful semantic text/content
                val isInteresting = isClickable || isEditable || isScrollable || isCheckable || hasText || hasDesc || isFocusable

                if (isInteresting) {

                    val actionsList = mutableListOf<String>()
                    if (isClickable) actionsList.add("click")
                    if (isEditable) actionsList.add("setText")
                    if (isScrollable) actionsList.add("scroll")
                    if (isCheckable) actionsList.add("check")

                    val elementId = "e${elementCounter++}"
                    collectedElements.add(
                        UiElement(
                            id = elementId,
                            className = node.className?.toString() ?: "View",
                            resourceId = node.viewIdResourceName,
                            text = sanitizeText(rawText, isSens),
                            contentDescription = sanitizeText(rawDesc, isSens),
                            isClickable = isClickable,
                            isEditable = isEditable,
                            isScrollable = isScrollable,
                            isEnabled = node.isEnabled,
                            isFocused = node.isFocused,
                            isSelected = node.isSelected,
                            isPassword = node.isPassword || isSens,
                            bounds = UiBounds(rect.left, rect.top, rect.right, rect.bottom),
                            availableActions = actionsList
                        )
                    )
                }
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                if (collectedElements.size >= MAX_SNAPSHOT_ELEMENTS) break
                val child = try {
                    node.getChild(i)
                } catch (_: Exception) {
                    null
                }
                if (child != null) {
                    traverse(child, depth + 1)
                    try {
                        child.recycle()
                    } catch (_: Exception) {}
                }
            }
        }

        traverse(rootNode, 0)

        val summary = buildSummary(packageName, collectedElements, totalNodesTraversed)

        com.omni.assistant.core.logging.OmniLogger.d("OmniA11ySnapshot", "createSnapshot: pkg=$packageName, traversed=$totalNodesTraversed, collected=${collectedElements.size}")
        return UiSnapshot(
            snapshotId = snapshotId,
            timestamp = timestamp,
            packageName = packageName,
            elements = collectedElements,
            totalNodesCount = totalNodesTraversed,
            isTruncated = collectedElements.size >= MAX_SNAPSHOT_ELEMENTS,
            summary = summary
        )
    }

    private fun buildSummary(pkg: String?, elements: List<UiElement>, totalNodes: Int): String {
        val appName = pkg?.substringAfterLast('.') ?: "Unknown"
        val clickableCount = elements.count { it.isClickable }
        val editableCount = elements.count { it.isEditable }
        val prominentTexts = elements
            .mapNotNull { it.text ?: it.contentDescription }
            .filter { it.isNotBlank() && it != "[PROTECTED]" }
            .take(5)

        val sampleStr = if (prominentTexts.isNotEmpty()) " (Key items: ${prominentTexts.joinToString(", ")})" else ""
        return "App: $appName, Elements: ${elements.size} interactive/labeled nodes out of $totalNodes traversed. ($clickableCount clickable, $editableCount editable)$sampleStr"
    }
}
