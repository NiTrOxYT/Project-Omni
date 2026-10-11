package com.omni.assistant

import com.omni.assistant.accessibility.AccessibilityConnectionManager
import com.omni.assistant.accessibility.UiBounds
import com.omni.assistant.accessibility.UiElement
import com.omni.assistant.accessibility.UiSnapshot
import com.omni.assistant.ai.AiProvider
import com.omni.assistant.ai.AiRequest
import com.omni.assistant.ai.AiResponse
import com.omni.assistant.ai.LocalPlanResult
import com.omni.assistant.ai.LocalTaskPlanner
import com.omni.assistant.ai.bridge.AiAppBridge
import com.omni.assistant.ai.bridge.AiBridgeQuery
import com.omni.assistant.ai.bridge.AiBridgeResponse
import com.omni.assistant.ai.bridge.InstalledAiApp
import com.omni.assistant.ai.bridge.SupportedAiApp
import com.omni.assistant.ai.config.AiConfig
import com.omni.assistant.ai.config.AiConfigRepository
import com.omni.assistant.ai.intent.IntentClassifier
import com.omni.assistant.ai.intent.UserIntent
import com.omni.assistant.ai.orchestrator.AgentExecutionResult
import com.omni.assistant.ai.orchestrator.AgentOrchestrator
import com.omni.assistant.device.AppInventoryProvider
import com.omni.assistant.domain.model.AppInfo
import com.omni.assistant.domain.model.ChatMessage
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
import com.omni.assistant.execution.tools.GetAppVersionTool
import com.omni.assistant.execution.tools.GetBatteryInfoTool
import com.omni.assistant.execution.tools.GetInstalledAppsTool
import com.omni.assistant.security.StandardActionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UniversalIntentRoutingTest {

    private lateinit var toolRegistry: ToolRegistry
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var localPlanner: LocalTaskPlanner
    private lateinit var intentClassifier: IntentClassifier
    private lateinit var fakeAppInventory: AppInventoryProvider

    private val messages = mutableListOf<ChatMessage>()
    private val fakeChatRepo = object : ChatRepository {
        override fun getMessages(): Flow<List<ChatMessage>> = flowOf(messages)
        override suspend fun insertMessage(message: ChatMessage) { messages.add(message) }
        override suspend fun clearHistory() { messages.clear() }
    }

    private val tasks = mutableListOf<TaskItem>()
    private val fakeTaskRepo = object : TaskRepository {
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
        override fun getThemeMode(): Flow<AppThemeMode> = flowOf(AppThemeMode.DARK)
        override suspend fun setThemeMode(mode: AppThemeMode) {}
        override fun getPreferredAiApp(): Flow<SupportedAiApp> = flowOf(SupportedAiApp.CHATGPT)
        override suspend fun setPreferredAiApp(app: SupportedAiApp) {}
        override fun isMessagingAutoSendEnabled(): Flow<Boolean> = flowOf(false)
        override suspend fun setMessagingAutoSendEnabled(enabled: Boolean) {}
    }

    private val experiences = mutableListOf<ExperienceRecord>()
    private val fakeExperienceRepo = object : ExperienceRepository {
        override suspend fun recordExperience(experience: ExperienceRecord) { experiences.add(experience) }
        override suspend fun recallStrategy(taskCategory: String, initialState: String): ExperienceRecord? =
            experiences.find { it.taskCategory == taskCategory }
        override suspend fun recallCategoryStrategies(taskCategory: String): List<ExperienceRecord> =
            experiences.filter { it.taskCategory == taskCategory }
        override fun getAllExperiences(): Flow<List<ExperienceRecord>> = flowOf(experiences)
        override suspend fun clearAllExperiences() { experiences.clear() }
    }

    private val fakeLifecycleManager = com.omni.assistant.accessibility.lifecycle.SnapshotLifecycleManager(null)

    private val dummyCloudProvider = object : AiProvider {
        override suspend fun generateResponse(request: AiRequest): AiResponse {
            throw AssertionError("Cloud AI provider must not be invoked (API-free)")
        }
    }

    private var chatGptBridgeCalled = false
    private var lastBridgeQuery: AiBridgeQuery? = null
    private var simulatedBridgeResponse = AiBridgeResponse(
        success = true,
        prompt = "",
        responseText = "The Pacific Ocean is the largest ocean on Earth.",
        appUsed = "ChatGPT"
    )

    private val controllableBridge = object : AiAppBridge(null) {
        override fun discoverInstalledAiApps(): List<InstalledAiApp> = listOf(
            InstalledAiApp(SupportedAiApp.CHATGPT, isInstalled = true, packageName = "com.openai.chatgpt", appLabel = "ChatGPT", versionName = "1.0")
        )
        override fun getPreferredOrAvailablePackage(preferred: SupportedAiApp): String? = "com.openai.chatgpt"
        override suspend fun askInstalledAiApp(query: AiBridgeQuery, onProgress: ((String) -> Unit)?): AiBridgeResponse {
            chatGptBridgeCalled = true
            lastBridgeQuery = query
            return simulatedBridgeResponse.copy(prompt = query.prompt)
        }
    }

    @Before
    fun setUp() {
        messages.clear()
        tasks.clear()
        experiences.clear()
        chatGptBridgeCalled = false
        lastBridgeQuery = null

        toolRegistry = ToolRegistry()
        dispatcher = CommandDispatcher(toolRegistry, StandardActionPolicy())

        fakeAppInventory = object : AppInventoryProvider(null) {
            override fun getVisibleInstalledApplications(): List<AppInfo> = listOf(
                AppInfo("com.instagram.android", "Instagram", "1.0", false),
                AppInfo("com.android.settings", "Settings", "1.0", true),
                AppInfo("com.whatsapp", "WhatsApp", "1.0", false),
                AppInfo("com.openai.chatgpt", "ChatGPT", "1.0", false)
            )
        }

        intentClassifier = IntentClassifier(fakeAppInventory)
        localPlanner = LocalTaskPlanner(toolRegistry, fakeAppInventory, controllableBridge, intentClassifier)

        // Register battery tool
        toolRegistry.register(object : OmniTool {
            override val definition = ToolDefinition("get_battery_info", "Battery info", RiskLevel.LOW)
            override suspend fun execute(parameters: Map<String, Any?>): ToolResult = ToolResult(
                success = true,
                tool = "get_battery_info",
                summaryText = "Battery: 85% (Discharging) | Health: Good"
            )
        })

        // Register app launch tool
        toolRegistry.register(object : OmniTool {
            override val definition = ToolDefinition("launch_app", "Launch app", RiskLevel.LOW)
            override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
                val app = parameters["appName"]?.toString() ?: "App"
                return ToolResult(
                    success = true,
                    tool = "launch_app",
                    summaryText = "Successfully launched application '$app'."
                )
            }
        })

        // Register installed apps tool
        toolRegistry.register(GetInstalledAppsTool(fakeAppInventory))

        // Register scroll and tap tools
        toolRegistry.register(object : OmniTool {
            override val definition = ToolDefinition("scroll_ui", "Scroll UI", RiskLevel.LOW)
            override suspend fun execute(parameters: Map<String, Any?>): ToolResult = ToolResult(
                success = true,
                tool = "scroll_ui",
                summaryText = "Scrolled UI down"
            )
        })

        toolRegistry.register(object : OmniTool {
            override val definition = ToolDefinition("tap_element", "Tap UI", RiskLevel.LOW)
            override suspend fun execute(parameters: Map<String, Any?>): ToolResult = ToolResult(
                success = true,
                tool = "tap_element",
                summaryText = "Tapped element"
            )
        })
    }

    @After
    fun tearDown() {
        AccessibilityConnectionManager.clearSnapshots()
    }

    private fun createOrchestrator(bridge: AiAppBridge = controllableBridge): AgentOrchestrator {
        return AgentOrchestrator(
            cloudAiProvider = dummyCloudProvider,
            localTaskPlanner = localPlanner,
            aiAppBridge = bridge,
            settingsRepository = fakeSettingsRepo,
            aiConfigRepository = object : AiConfigRepository {
                override fun getConfig() = flowOf(AiConfig())
                override fun getCurrentConfig() = AiConfig()
                override suspend fun updateApiKey(apiKey: String) {}
                override suspend fun updateModel(modelName: String) {}
                override suspend fun updateUseCloudAi(useCloudAi: Boolean) {}
            },
            toolRegistry = toolRegistry,
            actionPolicy = StandardActionPolicy(),
            commandDispatcher = dispatcher,
            chatRepository = fakeChatRepo,
            taskRepository = fakeTaskRepo,
            experienceRepository = fakeExperienceRepo,
            snapshotLifecycleManager = fakeLifecycleManager
        )
    }

    // ==========================================
    // TEST 1 — UNKNOWN GENERAL QUESTION
    // ==========================================
    @Test
    fun test1_UnknownGeneralQuestion_RoutesToChatGPTFallbackAndDisplaysAnswer() = runBlocking {
        val input = "What is the largest ocean on Earth?"
        val intent = intentClassifier.classify(input)

        // Verify classification
        assertTrue("Expected GeneralQuestion intent", intent is UserIntent.GeneralQuestion)

        simulatedBridgeResponse = AiBridgeResponse(
            success = true,
            prompt = input,
            responseText = "The Pacific Ocean is the largest and deepest ocean on Earth.",
            appUsed = "ChatGPT"
        )

        val orchestrator = createOrchestrator()
        val result = orchestrator.execute(input)

        // Verify result
        assertTrue(result is AgentExecutionResult.Success)
        val success = result as AgentExecutionResult.Success
        assertTrue("ChatGPT bridge should be used", chatGptBridgeCalled)
        assertEquals("The Pacific Ocean is the largest and deepest ocean on Earth.", success.assistantMessage.content)

        // Verify chat repository has user and assistant messages
        val savedAssistantMessage = messages.find { it.sender == com.omni.assistant.domain.model.MessageSender.ASSISTANT }
        assertNotNull(savedAssistantMessage)
        assertEquals("The Pacific Ocean is the largest and deepest ocean on Earth.", savedAssistantMessage?.content)

        // Verify task completion
        val savedTask = tasks.first()
        assertEquals(TaskStatus.SUCCEEDED, savedTask.status)
        assertEquals("The Pacific Ocean is the largest and deepest ocean on Earth.", savedTask.resultSummary)
    }

    // ==========================================
    // TEST 2 — KNOWN LOCAL ACTION
    // ==========================================
    @Test
    fun test2_KnownLocalAction_LaunchesAppWithoutUsingChatGPT() = runBlocking {
        val input = "Open Instagram."
        val intent = intentClassifier.classify(input)

        // Verify classification
        assertTrue("Expected AppAction", intent is UserIntent.AppAction)
        assertEquals("Instagram", (intent as UserIntent.AppAction).appName)

        val orchestrator = createOrchestrator()
        val result = orchestrator.execute(input)

        // Verify execution
        assertTrue(result is AgentExecutionResult.Success)
        val success = result as AgentExecutionResult.Success
        assertFalse("ChatGPT must NOT be called for known local app action", chatGptBridgeCalled)
        assertTrue(success.executedTools.contains("launch_app"))
        assertTrue(success.assistantMessage.content.contains("Instagram"))

        val savedTask = tasks.first()
        assertEquals(TaskStatus.SUCCEEDED, savedTask.status)
    }

    // ==========================================
    // TEST 3 — DEVICE QUESTION
    // ==========================================
    @Test
    fun test3_DeviceQuestion_UsesRealDeviceCapabilityWithoutChatGPT() = runBlocking {
        val input = "Check my battery level."
        val intent = intentClassifier.classify(input)

        // Verify classification
        assertTrue("Expected DeviceQuery", intent is UserIntent.DeviceQuery)
        assertEquals("get_battery_info", (intent as UserIntent.DeviceQuery).toolName)

        val orchestrator = createOrchestrator()
        val result = orchestrator.execute(input)

        // Verify local capability used
        assertTrue(result is AgentExecutionResult.Success)
        val success = result as AgentExecutionResult.Success
        assertFalse("ChatGPT must NOT be consulted to guess battery level", chatGptBridgeCalled)
        assertEquals("Battery: 85% (Discharging) | Health: Good", success.assistantMessage.content)
        assertEquals(listOf("get_battery_info"), success.executedTools)

        val savedTask = tasks.first()
        assertEquals(TaskStatus.SUCCEEDED, savedTask.status)
    }

    // ==========================================
    // TEST 4 — NOVEL MULTI-STEP REQUEST
    // ==========================================
    @Test
    fun test4_NovelMultiStepRequest_PlansBoundedActionsAndInspectsScreen() = runBlocking {
        val input = "Open Settings and find the Bluetooth option."
        val intent = intentClassifier.classify(input)

        // Verify classification
        assertTrue("Expected MultiStepTask", intent is UserIntent.MultiStepTask)
        val multi = intent as UserIntent.MultiStepTask
        assertEquals("Settings", multi.targetApp)
        assertEquals("Bluetooth", multi.targetElementText)

        // Seed AccessibilityConnectionManager with mock screen observation containing Bluetooth option
        val mockElement = UiElement(
            id = "e1",
            className = "android.widget.TextView",
            text = "Bluetooth",
            contentDescription = "Bluetooth settings",
            bounds = UiBounds(50, 400, 900, 550),
            isClickable = true
        )
        val mockSnapshot = UiSnapshot(
            snapshotId = "mock_settings_snap",
            timestamp = System.currentTimeMillis(),
            packageName = "com.android.settings",
            elements = listOf(mockElement),
            totalNodesCount = 1,
            isTruncated = false,
            summary = "Settings main screen with Bluetooth option"
        )
        AccessibilityConnectionManager.overrideCurrentSnapshotForTesting(mockSnapshot)

        val orchestrator = createOrchestrator()
        val result = orchestrator.execute(input)

        // Verify progress through bounded actions: launch_app -> observe screen -> tap_element
        assertTrue(result is AgentExecutionResult.Success)
        val success = result as AgentExecutionResult.Success
        assertTrue(success.executedTools.contains("launch_app"))
        assertTrue(success.executedTools.contains("tap_element"))
        assertTrue(success.assistantMessage.content.contains("Opened Settings and selected 'Bluetooth'"))

        val savedTask = tasks.first()
        assertEquals(TaskStatus.SUCCEEDED, savedTask.status)
    }

    // ==========================================
    // TEST 5 — CHATGPT UNAVAILABLE
    // ==========================================
    @Test
    fun test5_ChatGptUnavailable_SurfacesClearFailureWithoutFabricatingAnswer() = runBlocking {
        val unavailableBridge = object : AiAppBridge(null) {
            override fun discoverInstalledAiApps(): List<InstalledAiApp> = emptyList()
            override fun getPreferredOrAvailablePackage(preferred: SupportedAiApp): String? = null
            override suspend fun askInstalledAiApp(query: AiBridgeQuery, onProgress: ((String) -> Unit)?): AiBridgeResponse {
                return AiBridgeResponse(
                    success = false,
                    prompt = query.prompt,
                    responseText = null,
                    appUsed = "none",
                    error = "No supported AI application (ChatGPT or Google Gemini) is installed on this device."
                )
            }
        }

        val orchestrator = createOrchestrator(unavailableBridge)
        val result = orchestrator.execute("What is the capital of France?")

        // Verify failure surfaced clearly and no fake answer produced
        assertTrue(result is AgentExecutionResult.Failure)
        val failure = result as AgentExecutionResult.Failure
        assertTrue(failure.errorMessage.contains("No supported AI application"))
        assertTrue(failure.assistantMessage.content.contains("No supported AI application"))

        val savedTask = tasks.first()
        assertEquals(TaskStatus.FAILED, savedTask.status)
        assertTrue(savedTask.errorDetails?.contains("No supported AI application") == true)
    }

    // ==========================================
    // TEST 6 — EMPTY OR STALE RESPONSE
    // ==========================================
    @Test
    fun test6_EmptyOrStaleResponse_SurfacesFailureClearlyWithBoundedRetries() = runBlocking {
        val timeoutBridge = object : AiAppBridge(null) {
            override fun discoverInstalledAiApps(): List<InstalledAiApp> = listOf(
                InstalledAiApp(SupportedAiApp.CHATGPT, isInstalled = true, packageName = "com.openai.chatgpt", appLabel = "ChatGPT", versionName = "1.0")
            )
            override fun getPreferredOrAvailablePackage(preferred: SupportedAiApp): String? = "com.openai.chatgpt"
            override suspend fun askInstalledAiApp(query: AiBridgeQuery, onProgress: ((String) -> Unit)?): AiBridgeResponse {
                return AiBridgeResponse(
                    success = false,
                    prompt = query.prompt,
                    responseText = null,
                    appUsed = "ChatGPT",
                    error = "Timed out waiting for response from ChatGPT. Ensure the app is signed in and has internet access."
                )
            }
        }

        val orchestrator = createOrchestrator(timeoutBridge)
        val result = orchestrator.execute("Explain quantum entanglement.")

        assertTrue(result is AgentExecutionResult.Failure)
        val failure = result as AgentExecutionResult.Failure
        assertTrue(failure.errorMessage.contains("Timed out waiting for response"))
        assertEquals(TaskStatus.FAILED, tasks.first().status)
    }

    // ==========================================
    // TEST 7 — AMBIGUOUS ACTION
    // ==========================================
    @Test
    fun test7_AmbiguousAction_AsksForMissingInformationWithoutGuessing() = runBlocking {
        val input = "Send it to her."
        val intent = intentClassifier.classify(input)

        // Verify intent classification
        assertTrue("Expected Ambiguous intent", intent is UserIntent.Ambiguous)
        val amb = intent as UserIntent.Ambiguous
        assertEquals("Who would you like to send this to, and what message should I send?", amb.clarificationPrompt)

        val orchestrator = createOrchestrator()
        val result = orchestrator.execute(input)

        // Verify concise clarification question returned to user
        assertTrue(result is AgentExecutionResult.Success)
        val success = result as AgentExecutionResult.Success
        assertEquals("Who would you like to send this to, and what message should I send?", success.assistantMessage.content)
        assertFalse("ChatGPT must NOT be called for ambiguous prompt", chatGptBridgeCalled)
    }

    // ==========================================
    // TEST 8 — RESPONSE RESUMPTION
    // ==========================================
    @Test
    fun test8_ResponseResumption_AssociatesAnswerWithOriginalTaskWithoutDuplicates() = runBlocking {
        val input = "Explain how Wi-Fi works."
        simulatedBridgeResponse = AiBridgeResponse(
            success = true,
            prompt = input,
            responseText = "Wi-Fi uses radio waves to transmit data across a wireless local area network.",
            appUsed = "ChatGPT"
        )

        val orchestrator = createOrchestrator()
        val result = orchestrator.execute(input)

        assertTrue(result is AgentExecutionResult.Success)
        val success = result as AgentExecutionResult.Success
        val taskId = success.taskId

        // Exactly one user message and one assistant message
        assertEquals(2, messages.size)
        assertEquals(taskId, messages[0].taskId)
        assertEquals(taskId, messages[1].taskId)
        assertEquals("Wi-Fi uses radio waves to transmit data across a wireless local area network.", messages[1].content)

        // Saved task has single correct result
        assertEquals(1, tasks.size)
        assertEquals(taskId, tasks[0].id)
        assertEquals(TaskStatus.SUCCEEDED, tasks[0].status)
        assertEquals("Wi-Fi uses radio waves to transmit data across a wireless local area network.", tasks[0].resultSummary)
    }

    // ==========================================
    // TEST 9 — INSTALLED APPS LOCAL INVENTORY
    // ==========================================
    @Test
    fun test9_InstalledAppsQuery_UsesLocalAppInventoryTool() = runBlocking {
        val input = "Which apps are installed?"
        val intent = intentClassifier.classify(input)

        assertTrue("Expected DeviceQuery for installed apps", intent is UserIntent.DeviceQuery)
        assertEquals("get_installed_apps", (intent as UserIntent.DeviceQuery).toolName)

        val orchestrator = createOrchestrator()
        val result = orchestrator.execute(input)

        assertTrue(result is AgentExecutionResult.Success)
        val success = result as AgentExecutionResult.Success
        assertFalse("ChatGPT must not be called to list installed apps", chatGptBridgeCalled)
        assertTrue(success.executedTools.contains("get_installed_apps"))
        assertTrue(success.assistantMessage.content.contains("Instagram"))
        assertTrue(success.assistantMessage.content.contains("WhatsApp"))
    }
}
