package com.omni.assistant.domain.usecase

import com.omni.assistant.ai.AiProvider
import com.omni.assistant.ai.AiRequest
import com.omni.assistant.core.logging.OmniLogger
import com.omni.assistant.device.BatteryInfoProvider
import com.omni.assistant.device.DeviceInfoProvider
import com.omni.assistant.device.StorageInfoProvider
import com.omni.assistant.domain.model.BatteryStatus
import com.omni.assistant.domain.model.ChatMessage
import com.omni.assistant.domain.model.DeviceStatus
import com.omni.assistant.domain.model.MessageSender
import com.omni.assistant.domain.model.StorageStatus
import com.omni.assistant.domain.model.TaskItem
import com.omni.assistant.domain.model.TaskStatus
import com.omni.assistant.domain.repository.ChatRepository
import com.omni.assistant.domain.repository.TaskRepository
import com.omni.assistant.execution.CommandDispatcher
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class ProcessCommandUseCase(
    private val aiProvider: AiProvider,
    private val commandDispatcher: CommandDispatcher,
    private val chatRepository: ChatRepository,
    private val taskRepository: TaskRepository
) {
    private val tag = "ProcessCommandUseCase"

    suspend operator fun invoke(rawInput: String): ProcessCommandResult {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) {
            return ProcessCommandResult.Empty
        }

        val taskId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()

        // 1. Record User Message
        val userMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            sender = MessageSender.USER,
            content = trimmed,
            timestamp = timestamp,
            taskId = taskId
        )
        chatRepository.insertMessage(userMessage)

        // 2. Create Task Record (PENDING)
        val initialTask = TaskItem(
            id = taskId,
            request = trimmed,
            status = TaskStatus.PENDING,
            createdAt = timestamp
        )
        taskRepository.insertTask(initialTask)

        // 3. Update to RUNNING
        taskRepository.updateTaskStatus(
            taskId = taskId,
            status = TaskStatus.RUNNING
        )

        // 4. Process via AI / Local Command Engine
        val aiResponse = aiProvider.process(AiRequest(prompt = trimmed))

        if (!aiResponse.isHandled || aiResponse.matchedToolName == null) {
            OmniLogger.i(tag, "Command unhandled: '$trimmed'")
            val completionTime = System.currentTimeMillis()

            // Update Task as CANCELLED/UNSUPPORTED
            taskRepository.updateTaskStatus(
                taskId = taskId,
                status = TaskStatus.FAILED,
                completedAt = completionTime,
                resultSummary = "Unsupported command in Phase 1",
                errorDetails = "Command not mapped to available local tools."
            )

            // Record Assistant Message with honest message
            val assistantMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.ASSISTANT,
                content = aiResponse.replyText,
                timestamp = completionTime,
                taskId = taskId,
                isError = false
            )
            chatRepository.insertMessage(assistantMessage)

            return ProcessCommandResult.Executed(
                taskId = taskId,
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                isSuccess = false
            )
        }

        // 5. Dispatch the matched tool
        val toolName = aiResponse.matchedToolName
        val toolResult = commandDispatcher.dispatch(toolName, aiResponse.toolParameters)
        val completionTime = System.currentTimeMillis()

        val finalStatus = if (toolResult.success) TaskStatus.SUCCEEDED else TaskStatus.FAILED

        taskRepository.updateTaskStatus(
            taskId = taskId,
            status = finalStatus,
            completedAt = completionTime,
            resultSummary = toolResult.summaryText,
            errorDetails = toolResult.error
        )

        val assistantMessage = ChatMessage(
            id = UUID.randomUUID().toString(),
            sender = MessageSender.ASSISTANT,
            content = toolResult.summaryText,
            timestamp = completionTime,
            taskId = taskId,
            isError = !toolResult.success
        )
        chatRepository.insertMessage(assistantMessage)

        return ProcessCommandResult.Executed(
            taskId = taskId,
            userMessage = userMessage,
            assistantMessage = assistantMessage,
            isSuccess = toolResult.success
        )
    }
}

sealed interface ProcessCommandResult {
    data object Empty : ProcessCommandResult
    data class Executed(
        val taskId: String,
        val userMessage: ChatMessage,
        val assistantMessage: ChatMessage,
        val isSuccess: Boolean
    ) : ProcessCommandResult
}

class GetDeviceInfoUseCase(
    private val deviceInfoProvider: DeviceInfoProvider
) {
    operator fun invoke(): DeviceStatus = deviceInfoProvider.getDeviceInfo()
}

class GetStorageInfoUseCase(
    private val storageInfoProvider: StorageInfoProvider
) {
    operator fun invoke(): StorageStatus = storageInfoProvider.getPrimaryStorageInfo()
}

class GetBatteryInfoUseCase(
    private val batteryInfoProvider: BatteryInfoProvider
) {
    operator fun invoke(): BatteryStatus = batteryInfoProvider.getBatteryInfo()
}

class GetChatMessagesUseCase(
    private val chatRepository: ChatRepository
) {
    operator fun invoke(): Flow<List<ChatMessage>> = chatRepository.getMessages()
}

class GetTasksUseCase(
    private val taskRepository: TaskRepository
) {
    operator fun invoke(): Flow<List<TaskItem>> = taskRepository.getTasks()
    fun getRecent(limit: Int): Flow<List<TaskItem>> = taskRepository.getRecentTasks(limit)
}
