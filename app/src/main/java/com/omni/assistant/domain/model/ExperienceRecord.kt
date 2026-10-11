package com.omni.assistant.domain.model

data class ExperienceRecord(
    val id: String,
    val taskCategory: String,
    val generalizedInitialState: String,
    val intendedOutcome: String,
    val actionsAttempted: List<String>,
    val stateTransitions: List<String>,
    val successfulActions: List<String>,
    val failedActions: List<String> = emptyList(),
    val recoveryStrategy: String? = null,
    val finalOutcome: ExperienceOutcome,
    val failureCategory: String? = null,
    val executionDurationMs: Long,
    val successCount: Int = if (finalOutcome == ExperienceOutcome.SUCCESS) 1 else 0,
    val failureCount: Int = if (finalOutcome == ExperienceOutcome.FAILURE) 1 else 0,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ExperienceOutcome {
    SUCCESS,
    FAILURE,
    RECOVERED,
    CANCELLED
}
