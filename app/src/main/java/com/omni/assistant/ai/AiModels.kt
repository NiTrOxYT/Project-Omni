package com.omni.assistant.ai

enum class AiRole {
    USER,
    MODEL,
    SYSTEM,
    TOOL
}

data class AiToolProperty(
    val type: String = "string",
    val description: String = "",
    val enumValues: List<String>? = null
)

data class AiToolSchema(
    val name: String,
    val description: String,
    val properties: Map<String, AiToolProperty> = emptyMap(),
    val required: List<String> = emptyList()
)

data class AiToolCall(
    val id: String = "",
    val name: String,
    val arguments: Map<String, Any?> = emptyMap(),
    val thoughtSignature: String? = null,
    val rawPartJson: String? = null
)

data class AiToolResult(
    val toolCallId: String = "",
    val toolName: String,
    val content: Map<String, Any?> = emptyMap()
)

data class AiMessage(
    val role: AiRole,
    val text: String? = null,
    val toolCalls: List<AiToolCall> = emptyList(),
    val toolResults: List<AiToolResult> = emptyList(),
    val rawContentJson: String? = null
)

data class AiRequest(
    val prompt: String,
    val conversationHistory: List<AiMessage> = emptyList(),
    val tools: List<AiToolSchema> = emptyList(),
    val systemInstruction: String? = null,
    val context: Map<String, Any?> = emptyMap()
)

data class AiResponse(
    val isHandled: Boolean = true,
    val replyText: String? = null,
    val toolCalls: List<AiToolCall> = emptyList(),
    val matchedToolName: String? = null,
    val toolParameters: Map<String, Any?> = emptyMap(),
    val isSupportedCommand: Boolean = true,
    val rawJson: String? = null,
    val rawContentJson: String? = null
)

interface AiProvider {
    suspend fun generateResponse(request: AiRequest): AiResponse
    
    // Backward compatibility with Phase 1 simple process()
    suspend fun process(request: AiRequest): AiResponse {
        return generateResponse(request)
    }
}
