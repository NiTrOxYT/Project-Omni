package com.omni.assistant.data.repository

import com.omni.assistant.data.local.dao.TaskDao
import com.omni.assistant.data.local.entity.TaskEntity
import com.omni.assistant.domain.model.TaskItem
import com.omni.assistant.domain.model.TaskStatus
import com.omni.assistant.domain.repository.TaskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class TaskRepositoryImpl(
    private val taskDao: TaskDao
) : TaskRepository {

    override fun getTasks(): Flow<List<TaskItem>> {
        return taskDao.getAllTasks().map { list ->
            list.map { it.toDomain() }
        }
    }

    override fun getRecentTasks(limit: Int): Flow<List<TaskItem>> {
        return taskDao.getRecentTasks(limit).map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun getTaskById(taskId: String): TaskItem? {
        return taskDao.getTaskById(taskId)?.toDomain()
    }

    override suspend fun insertTask(task: TaskItem) {
        taskDao.insertTask(TaskEntity.fromDomain(task))
    }

    override suspend fun updateTaskStatus(
        taskId: String,
        status: TaskStatus,
        completedAt: Long?,
        resultSummary: String?,
        errorDetails: String?
    ) {
        taskDao.updateStatus(
            id = taskId,
            status = status.name,
            completedAt = completedAt,
            resultSummary = resultSummary,
            errorDetails = errorDetails
        )
    }

    override suspend fun clearTasks() {
        taskDao.clearAll()
    }
}
