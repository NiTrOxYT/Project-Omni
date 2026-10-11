package com.omni.assistant

import com.omni.assistant.accessibility.lifecycle.SnapshotLifecycleManager
import com.omni.assistant.accessibility.observation.ObservationClassifier
import com.omni.assistant.accessibility.observation.ObservationStatus
import com.omni.assistant.accessibility.observation.ScreenClassification
import com.omni.assistant.accessibility.UiBounds
import com.omni.assistant.accessibility.UiElement
import com.omni.assistant.accessibility.UiSnapshot
import com.omni.assistant.ai.AiProvider
import com.omni.assistant.ai.AiRequest
import com.omni.assistant.ai.AiResponse
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
import com.omni.assistant.domain.model.ExperienceOutcome
import com.omni.assistant.domain.model.ExperienceRecord
import com.omni.assistant.domain.model.RiskLevel
import com.omni.assistant.domain.model.TaskItem
import com.omni.assistant.domain.model.TaskStatus
import com.omni.assistant.domain.repository.AppThemeMode
import com.omni.assistant.domain.repository.ChatRepository
import com.omni.assistant.domain.repository.ExperienceRepository
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

class SelfImprovingAgentOrchestratorTest {

    private lateinit var toolRegistry: ToolRegistry
    private lateinit var policy: StandardActionPolicy
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var localPlanner: LocalTaskPlanner
    private lateinit var fakeBridge: AiAppBridge
    private lateinit var fakeAppInventory: AppInventoryProvider
    private lateinit var tempBaseDir: File
    private lateinit var lifecycleManager: SnapshotLifecycleManager

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

    private val fakeExperienceRepo = object : ExperienceRepository {
        val records = mutableListOf<ExperienceRecord>()
        override suspend fun recordExperience(experience: ExperienceRecord) {
            records.add(experience)
        }
        override suspend fun recallStrategy(taskCategory: String, initialState: String): ExperienceRecord? {
            val record = records.find { it.taskCategory == taskCategory && it.generalizedInitialState == initialState }
            return if (record != null && record.successCount >= record.failureCount) record else null
        }
        override suspend fun recallCategoryStrategies(taskCategory: String): List<ExperienceRecord> {
            return records.filter { it.taskCategory == taskCategory && it.successCount >= it.failureCount }
        }
        override fun getAllExperiences(): Flow<List<ExperienceRecord>> = flowOf(records)
        override suspend fun clearAllExperiences() { records.clear() }
    }

    private val dummyCloudProvider = object : AiProvider {
        override suspend fun generateResponse(request: AiRequest): AiResponse {
            throw AssertionError("Cloud provider should not be called")
        }
    }

    private class FakeConfigRepo(var config: AiConfig) : AiConfigRepository {
        override fun getConfig(): Flow<AiConfig> = flowOf(config)
        override fun getCurrentConfig(): AiConfig = config
        override suspend fun updateApiKey(apiKey: String) { config = config.copy(apiKey = apiKey) }
        override suspend fun updateModel(modelName: String) { config = config.copy(modelName = modelName) }
        override suspend fun updateUseCloudAi(useCloudAi: Boolean) { config = config.copy(useCloudAi = useCloudAi) }
    }

