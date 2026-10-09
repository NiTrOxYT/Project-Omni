package com.omni.assistant.domain.repository

import com.omni.assistant.domain.model.TaskItem
import com.omni.assistant.domain.model.TaskStatus
import kotlinx.coroutines.flow.Flow

interface TaskRepository {
    fun getTasks(): Flow<List<TaskItem>>
    fun getRecentTasks(limit: Int): Flow<List<TaskItem>>
    suspend fun getTaskById(taskId: String): TaskItem?
    suspend fun insertTask(task: TaskItem)
    suspend fun updateTaskStatus(
        taskId: String,
        status: TaskStatus,
        completedAt: Long? = null,
        resultSummary: String? = null,
        errorDetails: String? = null
    )
    suspend fun clearTasks()
}
