package com.omni.assistant.data.repository

import com.omni.assistant.core.logging.OmniLogger
import com.omni.assistant.data.local.dao.ExperienceDao
import com.omni.assistant.data.local.entity.ExperienceRecordEntity
import com.omni.assistant.domain.model.ExperienceOutcome
import com.omni.assistant.domain.model.ExperienceRecord
import com.omni.assistant.domain.repository.ExperienceRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ExperienceRepositoryImpl(
    private val experienceDao: ExperienceDao
) : ExperienceRepository {

    private val tag = "ExperienceRepo"

    override suspend fun recordExperience(experience: ExperienceRecord) {
        val sanitized = sanitizeExperience(experience)
        val existing = experienceDao.findBestStrategy(sanitized.taskCategory, sanitized.generalizedInitialState)

        if (existing != null && existing.actionsAttemptedJoined == ExperienceRecordEntity.fromDomain(sanitized).actionsAttemptedJoined) {
            val updated = existing.copy(
                successCount = if (sanitized.finalOutcome == ExperienceOutcome.SUCCESS) existing.successCount + 1 else existing.successCount,
                failureCount = if (sanitized.finalOutcome == ExperienceOutcome.FAILURE) existing.failureCount + 1 else existing.failureCount,
                timestamp = System.currentTimeMillis()
            )
            experienceDao.updateExperience(updated)
            OmniLogger.d(tag, "Updated existing strategy count for ${sanitized.taskCategory}: success=${updated.successCount}, fail=${updated.failureCount}")
        } else {
            val initialSuccess = if (sanitized.finalOutcome == ExperienceOutcome.SUCCESS) maxOf(1, sanitized.successCount) else 0
            val initialFailure = if (sanitized.finalOutcome == ExperienceOutcome.FAILURE) maxOf(1, sanitized.failureCount) else 0
            val entityToSave = ExperienceRecordEntity.fromDomain(
                sanitized.copy(
                    successCount = initialSuccess,
                    failureCount = initialFailure
                )
            )
            experienceDao.insertExperience(entityToSave)
            OmniLogger.d(tag, "Saved new experience record: ${sanitized.taskCategory} -> ${sanitized.finalOutcome} (success=$initialSuccess, fail=$initialFailure)")
        }
    }

    override suspend fun recallStrategy(taskCategory: String, initialState: String): ExperienceRecord? {
        val entity = experienceDao.findBestStrategy(taskCategory, initialState) ?: return null
        val domain = entity.toDomain()
        // Only return strategies with positive verified success record
        return if (domain.successCount > 0 && domain.successCount >= domain.failureCount) domain else null
    }

    override suspend fun recallCategoryStrategies(taskCategory: String): List<ExperienceRecord> {
        return experienceDao.findCategoryStrategies(taskCategory)
            .map { it.toDomain() }
            .filter { it.successCount > 0 && it.successCount >= it.failureCount }
    }

    override fun getAllExperiences(): Flow<List<ExperienceRecord>> {
        return experienceDao.getAllExperiences().map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun clearAllExperiences() {
        experienceDao.clearAllExperiences()
        OmniLogger.i(tag, "All experience records cleared.")
    }

    private fun sanitizeExperience(record: ExperienceRecord): ExperienceRecord {
        // Redact any phone numbers, email addresses, or personal messages
        val phoneRegex = Regex("\\+?\\d{10,13}")
        val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")

        fun clean(str: String): String {
            return str
                .replace(phoneRegex, "[PHONE]")
                .replace(emailRegex, "[EMAIL]")
        }

        return record.copy(
            generalizedInitialState = clean(record.generalizedInitialState),
            intendedOutcome = clean(record.intendedOutcome),
            actionsAttempted = record.actionsAttempted.map { clean(it) },
            stateTransitions = record.stateTransitions.map { clean(it) },
            successfulActions = record.successfulActions.map { clean(it) },
            failedActions = record.failedActions.map { clean(it) }
        )
    }
}
