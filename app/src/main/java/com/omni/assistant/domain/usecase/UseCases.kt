package com.omni.assistant.domain.usecase

import com.omni.assistant.ai.orchestrator.AgentExecutionResult
import com.omni.assistant.ai.orchestrator.AgentOrchestrator
import com.omni.assistant.device.BatteryInfoProvider
import com.omni.assistant.device.DeviceInfoProvider
import com.omni.assistant.device.StorageInfoProvider
import com.omni.assistant.domain.model.BatteryStatus
import com.omni.assistant.domain.model.ChatMessage
import com.omni.assistant.domain.model.DeviceStatus
import com.omni.assistant.domain.model.StorageStatus
import com.omni.assistant.domain.model.TaskItem
import com.omni.assistant.domain.repository.ChatRepository
import com.omni.assistant.domain.repository.TaskRepository
import kotlinx.coroutines.flow.Flow

class ProcessCommandUseCase(
    private val agentOrchestrator: AgentOrchestrator
) {
    suspend operator fun invoke(
        rawInput: String,
        onProgress: ((statusMessage: String) -> Unit)? = null
    ): ProcessCommandResult {
        return when (val result = agentOrchestrator.execute(rawInput, onProgress)) {
            is AgentExecutionResult.Success -> {
                ProcessCommandResult.Executed(
                    taskId = result.taskId,
                    userMessage = result.userMessage,
                    assistantMessage = result.assistantMessage,
                    isSuccess = true,
                    executedTools = result.executedTools
                )
            }
            is AgentExecutionResult.Failure -> {
                ProcessCommandResult.Executed(
                    taskId = result.taskId,
                    userMessage = result.userMessage,
                    assistantMessage = result.assistantMessage,
                    isSuccess = false,
                    executedTools = emptyList()
                )
            }
            is AgentExecutionResult.Cancelled -> {
                ProcessCommandResult.Empty
            }
            is AgentExecutionResult.Empty -> {
                ProcessCommandResult.Empty
            }
        }
    }
}

sealed interface ProcessCommandResult {
    data object Empty : ProcessCommandResult
    data class Executed(
        val taskId: String,
        val userMessage: ChatMessage,
        val assistantMessage: ChatMessage,
        val isSuccess: Boolean,
        val executedTools: List<String> = emptyList()
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
