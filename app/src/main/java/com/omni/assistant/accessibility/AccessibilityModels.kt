package com.omni.assistant.accessibility

enum class AccessibilityStatus {
    NOT_ENABLED,
    DISCONNECTED,
    CONNECTED
}

data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    fun toCompactString(): String = "[$left,$top][$right,$bottom]"
}

data class UiElement(
    val id: String,
    val className: String,
    val text: String? = null,
    val contentDescription: String? = null,
    val isClickable: Boolean = false,
    val isEditable: Boolean = false,
    val isScrollable: Boolean = false,
    val isEnabled: Boolean = true,
    val isFocused: Boolean = false,
    val isSelected: Boolean = false,
    val isPassword: Boolean = false,
    val bounds: UiBounds,
    val availableActions: List<String> = emptyList()
) {
    fun toCompactMap(): Map<String, Any?> = buildMap {
        put("id", id)
        put("class", className.substringAfterLast('.'))
        if (!text.isNullOrBlank()) put("text", text)
        if (!contentDescription.isNullOrBlank()) put("desc", contentDescription)
        if (isClickable) put("clickable", true)
        if (isEditable) put("editable", true)
        if (isScrollable) put("scrollable", true)
        if (!isEnabled) put("enabled", false)
        if (isFocused) put("focused", true)
        if (isSelected) put("selected", true)
        if (isPassword) put("password", true)
        put("bounds", bounds.toCompactString())
        if (availableActions.isNotEmpty()) put("actions", availableActions)
    }
}

data class UiSnapshot(
    val snapshotId: String,
    val timestamp: Long,
    val packageName: String?,
    val elements: List<UiElement>,
    val totalNodesCount: Int,
    val isTruncated: Boolean,
    val summary: String
) {
    fun toCompactJsonMap(): Map<String, Any?> = mapOf(
        "snapshotId" to snapshotId,
        "timestamp" to timestamp,
        "package" to (packageName ?: "unknown"),
        "elementsCount" to elements.size,
        "isTruncated" to isTruncated,
        "summary" to summary,
        "interactiveElements" to elements.map { it.toCompactMap() }
    )
}

data class AutomationActionResult(
    val success: Boolean,
    val action: String,
    val message: String,
    val error: String? = null,
    val freshSnapshot: UiSnapshot? = null
)
