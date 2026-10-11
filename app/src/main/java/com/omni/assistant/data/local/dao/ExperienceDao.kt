package com.omni.assistant.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.omni.assistant.data.local.entity.ExperienceRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ExperienceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExperience(experience: ExperienceRecordEntity)

    @Update
    suspend fun updateExperience(experience: ExperienceRecordEntity)

    @Query("SELECT * FROM experience_records WHERE taskCategory = :taskCategory AND generalizedInitialState = :initialState ORDER BY successCount DESC, timestamp DESC LIMIT 1")
    suspend fun findBestStrategy(taskCategory: String, initialState: String): ExperienceRecordEntity?

    @Query("SELECT * FROM experience_records WHERE taskCategory = :taskCategory ORDER BY successCount DESC, timestamp DESC LIMIT 3")
    suspend fun findCategoryStrategies(taskCategory: String): List<ExperienceRecordEntity>

    @Query("SELECT * FROM experience_records ORDER BY timestamp DESC")
    fun getAllExperiences(): Flow<List<ExperienceRecordEntity>>

    @Query("DELETE FROM experience_records")
    suspend fun clearAllExperiences()

    @Query("SELECT COUNT(*) FROM experience_records")
    suspend fun getCount(): Int
}
