package com.omni.assistant.domain.repository

import com.omni.assistant.domain.model.ExperienceRecord
import kotlinx.coroutines.flow.Flow

interface ExperienceRepository {
    suspend fun recordExperience(experience: ExperienceRecord)
    suspend fun recallStrategy(taskCategory: String, initialState: String): ExperienceRecord?
    suspend fun recallCategoryStrategies(taskCategory: String): List<ExperienceRecord>
    fun getAllExperiences(): Flow<List<ExperienceRecord>>
    suspend fun clearAllExperiences()
}
