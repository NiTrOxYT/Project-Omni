package com.omni.assistant.ai

import com.omni.assistant.ai.bridge.AiAppBridge
import com.omni.assistant.ai.bridge.AiBridgeQuery
import com.omni.assistant.ai.bridge.SupportedAiApp
import com.omni.assistant.device.AppInventoryProvider
import com.omni.assistant.execution.CommandDispatcher
import com.omni.assistant.execution.ToolRegistry

sealed interface LocalPlanResult {
    data class DirectToolExecution(
        val toolName: String,
        val arguments: Map<String, Any?> = emptyMap(),
        val description: String
    ) : LocalPlanResult

    data class NeedsAiBridge(
        val prompt: String,
        val targetApp: SupportedAiApp = SupportedAiApp.AUTO
    ) : LocalPlanResult

    data class Ambiguous(
        val clarificationMessage: String
    ) : LocalPlanResult
}

class LocalTaskPlanner(
    private val toolRegistry: ToolRegistry,
    private val appInventoryProvider: AppInventoryProvider,
    private val aiAppBridge: AiAppBridge
) {
    fun plan(input: String, preferredAiApp: SupportedAiApp = SupportedAiApp.AUTO): LocalPlanResult {
        val trimmed = input.trim()
        val normalized = trimmed.lowercase()

        // 1. Explicit AI app query checks
        if (normalized.startsWith("ask chatgpt") || normalized.startsWith("use chatgpt to") || normalized.startsWith("chatgpt:")) {
            val prompt = trimmed
                .removePrefix("ask chatgpt")
                .removePrefix("Ask chatgpt")
                .removePrefix("use chatgpt to")
                .removePrefix("chatgpt:")
                .trim()
            return LocalPlanResult.NeedsAiBridge(prompt.ifBlank { trimmed }, SupportedAiApp.CHATGPT)
        }

        if (normalized.startsWith("ask gemini") || normalized.startsWith("use gemini to") || normalized.startsWith("gemini:")) {
            val prompt = trimmed
                .removePrefix("ask gemini")
                .removePrefix("Ask gemini")
                .removePrefix("use gemini to")
                .removePrefix("gemini:")
                .trim()
            return LocalPlanResult.NeedsAiBridge(prompt.ifBlank { trimmed }, SupportedAiApp.GEMINI)
        }

        // 2. Deterministic Battery
        if (normalized.contains("battery") || normalized.contains("charge") || normalized.contains("power level") || normalized.contains("juice")) {
            return LocalPlanResult.DirectToolExecution("get_battery_info", emptyMap(), "Checking battery status")
        }

        // 3. Deterministic Storage
        if (normalized.contains("storage") || normalized.contains("disk") || normalized.contains("free space") || normalized.contains("available memory")) {
            return LocalPlanResult.DirectToolExecution("get_storage_info", emptyMap(), "Checking storage space")
        }

        // 4. Deterministic Device Info
        if (normalized.contains("device info") || normalized.contains("phone info") || normalized.contains("device specs") || normalized.contains("model number") || normalized.contains("os version")) {
            return LocalPlanResult.DirectToolExecution("get_device_info", emptyMap(), "Retrieving device details")
        }

        // 5. Deterministic App Version
        if (normalized.contains("app version") || normalized.contains("version of omni") || normalized.contains("build version")) {
            return LocalPlanResult.DirectToolExecution("get_app_version", emptyMap(), "Checking Omni version")
        }

        // 6. Accessibility Service Status
        if (normalized.contains("accessibility status") || normalized.contains("is accessibility enabled") || normalized.contains("check accessibility")) {
            return LocalPlanResult.DirectToolExecution("get_accessibility_status", emptyMap(), "Checking accessibility status")
        }

        // 7. Inspect / Describe Current Screen
        if (normalized.contains("ui snapshot") || 
            (normalized.contains("screen") && (normalized.contains("describe") || normalized.contains("inspect") || normalized.contains("view") || normalized.contains("what is on") || normalized.contains("read") || normalized.contains("see")))
        ) {
            return LocalPlanResult.DirectToolExecution("get_ui_snapshot", emptyMap(), "Inspecting active screen")
        }

        // 8. Navigation Back
        if (normalized == "back" || normalized == "go back" || normalized.contains("navigate back") || normalized.contains("press back")) {
            return LocalPlanResult.DirectToolExecution("navigate_back", emptyMap(), "Navigating backward")
        }

        // 9. Scroll Screen
        if (normalized.contains("scroll down") || normalized.contains("scroll forward") || normalized == "scroll") {
            return LocalPlanResult.DirectToolExecution("scroll_ui", mapOf("direction" to "DOWN"), "Scrolling down")
        }
        if (normalized.contains("scroll up") || normalized.contains("scroll backward")) {
            return LocalPlanResult.DirectToolExecution("scroll_ui", mapOf("direction" to "UP"), "Scrolling up")
        }

        // 10. Launch Application
        if (normalized.startsWith("open ") || normalized.startsWith("launch ") || normalized.startsWith("start ")) {
            val appQuery = trimmed
                .removePrefix("open ")
                .removePrefix("Open ")
                .removePrefix("launch ")
                .removePrefix("Launch ")
                .removePrefix("start ")
                .removePrefix("Start ")
                .trim()
                .removeSuffix(" app")
                .removeSuffix(" application")

            if (appQuery.isNotBlank()) {
                return LocalPlanResult.DirectToolExecution(
                    toolName = "launch_app",
                    arguments = mapOf("appName" to appQuery),
                    description = "Launching $appQuery"
                )
            }
        }

        // 11. General natural language or complex reasoning -> route to on-device AI app bridge
        return LocalPlanResult.NeedsAiBridge(trimmed, preferredAiApp)
    }
}
