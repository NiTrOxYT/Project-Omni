package com.omni.assistant.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.omni.assistant.domain.model.ExperienceOutcome
import com.omni.assistant.domain.model.ExperienceRecord

@Entity(tableName = "experience_records")
data class ExperienceRecordEntity(
    @PrimaryKey
    val id: String,
    val taskCategory: String,
    val generalizedInitialState: String,
    val intendedOutcome: String,
    val actionsAttemptedJoined: String,
    val stateTransitionsJoined: String,
    val successfulActionsJoined: String,
    val failedActionsJoined: String,
    val recoveryStrategy: String?,
    val finalOutcome: String,
    val failureCategory: String?,
    val executionDurationMs: Long,
    val successCount: Int,
    val failureCount: Int,
    val timestamp: Long
) {
    fun toDomain(): ExperienceRecord {
        val outcome = try {
            ExperienceOutcome.valueOf(finalOutcome)
        } catch (_: Exception) {
            ExperienceOutcome.FAILURE
        }

        return ExperienceRecord(
            id = id,
            taskCategory = taskCategory,
            generalizedInitialState = generalizedInitialState,
            intendedOutcome = intendedOutcome,
            actionsAttempted = decodeList(actionsAttemptedJoined),
            stateTransitions = decodeList(stateTransitionsJoined),
            successfulActions = decodeList(successfulActionsJoined),
            failedActions = decodeList(failedActionsJoined),
            recoveryStrategy = recoveryStrategy,
            finalOutcome = outcome,
            failureCategory = failureCategory,
            executionDurationMs = executionDurationMs,
            successCount = successCount,
            failureCount = failureCount,
            timestamp = timestamp
        )
    }

    companion object {
        fun fromDomain(domain: ExperienceRecord): ExperienceRecordEntity {
            return ExperienceRecordEntity(
                id = domain.id,
                taskCategory = domain.taskCategory,
                generalizedInitialState = domain.generalizedInitialState,
                intendedOutcome = domain.intendedOutcome,
                actionsAttemptedJoined = encodeList(domain.actionsAttempted),
                stateTransitionsJoined = encodeList(domain.stateTransitions),
                successfulActionsJoined = encodeList(domain.successfulActions),
                failedActionsJoined = encodeList(domain.failedActions),
                recoveryStrategy = domain.recoveryStrategy,
                finalOutcome = domain.finalOutcome.name,
                failureCategory = domain.failureCategory,
                executionDurationMs = domain.executionDurationMs,
                successCount = domain.successCount,
                failureCount = domain.failureCount,
                timestamp = domain.timestamp
            )
        }

        private fun encodeList(list: List<String>): String = list.joinToString("|||")
        private fun decodeList(joined: String): List<String> =
            if (joined.isBlank()) emptyList() else joined.split("|||")
    }
}
