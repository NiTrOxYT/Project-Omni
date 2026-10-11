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
        val targetApp: SupportedAiApp = SupportedAiApp.AUTO,
        val isDirectQuery: Boolean = false
    ) : LocalPlanResult

    data class Ambiguous(
        val clarificationMessage: String
    ) : LocalPlanResult

    data class UserConfirmation(
        val isConfirmed: Boolean
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
            return LocalPlanResult.NeedsAiBridge(prompt.ifBlank { trimmed }, SupportedAiApp.CHATGPT, isDirectQuery = true)
        }

        if (normalized.startsWith("ask gemini") || normalized.startsWith("use gemini to") || normalized.startsWith("gemini:")) {
            val prompt = trimmed
                .removePrefix("ask gemini")
                .removePrefix("Ask gemini")
                .removePrefix("use gemini to")
                .removePrefix("gemini:")
                .trim()
            return LocalPlanResult.NeedsAiBridge(prompt.ifBlank { trimmed }, SupportedAiApp.GEMINI, isDirectQuery = true)
        }

        // 2. User Confirmation for Pending Actions
        if (normalized == "yes" || normalized == "confirm" || normalized == "send" || normalized == "send it" || normalized == "proceed" || normalized == "approve") {
            return LocalPlanResult.UserConfirmation(isConfirmed = true)
        }
        if (normalized == "no" || normalized == "cancel" || normalized == "abort" || normalized == "stop" || normalized == "reject") {
            return LocalPlanResult.UserConfirmation(isConfirmed = false)
        }

        // 3. Deterministic Battery
        if (normalized.contains("battery") || normalized.contains("charge") || normalized.contains("power level") || normalized.contains("juice")) {
            return LocalPlanResult.DirectToolExecution("get_battery_info", emptyMap(), "Checking battery status")
        }

        // 4. Deterministic Storage
        if (normalized.contains("storage") || normalized.contains("disk") || normalized.contains("free space") || normalized.contains("available memory")) {
            return LocalPlanResult.DirectToolExecution("get_storage_info", emptyMap(), "Checking storage space")
        }

        // 5. Deterministic Device Info
        if (normalized.contains("device info") || normalized.contains("phone info") || normalized.contains("device specs") || normalized.contains("model number") || normalized.contains("os version")) {
            return LocalPlanResult.DirectToolExecution("get_device_info", emptyMap(), "Retrieving device details")
        }

        // 6. Deterministic App Version
        if (normalized.contains("app version") || normalized.contains("version of omni") || normalized.contains("build version")) {
            return LocalPlanResult.DirectToolExecution("get_app_version", emptyMap(), "Checking Omni version")
        }

        // 7. Accessibility Service Status
        if (normalized.contains("accessibility status") || normalized.contains("is accessibility enabled") || normalized.contains("check accessibility")) {
            return LocalPlanResult.DirectToolExecution("get_accessibility_status", emptyMap(), "Checking accessibility status")
        }

        // 8. Inspect / Describe Current Screen
        if (normalized.contains("ui snapshot") || 
            (normalized.contains("screen") && (normalized.contains("describe") || normalized.contains("inspect") || normalized.contains("view") || normalized.contains("what is on") || normalized.contains("read") || normalized.contains("see")))
        ) {
            return LocalPlanResult.DirectToolExecution("get_ui_snapshot", emptyMap(), "Inspecting active screen")
        }

        // 9. Navigation Back
        if (normalized == "back" || normalized == "go back" || normalized.contains("navigate back") || normalized.contains("press back")) {
            return LocalPlanResult.DirectToolExecution("navigate_back", emptyMap(), "Navigating backward")
        }

        // 10. Scroll Screen
        if (normalized.contains("scroll down") || normalized.contains("scroll forward") || normalized == "scroll") {
            return LocalPlanResult.DirectToolExecution("scroll_ui", mapOf("direction" to "DOWN"), "Scrolling down")
        }
        if (normalized.contains("scroll up") || normalized.contains("scroll backward")) {
            return LocalPlanResult.DirectToolExecution("scroll_ui", mapOf("direction" to "UP"), "Scrolling up")
        }

        // 11. Natural Language Messaging (WhatsApp)
        val messagingTask = com.omni.assistant.automation.messaging.MessagingCommandParser.parse(trimmed)
        if (messagingTask != null) {
            return LocalPlanResult.DirectToolExecution(
                toolName = "send_whatsapp_message",
                arguments = mapOf(
                    "recipient" to messagingTask.recipient,
                    "message" to messagingTask.messageText
                ),
                description = "Processing WhatsApp message to '${messagingTask.recipient}'"
            )
        }

        val incompleteRecipient = com.omni.assistant.automation.messaging.MessagingCommandParser.detectIncompleteRecipient(trimmed)
        if (incompleteRecipient != null) {
            return LocalPlanResult.Ambiguous(
                "Please specify what message you would like to send to $incompleteRecipient (e.g. \"Open WhatsApp and text $incompleteRecipient: how are you\")."
            )
        }

        // 12. Launch Application
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

        // 13. General natural language or complex reasoning -> route to on-device AI app bridge
        return LocalPlanResult.NeedsAiBridge(trimmed, preferredAiApp)
    }

    fun extractTaskCategory(input: String): String {
        val normalized = input.trim().lowercase()
        return when {
            normalized.contains("whatsapp") || normalized.contains("text ") || normalized.contains("message ") -> "MESSAGING_WHATSAPP"
            normalized.contains("setting") || normalized.contains("bluetooth") || normalized.contains("wifi") || normalized.contains("volume") -> "SETTINGS_CONTROL"
            normalized.contains("battery") || normalized.contains("storage") || normalized.contains("device info") -> "DEVICE_INSPECTION"
            normalized.startsWith("open ") || normalized.startsWith("launch ") -> "APP_LAUNCH"
            else -> "GENERAL_AUTOMATION"
        }
    }

    fun extractIntendedOutcome(input: String): String {
        val normalized = input.trim().lowercase()
        return when {
            normalized.contains("whatsapp") || normalized.contains("text ") || normalized.contains("message ") -> "SEND_WHATSAPP_MESSAGE"
            normalized.contains("setting") -> "ADJUST_SETTING"
            normalized.contains("battery") -> "CHECK_BATTERY"
            normalized.contains("storage") -> "CHECK_STORAGE"
            normalized.contains("device info") -> "CHECK_DEVICE_INFO"
            normalized.startsWith("open ") || normalized.startsWith("launch ") -> "LAUNCH_APP"
            else -> "EXECUTE_USER_INTENT"
        }
    }

    fun extractGeneralizedInitialState(input: String, currentPackage: String?): String {
        val normalized = input.trim().lowercase()
        return when {
            normalized.contains("whatsapp") || normalized.contains("text ") || normalized.contains("message ") -> "pkg:com.whatsapp|screen:CHAT_LIST"
            normalized.contains("setting") -> "pkg:com.android.settings|screen:SETTINGS_MAIN"
            currentPackage != null -> "pkg:$currentPackage"
            else -> "pkg:unknown"
        }
    }
}
