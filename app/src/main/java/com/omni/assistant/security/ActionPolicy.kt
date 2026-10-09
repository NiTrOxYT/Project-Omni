package com.omni.assistant.security

import com.omni.assistant.domain.model.RiskLevel
import com.omni.assistant.execution.ToolDefinition

sealed interface PolicyDecision {
    data object Allow : PolicyDecision
    data class Deny(val reason: String) : PolicyDecision
    data class RequiresConfirmation(val warning: String) : PolicyDecision
}

interface ActionPolicy {
    fun evaluate(tool: ToolDefinition, parameters: Map<String, Any?> = emptyMap()): PolicyDecision
}

class StandardActionPolicy : ActionPolicy {
    override fun evaluate(tool: ToolDefinition, parameters: Map<String, Any?>): PolicyDecision {
        return when (tool.riskLevel) {
            RiskLevel.LOW -> PolicyDecision.Allow
            RiskLevel.MEDIUM -> PolicyDecision.Allow
            RiskLevel.HIGH -> PolicyDecision.RequiresConfirmation("This action modifies system state and requires confirmation.")
            RiskLevel.CRITICAL -> PolicyDecision.Deny("Critical risk actions are disabled in this phase.")
        }
    }
}
