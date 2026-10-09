package com.omni.assistant

import com.omni.assistant.ai.AiMessage
import com.omni.assistant.ai.AiProvider
import com.omni.assistant.ai.AiRequest
import com.omni.assistant.ai.AiResponse
import com.omni.assistant.ai.AiRole
import com.omni.assistant.ai.AiToolCall
import com.omni.assistant.ai.LocalCommandProcessor
import com.omni.assistant.ai.config.AiConfig
import com.omni.assistant.ai.config.AiConfigRepository
import com.omni.assistant.ai.orchestrator.AgentExecutionResult
import com.omni.assistant.ai.orchestrator.AgentOrchestrator
import com.omni.assistant.domain.model.ChatMessage
import com.omni.assistant.domain.model.RiskLevel
import com.omni.assistant.domain.model.TaskItem
import com.omni.assistant.domain.model.TaskStatus
import com.omni.assistant.domain.repository.ChatRepository
import com.omni.assistant.domain.repository.TaskRepository
import com.omni.assistant.execution.CommandDispatcher
import com.omni.assistant.execution.OmniTool
import com.omni.assistant.execution.ToolDefinition
import com.omni.assistant.execution.ToolRegistry
import com.omni.assistant.execution.ToolResult
import com.omni.assistant.security.StandardActionPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentOrchestratorTest {

    private lateinit var toolRegistry: ToolRegistry
    private lateinit var policy: StandardActionPolicy
    private lateinit var dispatcher: CommandDispatcher
    private lateinit var localProcessor: LocalCommandProcessor

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
        localProcessor = LocalCommandProcessor()

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
    }

    @Test
    fun testDeterministicLocalFastPath() = runBlocking {
        val fakeCloudProvider = object : AiProvider {
            override suspend fun generateResponse(request: AiRequest): AiResponse {
                throw AssertionError("Cloud provider should not be called for local fast-path commands")
            }
        }

        val configRepo = FakeConfigRepo(AiConfig(apiKey = "valid_key_12345"))
        val orchestrator = AgentOrchestrator(
            cloudAiProvider = fakeCloudProvider,
            localCommandProcessor = localProcessor,
            aiConfigRepository = configRepo,
            toolRegistry = toolRegistry,
            actionPolicy = policy,
            commandDispatcher = dispatcher,
            chatRepository = fakeChatRepo,
            taskRepository = fakeTaskRepo
        )

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
    fun testMultiTurnCloudToolCallingLoop() = runBlocking {
        // Step 1: Model requests tool "get_battery_info"
        // Step 2: Model returns grounded final answer based on tool output
        var step = 0
        val fakeCloudProvider = object : AiProvider {
            override suspend fun generateResponse(request: AiRequest): AiResponse {
                step++
                return if (step == 1) {
                    AiResponse(
                        isHandled = true,
                        toolCalls = listOf(AiToolCall(id = "call_1", name = "get_battery_info", arguments = emptyMap()))
                    )
                } else {
                    // Verify tool result was delivered in history
                    val toolMsg = request.conversationHistory.find { it.role == AiRole.TOOL }
                    assertNotNull("Tool result must be provided in conversation history", toolMsg)
                    AiResponse(
                        isHandled = true,
                        replyText = "Your device currently has 82% battery remaining and is charging."
                    )
                }
            }
        }

        val configRepo = FakeConfigRepo(AiConfig(apiKey = "valid_key_12345"))
        val orchestrator = AgentOrchestrator(
            cloudAiProvider = fakeCloudProvider,
            localCommandProcessor = localProcessor,
            aiConfigRepository = configRepo,
            toolRegistry = toolRegistry,
            actionPolicy = policy,
            commandDispatcher = dispatcher,
            chatRepository = fakeChatRepo,
            taskRepository = fakeTaskRepo
        )

        val result = orchestrator.execute("How much power do I have left?")

        assertTrue(result is AgentExecutionResult.Success)
        val success = result as AgentExecutionResult.Success
        assertEquals("Your device currently has 82% battery remaining and is charging.", success.assistantMessage.content)
        assertEquals(listOf("get_battery_info"), success.executedTools)
        assertEquals(2, step)
    }

    @Test
    fun testRejectionOfUnknownToolRequestedByModel() = runBlocking {
        val fakeCloudProvider = object : AiProvider {
            var step = 0
            override suspend fun generateResponse(request: AiRequest): AiResponse {
                step++
                return if (step == 1) {
                    AiResponse(
                        isHandled = true,
                        toolCalls = listOf(AiToolCall(id = "call_bad", name = "delete_all_files", arguments = emptyMap()))
                    )
                } else {
                    val toolMsg = request.conversationHistory.find { it.role == AiRole.TOOL }
                    val toolRes = toolMsg?.toolResults?.firstOrNull()
                    assertNotNull(toolRes)
                    assertEquals(false, toolRes?.content?.get("success"))
                    AiResponse(
                        isHandled = true,
                        replyText = "I cannot delete files as that capability is not registered."
                    )
                }
            }
        }

        val configRepo = FakeConfigRepo(AiConfig(apiKey = "valid_key_12345"))
        val orchestrator = AgentOrchestrator(
            cloudAiProvider = fakeCloudProvider,
            localCommandProcessor = localProcessor,
            aiConfigRepository = configRepo,
            toolRegistry = toolRegistry,
            actionPolicy = policy,
            commandDispatcher = dispatcher,
            chatRepository = fakeChatRepo,
            taskRepository = fakeTaskRepo
        )

        val result = orchestrator.execute("Delete everything on my phone")

        assertTrue(result is AgentExecutionResult.Success)
        val success = result as AgentExecutionResult.Success
        assertEquals("I cannot delete files as that capability is not registered.", success.assistantMessage.content)
    }

    @Test
    fun testToolIterationLimitProtection() = runBlocking {
        // Model keeps requesting tools endlessly in an infinite loop
        val fakeCloudProvider = object : AiProvider {
            override suspend fun generateResponse(request: AiRequest): AiResponse {
                return AiResponse(
                    isHandled = true,
                    toolCalls = listOf(AiToolCall(name = "get_battery_info"))
                )
            }
        }

        val configRepo = FakeConfigRepo(AiConfig(apiKey = "valid_key_12345", maxIterations = 3))
        val orchestrator = AgentOrchestrator(
            cloudAiProvider = fakeCloudProvider,
            localCommandProcessor = localProcessor,
            aiConfigRepository = configRepo,
            toolRegistry = toolRegistry,
            actionPolicy = policy,
            commandDispatcher = dispatcher,
            chatRepository = fakeChatRepo,
            taskRepository = fakeTaskRepo
        )

        val result = orchestrator.execute("Infinite loop query")

        assertTrue(result is AgentExecutionResult.Failure)
        val failure = result as AgentExecutionResult.Failure
        assertTrue(failure.errorMessage.contains("maximum iteration limit"))
    }

    @Test
    fun testUnconfiguredApiKeyReturnsHonestLimitation() = runBlocking {
        val fakeCloudProvider = object : AiProvider {
            override suspend fun generateResponse(request: AiRequest): AiResponse {
                throw AssertionError("Should not contact AI without API key")
            }
        }

        val configRepo = FakeConfigRepo(AiConfig(apiKey = "")) // Empty key
        val orchestrator = AgentOrchestrator(
            cloudAiProvider = fakeCloudProvider,
            localCommandProcessor = localProcessor,
            aiConfigRepository = configRepo,
            toolRegistry = toolRegistry,
            actionPolicy = policy,
            commandDispatcher = dispatcher,
            chatRepository = fakeChatRepo,
            taskRepository = fakeTaskRepo
        )

        val result = orchestrator.execute("Analyze my battery degradation curve")

        assertTrue(result is AgentExecutionResult.Failure)
        val failure = result as AgentExecutionResult.Failure
        assertEquals(AgentOrchestrator.NO_API_KEY_MESSAGE, failure.assistantMessage.content)
    }
}
