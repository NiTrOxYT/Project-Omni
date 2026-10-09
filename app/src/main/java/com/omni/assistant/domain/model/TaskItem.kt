package com.omni.assistant.domain.model

data class TaskItem(
    val id: String,
    val request: String,
    val status: TaskStatus,
    val createdAt: Long,
    val completedAt: Long? = null,
    val resultSummary: String? = null,
    val toolName: String? = null,
    val errorDetails: String? = null
) {
    val durationMillis: Long?
        get() = completedAt?.let { it - createdAt }
}
