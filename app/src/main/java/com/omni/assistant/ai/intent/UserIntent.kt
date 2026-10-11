package com.omni.assistant.ai.intent

import com.omni.assistant.ai.bridge.SupportedAiApp

sealed interface UserIntent {

    /**
     * General knowledge, explanations, open-ended questions.
     * Answered using ChatGPT UI fallback directly when local answer is not available.
     * Response is extracted and returned to chatbox.
     */
    data class GeneralQuestion(
        val question: String,
        val preferredAiApp: SupportedAiApp = SupportedAiApp.AUTO,
        val localAnswer: String? = null
    ) : UserIntent

    /**
     * Novel multi-step or complex actionable request requiring on-device screen reasoning.
     * The AI bridge is consulted to propose validated tool actions on observed screen state.
     */
    data class ActionableReasoning(
        val prompt: String,
        val preferredAiApp: SupportedAiApp = SupportedAiApp.AUTO
    ) : UserIntent

    /**
     * Local device state queries (battery, storage, specs, connectivity, installed apps, accessibility status).
     * Must be answered using local Android APIs/tools, never by asking ChatGPT to guess device state.
     */
    data class DeviceQuery(
        val toolName: String,
        val arguments: Map<String, Any?> = emptyMap(),
        val description: String
    ) : UserIntent

    /**
     * Single application launch action.
     * Uses local AppInventory and launch_app tool. Never opens ChatGPT unnecessarily.
     */
    data class AppAction(
        val appName: String,
        val packageName: String? = null
    ) : UserIntent

    /**
     * Multi-step task combining application launch with screen inspection and interaction.
     * e.g. "Open Settings and find the Bluetooth option"
     */
    data class MultiStepTask(
        val targetApp: String?,
        val stepAction: String,
        val targetElementText: String?,
        val rawRequest: String
    ) : UserIntent

    /**
     * External or consequential actions (messaging, deletions, posting).
     * Must preserve confirmation gating before performing consequential operations.
     */
    data class SensitiveAction(
        val actionType: String,
        val toolName: String,
        val arguments: Map<String, Any?>,
        val description: String,
        val requiresConfirmation: Boolean = true
    ) : UserIntent

    /**
     * Ambiguous or underspecified requests requiring user clarification (e.g. "Send it to her").
     * Must prompt the user concisely rather than guessing.
     */
    data class Ambiguous(
        val clarificationPrompt: String,
        val missingFields: List<String> = emptyList()
    ) : UserIntent

    /**
     * User confirmation / cancellation response to a pending gated action.
     */
    data class UserConfirmation(
        val isConfirmed: Boolean
    ) : UserIntent
}
