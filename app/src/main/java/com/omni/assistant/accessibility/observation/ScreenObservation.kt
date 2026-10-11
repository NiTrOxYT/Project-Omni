package com.omni.assistant.accessibility.observation

import com.omni.assistant.accessibility.UiElement
import com.omni.assistant.accessibility.UiSnapshot

data class ScreenObservation(
    val snapshotId: String,
    val timestamp: Long,
    val packageName: String?,
    val screenClassification: ScreenClassification,
    val primaryTitle: String?,
    val interactiveCount: Int,
    val editableControls: List<UiElement>,
    val clickableControls: List<UiElement>,
    val prominentTexts: List<String>,
    val status: ObservationStatus,
    val compactSummary: String,
    val rawSnapshot: UiSnapshot
) {
    fun toStateKey(): String {
        val pkg = packageName ?: "unknown"
        return "pkg:$pkg|screen:$screenClassification"
    }
}
