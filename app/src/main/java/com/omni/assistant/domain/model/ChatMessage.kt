package com.omni.assistant.domain.model

enum class MessageSender {
    USER,
    ASSISTANT,
    SYSTEM
}

data class ChatMessage(
    val id: String,
    val sender: MessageSender,
    val content: String,
    val timestamp: Long,
    val taskId: String? = null,
    val structuredDataJson: String? = null,
    val isError: Boolean = false
)
