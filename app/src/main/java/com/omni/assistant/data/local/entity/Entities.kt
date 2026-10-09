package com.omni.assistant.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.omni.assistant.domain.model.ChatMessage
import com.omni.assistant.domain.model.MessageSender
import com.omni.assistant.domain.model.TaskItem
import com.omni.assistant.domain.model.TaskStatus

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey
    val id: String,
    val sender: String,
    val content: String,
    val timestamp: Long,
    val taskId: String? = null,
    val structuredDataJson: String? = null,
    val isError: Boolean = false
) {
    fun toDomain(): ChatMessage {
        val messageSender = try {
            MessageSender.valueOf(sender)
        } catch (e: Exception) {
            MessageSender.SYSTEM
        }
        return ChatMessage(
            id = id,
            sender = messageSender,
            content = content,
            timestamp = timestamp,
            taskId = taskId,
            structuredDataJson = structuredDataJson,
            isError = isError
        )
    }

    companion object {
        fun fromDomain(domain: ChatMessage): ChatMessageEntity {
            return ChatMessageEntity(
                id = domain.id,
                sender = domain.sender.name,
                content = domain.content,
                timestamp = domain.timestamp,
                taskId = domain.taskId,
                structuredDataJson = domain.structuredDataJson,
                isError = domain.isError
            )
        }
    }
}

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey
    val id: String,
    val request: String,
    val status: String,
    val createdAt: Long,
    val completedAt: Long? = null,
    val resultSummary: String? = null,
    val toolName: String? = null,
    val errorDetails: String? = null
) {
    fun toDomain(): TaskItem {
        val taskStatus = try {
            TaskStatus.valueOf(status)
        } catch (e: Exception) {
            TaskStatus.FAILED
        }
        return TaskItem(
            id = id,
            request = request,
            status = taskStatus,
            createdAt = createdAt,
            completedAt = completedAt,
            resultSummary = resultSummary,
            toolName = toolName,
            errorDetails = errorDetails
        )
    }

    companion object {
        fun fromDomain(domain: TaskItem): TaskEntity {
            return TaskEntity(
                id = domain.id,
                request = domain.request,
                status = domain.status.name,
                createdAt = domain.createdAt,
                completedAt = domain.completedAt,
                resultSummary = domain.resultSummary,
                toolName = domain.toolName,
                errorDetails = domain.errorDetails
            )
        }
    }
}