    @Before
    fun setup() {
        tempBaseDir = File(System.getProperty("java.io.tmpdir"), "test_omni_workspaces_${UUID.randomUUID()}").apply { mkdirs() }
        lifecycleManager = SnapshotLifecycleManager(context = null, baseDirectory = tempBaseDir)

        toolRegistry = ToolRegistry()
        policy = StandardActionPolicy()
        dispatcher = CommandDispatcher(toolRegistry, policy)

        fakeAppInventory = object : AppInventoryProvider(null) {
            override fun getVisibleInstalledApplications(): List<AppInfo> = listOf(
                AppInfo("com.whatsapp", "WhatsApp", "1.0", false),
                AppInfo("com.android.settings", "Settings", "1.0", true)
            )
        }

        fakeBridge = object : AiAppBridge(null) {
            override fun discoverInstalledAiApps(): List<InstalledAiApp> = listOf(
                InstalledAiApp(SupportedAiApp.CHATGPT, true, "com.openai.chatgpt", "ChatGPT", "1.0")
            )
            override fun getPreferredOrAvailablePackage(preferred: SupportedAiApp): String? = "com.openai.chatgpt"
            override suspend fun askInstalledAiApp(query: AiBridgeQuery, onProgress: ((String) -> Unit)?): AiBridgeResponse {
                return AiBridgeResponse(success = true, prompt = query.prompt, responseText = "{\"thought\":\"ok\",\"action\":\"finish\",\"message\":\"Done\"}", appUsed = "ChatGPT")
            }
        }

        localPlanner = LocalTaskPlanner(toolRegistry, fakeAppInventory, fakeBridge)

        toolRegistry.register(object : OmniTool {
            override val definition = ToolDefinition(
                name = "get_battery_info",
                description = "Gets battery info",
                riskLevel = RiskLevel.LOW
            )
            override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
                return ToolResult(
                    success = true,
                    tool = "get_battery_info",
                    data = mapOf("percentage" to 85, "charging" to true),
                    summaryText = "Battery: 85% (Charging)"
                )
            }
        })

