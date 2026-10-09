package com.omni.assistant

import android.app.Application
import com.omni.assistant.ai.AiProvider
import com.omni.assistant.ai.LocalCommandProcessor
import com.omni.assistant.ai.config.AiConfigRepository
import com.omni.assistant.ai.config.AiConfigRepositoryImpl
import com.omni.assistant.ai.gemini.GeminiAiProvider
import com.omni.assistant.ai.orchestrator.AgentOrchestrator
import com.omni.assistant.core.logging.OmniLogger
import com.omni.assistant.data.local.database.OmniDatabase
import com.omni.assistant.data.repository.ChatRepositoryImpl
import com.omni.assistant.data.repository.SettingsRepositoryImpl
import com.omni.assistant.data.repository.TaskRepositoryImpl
import com.omni.assistant.device.AppInventoryProvider
import com.omni.assistant.device.BatteryInfoProvider
import com.omni.assistant.device.DeviceInfoProvider
import com.omni.assistant.device.StorageInfoProvider
import com.omni.assistant.domain.repository.ChatRepository
import com.omni.assistant.domain.repository.SettingsRepository
import com.omni.assistant.domain.repository.TaskRepository
import com.omni.assistant.domain.usecase.GetBatteryInfoUseCase
import com.omni.assistant.domain.usecase.GetChatMessagesUseCase
import com.omni.assistant.domain.usecase.GetDeviceInfoUseCase
import com.omni.assistant.domain.usecase.GetStorageInfoUseCase
import com.omni.assistant.domain.usecase.GetTasksUseCase
import com.omni.assistant.domain.usecase.ProcessCommandUseCase
import com.omni.assistant.execution.CommandDispatcher
import com.omni.assistant.execution.ToolRegistry
import com.omni.assistant.execution.tools.GetAppVersionTool
import com.omni.assistant.execution.tools.GetBatteryInfoTool
import com.omni.assistant.execution.tools.GetDeviceInfoTool
import com.omni.assistant.execution.tools.GetStorageInfoTool
import com.omni.assistant.security.ActionPolicy
import com.omni.assistant.security.StandardActionPolicy

class OmniAppContainer(private val application: Application) {
    val database: OmniDatabase by lazy {
        OmniDatabase.getInstance(application)
    }

    val chatRepository: ChatRepository by lazy {
        ChatRepositoryImpl(database.chatMessageDao())
    }

    val taskRepository: TaskRepository by lazy {
        TaskRepositoryImpl(database.taskDao())
    }

    val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(application)
    }

    val aiConfigRepository: AiConfigRepository by lazy {
        AiConfigRepositoryImpl(application)
    }

    val deviceInfoProvider: DeviceInfoProvider by lazy {
        DeviceInfoProvider(application)
    }

    val storageInfoProvider: StorageInfoProvider by lazy {
        StorageInfoProvider()
    }

    val batteryInfoProvider: BatteryInfoProvider by lazy {
        BatteryInfoProvider(application)
    }

    val appInventoryProvider: AppInventoryProvider by lazy {
        AppInventoryProvider(application)
    }

    val toolRegistry: ToolRegistry by lazy {
        ToolRegistry().apply {
            register(GetDeviceInfoTool(deviceInfoProvider))
            register(GetStorageInfoTool(storageInfoProvider))
            register(GetBatteryInfoTool(batteryInfoProvider))
            register(GetAppVersionTool(deviceInfoProvider))
        }
    }

    val actionPolicy: ActionPolicy by lazy {
        StandardActionPolicy()
    }

    val commandDispatcher: CommandDispatcher by lazy {
        CommandDispatcher(toolRegistry, actionPolicy)
    }

    val localCommandProcessor: LocalCommandProcessor by lazy {
        LocalCommandProcessor()
    }

    val cloudAiProvider: AiProvider by lazy {
        GeminiAiProvider(aiConfigRepository)
    }

    val agentOrchestrator: AgentOrchestrator by lazy {
        AgentOrchestrator(
            cloudAiProvider = cloudAiProvider,
            localCommandProcessor = localCommandProcessor,
            aiConfigRepository = aiConfigRepository,
            toolRegistry = toolRegistry,
            actionPolicy = actionPolicy,
            commandDispatcher = commandDispatcher,
            chatRepository = chatRepository,
            taskRepository = taskRepository
        )
    }

    val processCommandUseCase: ProcessCommandUseCase by lazy {
        ProcessCommandUseCase(agentOrchestrator)
    }

    val getDeviceInfoUseCase: GetDeviceInfoUseCase by lazy {
        GetDeviceInfoUseCase(deviceInfoProvider)
    }

    val getStorageInfoUseCase: GetStorageInfoUseCase by lazy {
        GetStorageInfoUseCase(storageInfoProvider)
    }

    val getBatteryInfoUseCase: GetBatteryInfoUseCase by lazy {
        GetBatteryInfoUseCase(batteryInfoProvider)
    }

    val getChatMessagesUseCase: GetChatMessagesUseCase by lazy {
        GetChatMessagesUseCase(chatRepository)
    }

    val getTasksUseCase: GetTasksUseCase by lazy {
        GetTasksUseCase(taskRepository)
    }
}

class OmniApplication : Application() {
    lateinit var container: OmniAppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        OmniLogger.i("OmniApp", "Initializing Omni Application (Phase 2 - Agent Intelligence)")
        container = OmniAppContainer(this)
    }
}
