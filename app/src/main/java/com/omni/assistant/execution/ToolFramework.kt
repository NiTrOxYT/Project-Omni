package com.omni.assistant.execution

import com.omni.assistant.ai.AiToolProperty
import com.omni.assistant.ai.AiToolSchema
import com.omni.assistant.domain.model.RiskLevel

data class ToolParameter(
    val name: String,
    val type: String = "string",
    val description: String = "",
    val isRequired: Boolean = false,
    val enumValues: List<String>? = null
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val requiredPermissions: List<String> = emptyList(),
    val parameterNames: List<String> = emptyList(),
    val parameters: List<ToolParameter> = emptyList()
) {
    fun toAiSchema(): AiToolSchema {
        val properties = parameters.associate { param ->
            param.name to AiToolProperty(
                type = param.type,
                description = param.description,
                enumValues = param.enumValues
            )
        }
        val required = parameters.filter { it.isRequired }.map { it.name }
        return AiToolSchema(
            name = name,
            description = description,
            properties = properties,
            required = required
        )
    }
}

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
