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
import com.omni.assistant.execution.tools.EnterTextTool
import com.omni.assistant.execution.tools.GetAccessibilityStatusTool
import com.omni.assistant.execution.tools.GetAppVersionTool
import com.omni.assistant.execution.tools.GetBatteryInfoTool
import com.omni.assistant.execution.tools.GetDeviceInfoTool
import com.omni.assistant.execution.tools.GetStorageInfoTool
import com.omni.assistant.execution.tools.GetUiSnapshotTool
import com.omni.assistant.execution.tools.LaunchAppTool
import com.omni.assistant.execution.tools.NavigateBackTool
import com.omni.assistant.execution.tools.ScrollUiTool
import com.omni.assistant.execution.tools.TapElementTool
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
            register(GetAccessibilityStatusTool(application))
            register(GetUiSnapshotTool())
            register(LaunchAppTool(application, appInventoryProvider))
            register(TapElementTool())
            register(EnterTextTool())
            register(ScrollUiTool())
            register(NavigateBackTool())
        }
    }

    val actionPolicy: ActionPolicy by lazy {
        StandardActionPolicy()
    }

    val commandDispatcher: CommandDispatcher by lazy {
        CommandDispatcher(toolRegistry, actionPolicy)
    }

    val aiAppBridge: com.omni.assistant.ai.bridge.AiAppBridge by lazy {
        com.omni.assistant.ai.bridge.AiAppBridge(application)
    }

    val localTaskPlanner: com.omni.assistant.ai.LocalTaskPlanner by lazy {
        com.omni.assistant.ai.LocalTaskPlanner(
            toolRegistry = toolRegistry,
            appInventoryProvider = appInventoryProvider,
            aiAppBridge = aiAppBridge
        )
    }

    val cloudAiProvider: AiProvider by lazy {
        GeminiAiProvider(aiConfigRepository)
    }

    val agentOrchestrator: AgentOrchestrator by lazy {
        AgentOrchestrator(
            cloudAiProvider = cloudAiProvider,
            localTaskPlanner = localTaskPlanner,
            aiAppBridge = aiAppBridge,
            settingsRepository = settingsRepository,
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
