package com.omni.assistant.ai

data class AiRequest(
    val prompt: String,
    val context: Map<String, Any?> = emptyMap()
)

data class AiResponse(
    val isHandled: Boolean,
    val matchedToolName: String? = null,
    val toolParameters: Map<String, Any?> = emptyMap(),
    val replyText: String,
    val isSupportedCommand: Boolean
)

interface AiProvider {
    suspend fun process(request: AiRequest): AiResponse
}
