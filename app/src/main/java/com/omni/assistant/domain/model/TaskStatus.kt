package com.omni.assistant.domain.model

enum class TaskStatus {
    PENDING,
    RUNNING,
    AWAITING_CONFIRMATION,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