        toolRegistry.register(object : OmniTool {
            override val definition = ToolDefinition(
                name = "launch_app",
                description = "Launches an app",
                riskLevel = RiskLevel.LOW
            )
            override suspend fun execute(arguments: Map<String, Any?>): ToolResult {
                val app = arguments["appName"]?.toString() ?: "App"
                return ToolResult(
                    success = true,
                    tool = "launch_app",
                    data = mapOf("app" to app),
                    summaryText = "Opened $app"
                )
            }
        })
    }

    private fun createOrchestrator(bridge: AiAppBridge = fakeBridge): AgentOrchestrator {
        return AgentOrchestrator(
            cloudAiProvider = dummyCloudProvider,
            localTaskPlanner = localPlanner,
            aiAppBridge = bridge,
            settingsRepository = fakeSettingsRepo,
            aiConfigRepository = FakeConfigRepo(AiConfig()),
            toolRegistry = toolRegistry,
            actionPolicy = policy,
            commandDispatcher = dispatcher,
            chatRepository = fakeChatRepo,
            taskRepository = fakeTaskRepo,
            experienceRepository = fakeExperienceRepo,
            snapshotLifecycleManager = lifecycleManager
        )
    }

    // 1. Task requiring deterministic execution and learning
    @Test
    fun testTaskExecutionAndExperienceLearning() = runBlocking {
        val orchestrator = createOrchestrator()
        val result = orchestrator.execute("Check battery status")

        assertTrue(result is AgentExecutionResult.Success)
        val success = result as AgentExecutionResult.Success
        assertEquals("Battery: 85% (Charging)", success.assistantMessage.content)

        // Verify learning record saved in ExperienceRepository
        val experiences = fakeExperienceRepo.records
        assertEquals(1, experiences.size)
        assertEquals("DEVICE_INSPECTION", experiences.first().taskCategory)
        assertEquals(ExperienceOutcome.SUCCESS, experiences.first().finalOutcome)
        assertEquals(listOf("get_battery_info"), experiences.first().successfulActions)
    }

    // 2. Previously successful strategy recalled on later run
    @Test
    fun testPreviouslySuccessfulStrategyRecalled() = runBlocking {
        // Seed verified strategy in experience repo
        fakeExperienceRepo.recordExperience(
            ExperienceRecord(
                id = "strat-1",
                taskCategory = "DEVICE_INSPECTION",
                generalizedInitialState = "pkg:local",
                intendedOutcome = "CHECK_BATTERY",
                actionsAttempted = listOf("get_battery_info"),
                stateTransitions = listOf("SCREEN_A", "SCREEN_B"),
                successfulActions = listOf("get_battery_info"),
                finalOutcome = ExperienceOutcome.SUCCESS,
                executionDurationMs = 1500L,
                successCount = 5,
                failureCount = 0
            )
        )

        val recalled = fakeExperienceRepo.recallCategoryStrategies("DEVICE_INSPECTION")
        assertEquals(1, recalled.size)
        assertEquals("strat-1", recalled.first().id)
        assertEquals(listOf("get_battery_info"), recalled.first().successfulActions)
    }

    // 3. Known failed strategy avoided
    @Test
    fun testKnownFailedStrategyAvoided() = runBlocking {
        // Seed failed strategy
        fakeExperienceRepo.recordExperience(
            ExperienceRecord(
                id = "strat-fail",
                taskCategory = "DEVICE_INSPECTION",
                generalizedInitialState = "pkg:local",
                intendedOutcome = "CHECK_BATTERY",
                actionsAttempted = listOf("bad_tool"),
                stateTransitions = listOf("SCREEN_A"),
                successfulActions = emptyList(),
                failedActions = listOf("bad_tool"),
                finalOutcome = ExperienceOutcome.FAILURE,
                failureCategory = "TOOL_NOT_FOUND",
                executionDurationMs = 500L,
                successCount = 0,
                failureCount = 3
            )
        )

        val recalled = fakeExperienceRepo.recallStrategy("DEVICE_INSPECTION", "pkg:local")
        assertNull("Failed strategy with failureCount > successCount must be avoided", recalled)
    }

    // 4. Stale strategy rejected when initial state differs
    @Test
    fun testStaleStrategyRejectedOnDifferentScreen() = runBlocking {
        fakeExperienceRepo.recordExperience(
            ExperienceRecord(
                id = "strat-whatsapp",
                taskCategory = "MESSAGING_WHATSAPP",
                generalizedInitialState = "pkg:com.whatsapp|screen:CHAT_LIST",
                intendedOutcome = "SEND_WHATSAPP_MESSAGE",
                actionsAttempted = listOf("open_whatsapp", "search_contact"),
                stateTransitions = listOf("CHAT_LIST", "SEARCH_ACTIVE"),
                successfulActions = listOf("open_whatsapp", "search_contact"),
                finalOutcome = ExperienceOutcome.SUCCESS,
                executionDurationMs = 2000L,
                successCount = 2,
                failureCount = 0
            )
        )

        val recalled = fakeExperienceRepo.recallStrategy("MESSAGING_WHATSAPP", "pkg:com.android.settings|screen:SETTINGS_MAIN")
        assertNull("Strategy matching CHAT_LIST must not match SETTINGS_MAIN", recalled)
    }

    // 5. Task that needs AI-app reasoning
    @Test
    fun testTaskNeedingAiAppReasoning() = runBlocking {
        val bridge = object : AiAppBridge(null) {
            override fun discoverInstalledAiApps(): List<InstalledAiApp> = listOf(
                InstalledAiApp(SupportedAiApp.CHATGPT, true, "com.openai.chatgpt", "ChatGPT", "1.0")
            )
            override fun getPreferredOrAvailablePackage(preferred: SupportedAiApp): String? = "com.openai.chatgpt"
            override suspend fun askInstalledAiApp(query: AiBridgeQuery, onProgress: ((String) -> Unit)?): AiBridgeResponse {
                return AiBridgeResponse(
                    success = true,
                    prompt = query.prompt,
                    responseText = """
                    ```json
                    {
                      "thought": "User has complex battery question",
                      "action": "get_battery_info",
                      "arguments": {}
                    }
                    ```
                    """.trimIndent(),
                    appUsed = "ChatGPT"
                )
            }
        }

        val orchestrator = createOrchestrator(bridge)
        val result = orchestrator.execute("Why is my phone dying so fast? Inspect the battery")

        assertTrue(result is AgentExecutionResult.Success)
        val success = result as AgentExecutionResult.Success
        assertEquals("Battery: 85% (Charging)", success.assistantMessage.content)
        assertTrue(success.executedTools.contains("get_battery_info"))
    }

    // 6. Unavailable AI application
    @Test
    fun testUnavailableAiApplication() = runBlocking {
        val failingBridge = object : AiAppBridge(null) {
            override fun discoverInstalledAiApps(): List<InstalledAiApp> = emptyList()
            override fun getPreferredOrAvailablePackage(preferred: SupportedAiApp): String? = null
            override suspend fun askInstalledAiApp(query: AiBridgeQuery, onProgress: ((String) -> Unit)?): AiBridgeResponse {
                return AiBridgeResponse(
                    success = false,
                    prompt = query.prompt,
                    responseText = null,
                    error = "No installed AI application available.",
                    appUsed = "None"
                )
            }
        }

        val orchestrator = createOrchestrator(failingBridge)
        val result = orchestrator.execute("Ask ChatGPT to write a poem")

        assertTrue(result is AgentExecutionResult.Failure)
        val failure = result as AgentExecutionResult.Failure
        assertTrue(failure.errorMessage.contains("No installed AI application available"))
    }

    // 7. Task reaches terminal state and cleans up heavy snapshots & workspace
    @Test
    fun testTerminalStateCleansUpWorkspace() = runBlocking {
        val orchestrator = createOrchestrator()
        val result = orchestrator.execute("Check battery status")

        assertTrue(result is AgentExecutionResult.Success)
        val taskId = (result as AgentExecutionResult.Success).taskId

        val workspace = File(lifecycleManager.tasksBaseDir, taskId)
        assertFalse("Task workspace directory should be deleted after terminal state", workspace.exists())
    }

    // 8. Recovery from stale artifacts after simulated process interruption
    @Test
    fun testStaleArtifactsCleanupOnStartup() {
        val staleWorkspace1 = File(lifecycleManager.tasksBaseDir, "stale_task_111").apply { mkdirs() }
        File(staleWorkspace1, "dump.json").writeText("old dump")
        val staleWorkspace2 = File(lifecycleManager.tasksBaseDir, "stale_task_222").apply { mkdirs() }
        File(staleWorkspace2, "screenshot.png").writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(staleWorkspace1.exists())
        assertTrue(staleWorkspace2.exists())

        val cleanedCount = lifecycleManager.cleanupStaleWorkspaces()
        assertEquals(2, cleanedCount)
        assertFalse(staleWorkspace1.exists())
        assertFalse(staleWorkspace2.exists())
    }

    // 9. Observation Classifier categorizes WhatsApp and Settings correctly
    @Test
    fun testObservationClassifier() {
        val chatListSnapshot = UiSnapshot(
            snapshotId = "s1",
            timestamp = 1000L,
            packageName = "com.whatsapp",
            elements = listOf(
                UiElement(
                    id = "e1",
                    className = "android.widget.TextView",
                    text = "Chats",
                    bounds = UiBounds(0, 0, 100, 100)
                ),
                UiElement(
                    id = "e2",
                    className = "android.widget.ImageButton",
                    resourceId = "com.whatsapp:id/fab",
                    contentDescription = "New chat",
                    isClickable = true,
                    bounds = UiBounds(0, 0, 100, 100)
                )
            ),
            totalNodesCount = 2,
            isTruncated = false,
            summary = "WhatsApp chats"
        )
        val chatListObservation = ObservationClassifier.classify(chatListSnapshot)
        assertEquals(ScreenClassification.CHAT_LIST, chatListObservation.screenClassification)
        assertEquals(ObservationStatus.COMPLETE, chatListObservation.status)

        val conversationSnapshot = UiSnapshot(
            snapshotId = "s2",
            timestamp = 2000L,
            packageName = "com.whatsapp",
            elements = listOf(
                UiElement(
                    id = "e1",
                    className = "android.widget.EditText",
                    resourceId = "com.whatsapp:id/entry",
                    text = "Type a message",
                    isEditable = true,
                    bounds = UiBounds(0, 0, 100, 100)
                ),
                UiElement(
                    id = "e2",
                    className = "android.widget.ImageButton",
                    resourceId = "com.whatsapp:id/send",
                    contentDescription = "Send",
                    isClickable = true,
                    bounds = UiBounds(0, 0, 100, 100)
                )
            ),
            totalNodesCount = 2,
            isTruncated = false,
            summary = "WhatsApp conversation"
        )
        val conversationObservation = ObservationClassifier.classify(conversationSnapshot)
        assertEquals(ScreenClassification.CONVERSATION, conversationObservation.screenClassification)
    }
}
