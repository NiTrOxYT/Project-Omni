package com.omni.assistant.execution

import com.omni.assistant.core.logging.OmniLogger
import com.omni.assistant.security.ActionPolicy
import com.omni.assistant.security.PolicyDecision

class CommandDispatcher(
    private val toolRegistry: ToolRegistry,
    private val actionPolicy: ActionPolicy
) {
    private val tag = "CommandDispatcher"

    suspend fun dispatch(
        toolName: String,
        parameters: Map<String, Any?> = emptyMap()
    ): ToolResult {
        val tool = toolRegistry.getTool(toolName)
        if (tool == null) {
            OmniLogger.w(tag, "Dispatch failed: Tool '$toolName' not found")
            return ToolResult(
                success = false,
                tool = toolName,
                summaryText = "Tool '$toolName' is not registered.",
                error = "ToolNotFound"
            )
        }

        val policyDecision = actionPolicy.evaluate(tool.definition, parameters)
        when (policyDecision) {
            is PolicyDecision.Deny -> {
                OmniLogger.w(tag, "Tool execution denied by policy: ${policyDecision.reason}")
                return ToolResult(
                    success = false,
                    tool = toolName,
                    summaryText = "Execution denied by policy: ${policyDecision.reason}",
                    error = "PolicyDenial"
                )
            }
            is PolicyDecision.RequiresConfirmation -> {
                OmniLogger.i(tag, "Tool requires confirmation: ${policyDecision.warning}")
                // In Phase 1 we pass through or handle as configured
            }
            is PolicyDecision.Allow -> {
                // Proceed
            }
        }

        return try {
            OmniLogger.i(tag, "Executing tool: $toolName")
            tool.execute(parameters)
        } catch (t: Throwable) {
            OmniLogger.e(tag, "Unhandled exception during tool execution: $toolName", t)
            ToolResult(
                success = false,
                tool = toolName,
                summaryText = "Execution failed with unexpected error: ${t.message}",
                error = t.localizedMessage ?: "UnexpectedException"
            )
        }
    }
}
