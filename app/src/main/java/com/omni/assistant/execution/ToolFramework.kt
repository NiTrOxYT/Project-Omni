package com.omni.assistant.execution

import com.omni.assistant.domain.model.RiskLevel

data class ToolDefinition(
    val name: String,
    val description: String,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val requiredPermissions: List<String> = emptyList(),
    val parameterNames: List<String> = emptyList()
)

data class ToolResult(
    val success: Boolean,
    val tool: String,
    val data: Map<String, Any?>? = null,
    val summaryText: String,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

interface OmniTool {
    val definition: ToolDefinition
    suspend fun execute(parameters: Map<String, Any?> = emptyMap()): ToolResult
}
