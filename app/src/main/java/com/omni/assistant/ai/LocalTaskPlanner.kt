package com.omni.assistant.ai

import com.omni.assistant.ai.bridge.AiAppBridge
import com.omni.assistant.ai.bridge.SupportedAiApp
import com.omni.assistant.ai.intent.IntentClassifier
import com.omni.assistant.ai.intent.UserIntent
import com.omni.assistant.device.AppInventoryProvider
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
        val isDirectQuery: Boolean = false,
        val isGeneralQuestion: Boolean = false
    ) : LocalPlanResult

    data class Ambiguous(
        val clarificationMessage: String
    ) : LocalPlanResult

    data class UserConfirmation(
        val isConfirmed: Boolean
    ) : LocalPlanResult

    data class MultiStepPlan(
        val targetApp: String?,
        val stepAction: String,
        val targetElementText: String?,
        val rawRequest: String
    ) : LocalPlanResult
}

class LocalTaskPlanner(
    private val toolRegistry: ToolRegistry,
    private val appInventoryProvider: AppInventoryProvider,
    private val aiAppBridge: AiAppBridge,
    private val intentClassifier: IntentClassifier = IntentClassifier(appInventoryProvider)
) {
    fun plan(input: String, preferredAiApp: SupportedAiApp = SupportedAiApp.AUTO): LocalPlanResult {
        val trimmed = input.trim()
        val intent = intentClassifier.classify(trimmed, preferredAiApp)

        return when (intent) {
            is UserIntent.UserConfirmation -> {
                LocalPlanResult.UserConfirmation(isConfirmed = intent.isConfirmed)
            }

            is UserIntent.Ambiguous -> {
                LocalPlanResult.Ambiguous(clarificationMessage = intent.clarificationPrompt)
            }

            is UserIntent.SensitiveAction -> {
                LocalPlanResult.DirectToolExecution(
                    toolName = intent.toolName,
                    arguments = intent.arguments,
                    description = intent.description
                )
            }

            is UserIntent.DeviceQuery -> {
                LocalPlanResult.DirectToolExecution(
                    toolName = intent.toolName,
                    arguments = intent.arguments,
                    description = intent.description
                )
            }

            is UserIntent.AppAction -> {
                LocalPlanResult.DirectToolExecution(
                    toolName = "launch_app",
                    arguments = mapOf("appName" to intent.appName),
                    description = "Launching ${intent.appName}"
                )
            }

            is UserIntent.MultiStepTask -> {
                LocalPlanResult.MultiStepPlan(
                    targetApp = intent.targetApp,
                    stepAction = intent.stepAction,
                    targetElementText = intent.targetElementText,
                    rawRequest = intent.rawRequest
                )
            }

            is UserIntent.ActionableReasoning -> {
                LocalPlanResult.NeedsAiBridge(
                    prompt = intent.prompt,
                    targetApp = intent.preferredAiApp,
                    isDirectQuery = false,
                    isGeneralQuestion = false
                )
            }

            is UserIntent.GeneralQuestion -> {
                LocalPlanResult.NeedsAiBridge(
                    prompt = intent.question,
                    targetApp = intent.preferredAiApp,
                    isDirectQuery = true,
                    isGeneralQuestion = true
                )
            }
        }
    }

    fun extractTaskCategory(input: String): String {
        return when (val intent = intentClassifier.classify(input.trim())) {
            is UserIntent.SensitiveAction -> intent.actionType
            is UserIntent.MultiStepTask -> "MULTI_STEP_TASK"
            is UserIntent.AppAction -> "APP_LAUNCH"
            is UserIntent.DeviceQuery -> "DEVICE_INSPECTION"
            is UserIntent.ActionableReasoning -> "ACTIONABLE_REASONING"
            is UserIntent.GeneralQuestion -> "GENERAL_QUESTION"
            is UserIntent.Ambiguous -> "AMBIGUOUS_REQUEST"
            is UserIntent.UserConfirmation -> "USER_CONFIRMATION"
        }
    }

    fun extractIntendedOutcome(input: String): String {
        return when (val intent = intentClassifier.classify(input.trim())) {
            is UserIntent.SensitiveAction -> "SEND_WHATSAPP_MESSAGE"
            is UserIntent.MultiStepTask -> "EXECUTE_MULTI_STEP_${intent.targetApp?.uppercase() ?: "AUTOMATION"}"
            is UserIntent.AppAction -> "LAUNCH_${intent.appName.uppercase()}"
            is UserIntent.DeviceQuery -> intent.toolName.uppercase()
            is UserIntent.ActionableReasoning -> "REASON_AND_EXECUTE"
            is UserIntent.GeneralQuestion -> "ANSWER_QUESTION"
            is UserIntent.Ambiguous -> "REQUEST_CLARIFICATION"
            is UserIntent.UserConfirmation -> "PROCESS_CONFIRMATION"
        }
    }

    fun extractGeneralizedInitialState(input: String, currentPackage: String?): String {
        val intent = intentClassifier.classify(input.trim())
        return when (intent) {
            is UserIntent.SensitiveAction -> "pkg:com.whatsapp|screen:CHAT_LIST"
            is UserIntent.MultiStepTask -> "pkg:${intent.targetApp?.lowercase() ?: "app"}|screen:INITIAL"
            is UserIntent.AppAction -> "pkg:${intent.appName.lowercase()}|screen:LAUNCH"
            is UserIntent.DeviceQuery -> "pkg:system|device:${intent.toolName}"
            is UserIntent.ActionableReasoning -> "pkg:omni|action:reasoning"
            is UserIntent.GeneralQuestion -> "pkg:omni|query:question"
            else -> if (currentPackage != null) "pkg:$currentPackage" else "pkg:unknown"
        }
    }
}
