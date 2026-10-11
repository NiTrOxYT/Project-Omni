package com.omni.assistant

import com.omni.assistant.ai.AiMessage
import com.omni.assistant.ai.AiProvider
import com.omni.assistant.ai.AiRequest
import com.omni.assistant.ai.AiResponse
import com.omni.assistant.ai.AiRole
import com.omni.assistant.ai.AiToolCall
import com.omni.assistant.ai.LocalTaskPlanner
import com.omni.assistant.ai.bridge.AiAppBridge
import com.omni.assistant.ai.bridge.AiBridgeQuery
import com.omni.assistant.ai.bridge.AiBridgeResponse
import com.omni.assistant.ai.bridge.InstalledAiApp
import com.omni.assistant.ai.bridge.SupportedAiApp
import com.omni.assistant.ai.config.AiConfig
import com.omni.assistant.ai.config.AiConfigRepository
import com.omni.assistant.ai.orchestrator.AgentExecutionResult
import com.omni.assistant.ai.orchestrator.AgentOrchestrator
import com.omni.assistant.device.AppInventoryProvider
import com.omni.assistant.domain.model.AppInfo
import com.omni.assistant.domain.model.ChatMessage
import com.omni.assistant.domain.model.RiskLevel
import com.omni.assistant.domain.model.TaskItem
import com.omni.assistant.domain.model.TaskStatus
import com.omni.assistant.domain.repository.AppThemeMode
import com.omni.assistant.domain.repository.ChatRepository
import com.omni.assistant.domain.repository.SettingsRepository
import com.omni.assistant.domain.repository.TaskRepository
import com.omni.assistant.execution.CommandDispatcher
import com.omni.assistant.execution.OmniTool
import com.omni.assistant.execution.ToolDefinition
import com.omni.assistant.execution.ToolRegistry
import com.omni.assistant.execution.ToolResult
import com.omni.assistant.security.StandardActionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentOrchestratorTest {

    private lateinit var toolRegistry: ToolRegistry
    private lateinit var policy: StandardActionPolicy
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var localPlanner: LocalTaskPlanner
    private lateinit var fakeBridge: AiAppBridge
    private lateinit var fakeAppInventory: AppInventoryProvider

    private val fakeChatRepo = object : ChatRepository {
        val messages = mutableListOf<ChatMessage>()
        override fun getMessages(): Flow<List<ChatMessage>> = flowOf(messages)
        override suspend fun insertMessage(message: ChatMessage) { messages.add(message) }
        override suspend fun clearHistory() { messages.clear() }
    }

    private val fakeTaskRepo = object : TaskRepository {
        val tasks = mutableListOf<TaskItem>()
        override fun getTasks(): Flow<List<TaskItem>> = flowOf(tasks)
        override fun getRecentTasks(limit: Int): Flow<List<TaskItem>> = flowOf(tasks.takeLast(limit))
        override suspend fun getTaskById(taskId: String): TaskItem? = tasks.find { it.id == taskId }
        override suspend fun insertTask(task: TaskItem) { tasks.add(task) }
        override suspend fun updateTaskStatus(
            taskId: String,
            status: TaskStatus,
            completedAt: Long?,
            resultSummary: String?,
            errorDetails: String?
        ) {
            val index = tasks.indexOfFirst { it.id == taskId }
            if (index != -1) {
                tasks[index] = tasks[index].copy(
                    status = status,
                    completedAt = completedAt,
                    resultSummary = resultSummary,
                    errorDetails = errorDetails
                )
            }
        }
        override suspend fun clearTasks() { tasks.clear() }
    }

    private val fakeSettingsRepo = object : SettingsRepository {
        var theme = AppThemeMode.DARK
        var preferredApp = SupportedAiApp.AUTO
        var autoSend = false
        override fun getThemeMode(): Flow<AppThemeMode> = flowOf(theme)
        override suspend fun setThemeMode(mode: AppThemeMode) { theme = mode }
        override fun getPreferredAiApp(): Flow<SupportedAiApp> = flowOf(preferredApp)
        override suspend fun setPreferredAiApp(app: SupportedAiApp) { preferredApp = app }
        override fun isMessagingAutoSendEnabled(): Flow<Boolean> = flowOf(autoSend)
        override suspend fun setMessagingAutoSendEnabled(enabled: Boolean) { autoSend = enabled }
    }

    private class FakeConfigRepo(var config: AiConfig) : AiConfigRepository {
        override fun getConfig() = flowOf(config)
        override fun getCurrentConfig() = config
        override suspend fun updateApiKey(apiKey: String) { config = config.copy(apiKey = apiKey) }
        override suspend fun updateModel(modelName: String) { config = config.copy(modelName = modelName) }
        override suspend fun updateUseCloudAi(useCloudAi: Boolean) { config = config.copy(useCloudAi = useCloudAi) }
    }

    @Before
    fun setUp() {
        toolRegistry = ToolRegistry()
        policy = StandardActionPolicy()
        dispatcher = CommandDispatcher(toolRegistry, policy)

        // Register dummy battery tool
        toolRegistry.register(object : OmniTool {
            override val definition = ToolDefinition(
                name = "get_battery_info",
                description = "Get battery status",
                riskLevel = RiskLevel.LOW
            )
            override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
                return ToolResult(
                    success = true,
                    tool = "get_battery_info",
                    data = mapOf("percentage" to 82, "charging" to true),
                    summaryText = "Battery: 82% (Charging)"
                )
            }
        })

        fakeAppInventory = object : AppInventoryProvider(null) {
            override fun getVisibleInstalledApplications(): List<AppInfo> = listOf(
                AppInfo(packageName = "com.android.settings", appName = "Settings", versionName = "1.0", isSystemApp = true),
                AppInfo(packageName = "com.openai.chatgpt", appName = "ChatGPT", versionName = "1.0", isSystemApp = false)
            )
        }

        fakeBridge = object : AiAppBridge(null) {
            override fun discoverInstalledAiApps(): List<InstalledAiApp> = listOf(
                InstalledAiApp(SupportedAiApp.CHATGPT, isInstalled = true, packageName = "com.openai.chatgpt", appLabel = "ChatGPT", versionName = "1.0")
            )
            override fun getPreferredOrAvailablePackage(preferred: SupportedAiApp): String? = "com.openai.chatgpt"
            override suspend fun askInstalledAiApp(query: AiBridgeQuery, onProgress: ((String) -> Unit)?): AiBridgeResponse {
                return AiBridgeResponse(
                    success = true,
                    prompt = query.prompt,
                    responseText = "On-device AI response for: ${query.prompt}",
                    appUsed = "ChatGPT"
                )
            }
        }

        localPlanner = LocalTaskPlanner(toolRegistry, fakeAppInventory, fakeBridge)
    }

    private val fakeExperienceRepo = object : com.omni.assistant.domain.repository.ExperienceRepository {
        val records = mutableListOf<com.omni.assistant.domain.model.ExperienceRecord>()
        override suspend fun recordExperience(experience: com.omni.assistant.domain.model.ExperienceRecord) {
            records.add(experience)
        }
        override suspend fun recallStrategy(taskCategory: String, initialState: String): com.omni.assistant.domain.model.ExperienceRecord? {
            return records.find { it.taskCategory == taskCategory }
        }
        override suspend fun recallCategoryStrategies(taskCategory: String): List<com.omni.assistant.domain.model.ExperienceRecord> {
            return records.filter { it.taskCategory == taskCategory }
        }
        override fun getAllExperiences(): Flow<List<com.omni.assistant.domain.model.ExperienceRecord>> = flowOf(records)
        override suspend fun clearAllExperiences() { records.clear() }
    }

    private val fakeLifecycleManager = com.omni.assistant.accessibility.lifecycle.SnapshotLifecycleManager(null)

    private fun createOrchestrator(
        cloudProvider: AiProvider,
        configRepo: AiConfigRepository
    ): AgentOrchestrator {
        return AgentOrchestrator(
            cloudAiProvider = cloudProvider,
            localTaskPlanner = localPlanner,
            aiAppBridge = fakeBridge,
            settingsRepository = fakeSettingsRepo,
            aiConfigRepository = configRepo,
            toolRegistry = toolRegistry,
            actionPolicy = policy,
            commandDispatcher = dispatcher,
            chatRepository = fakeChatRepo,
            taskRepository = fakeTaskRepo,
            experienceRepository = fakeExperienceRepo,
            snapshotLifecycleManager = fakeLifecycleManager
        )
    }

    @Test
    fun testDeterministicLocalFastPath() = runBlocking {
        val fakeCloudProvider = object : AiProvider {
            override suspend fun generateResponse(request: AiRequest): AiResponse {
                throw AssertionError("Cloud provider should not be called for local fast-path commands")
            }
        }

        val configRepo = FakeConfigRepo(AiConfig(apiKey = "valid_key_12345"))
        val orchestrator = createOrchestrator(fakeCloudProvider, configRepo)

        val result = orchestrator.execute("Check my battery.")

        assertTrue(result is AgentExecutionResult.Success)
        val success = result as AgentExecutionResult.Success
        assertEquals("Battery: 82% (Charging)", success.assistantMessage.content)
        assertEquals(listOf("get_battery_info"), success.executedTools)

        val savedTask = fakeTaskRepo.tasks.first()
        assertEquals(TaskStatus.SUCCEEDED, savedTask.status)
        assertEquals("Battery: 82% (Charging)", savedTask.resultSummary)
    }

    @Test
    fun testAiActionProposalLoopExecution() = runBlocking {
        val proposalBridge = object : AiAppBridge(null) {
            override fun discoverInstalledAiApps(): List<InstalledAiApp> = listOf(
                InstalledAiApp(SupportedAiApp.CHATGPT, isInstalled = true, packageName = "com.openai.chatgpt", appLabel = "ChatGPT", versionName = "1.0")
            )
            override fun getPreferredOrAvailablePackage(preferred: SupportedAiApp): String? = "com.openai.chatgpt"
            override suspend fun askInstalledAiApp(query: AiBridgeQuery, onProgress: ((String) -> Unit)?): AiBridgeResponse {
                return AiBridgeResponse(
                    success = true,
                    prompt = query.prompt,
                    responseText = """
                    ```json
                    {
                      "thought": "User wants to inspect battery",
                      "action": "get_battery_info",
                      "arguments": {}
                    }
                    ```
                    """.trimIndent(),
                    appUsed = "ChatGPT"
                )
            }
        }

        val dummyCloudProvider = object : AiProvider {
            override suspend fun generateResponse(request: AiRequest): AiResponse {
                throw AssertionError("Cloud provider should not be called")
            }
        }

        val orchestrator = AgentOrchestrator(
            cloudAiProvider = dummyCloudProvider,
            localTaskPlanner = localPlanner,
            aiAppBridge = proposalBridge,
            settingsRepository = fakeSettingsRepo,
            aiConfigRepository = FakeConfigRepo(AiConfig()),
            toolRegistry = toolRegistry,
            actionPolicy = policy,
            commandDispatcher = dispatcher,
            chatRepository = fakeChatRepo,
            taskRepository = fakeTaskRepo,
            experienceRepository = fakeExperienceRepo,
            snapshotLifecycleManager = fakeLifecycleManager
        )

        val result = orchestrator.execute("Explain why my phone is warm and check it")
        assertTrue(result is AgentExecutionResult.Success)
        val success = result as AgentExecutionResult.Success
        assertEquals("Battery: 82% (Charging)", success.assistantMessage.content)
        assertTrue(success.executedTools.contains("get_battery_info"))
    }

    @Test
    fun testAiActionParser() {
        val rawMarkdown = """
        Here is the planned action:
        ```json
        {
          "thought": "I should open WhatsApp",
          "action": "launch_app",
          "arguments": {
            "appName": "WhatsApp"
          }
        }
        ```
        """.trimIndent()

        val parsed = com.omni.assistant.ai.orchestrator.AiActionParser.parse(rawMarkdown)
        assertNotNull(parsed)
        assertEquals("launch_app", parsed!!.action)
        assertEquals("WhatsApp", parsed.arguments["appName"])
        assertEquals("I should open WhatsApp", parsed.thought)

        val finishJson = """{"thought": "done", "action": "finish", "message": "Everything is ready."}"""
        val parsedFinish = com.omni.assistant.ai.orchestrator.AiActionParser.parse(finishJson)
        assertNotNull(parsedFinish)
        assertEquals("finish", parsedFinish!!.action)
        assertEquals("Everything is ready.", parsedFinish.message)
    }
}
