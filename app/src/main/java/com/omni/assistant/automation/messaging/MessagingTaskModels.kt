package com.omni.assistant.automation.messaging

enum class MessagingState {
    RECEIVED,
    PARSED,
    RESOLVING_RECIPIENT,
    OPENING_APP,
    OPENING_CONVERSATION,
    COMPOSING_MESSAGE,
    AWAITING_CONFIRMATION,
    SENDING,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class MessagingRequest(
    val appName: String = "WhatsApp",
    val packageName: String = "com.whatsapp",
    val recipient: String,
    val messageText: String,
    val requiresConfirmation: Boolean = true
)

data class MessagingExecutionResult(
    val success: Boolean,
    val state: MessagingState,
    val recipient: String,
    val messageText: String,
    val summaryText: String,
    val error: String? = null
)
