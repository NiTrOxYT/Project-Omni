package com.omni.assistant.ai.orchestrator

import com.omni.assistant.ai.AiMessage
import com.omni.assistant.ai.AiProvider
import com.omni.assistant.ai.AiRequest
import com.omni.assistant.ai.AiRole
import com.omni.assistant.ai.AiToolResult
import com.omni.assistant.ai.LocalCommandProcessor
import com.omni.assistant.ai.config.AiConfigRepository
import com.omni.assistant.core.logging.OmniLogger
import com.omni.assistant.domain.model.ChatMessage
import com.omni.assistant.domain.model.MessageSender
import com.omni.assistant.domain.model.TaskItem
import com.omni.assistant.domain.model.TaskStatus
import com.omni.assistant.domain.repository.ChatRepository
import com.omni.assistant.domain.repository.TaskRepository
import com.omni.assistant.execution.CommandDispatcher
import com.omni.assistant.execution.ToolRegistry
import com.omni.assistant.security.ActionPolicy
import com.omni.assistant.security.PolicyDecision
import com.omni.assistant.ai.LocalPlanResult
import com.omni.assistant.ai.LocalTaskPlanner
import com.omni.assistant.ai.bridge.AiAppBridge
import com.omni.assistant.ai.bridge.AiBridgeQuery
import com.omni.assistant.ai.bridge.SupportedAiApp
import com.omni.assistant.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.coroutines.coroutineContext

sealed interface AgentExecutionResult {
    data object Empty : AgentExecutionResult
    data class Success(
        val taskId: String,
        val userMessage: ChatMessage,
        val assistantMessage: ChatMessage,
        val executedTools: List<String> = emptyList()
    ) : AgentExecutionResult
    data class Failure(
        val taskId: String,
        val userMessage: ChatMessage,
        val assistantMessage: ChatMessage,
        val errorMessage: String
    ) : AgentExecutionResult
    data class Cancelled(val taskId: String) : AgentExecutionResult
}

class AgentOrchestrator(
    private val cloudAiProvider: AiProvider,
    private val localTaskPlanner: LocalTaskPlanner,
    private val aiAppBridge: AiAppBridge,
    private val settingsRepository: SettingsRepository,
    private val aiConfigRepository: AiConfigRepository,
    private val toolRegistry: ToolRegistry,
    private val actionPolicy: ActionPolicy,
    private val commandDispatcher: CommandDispatcher,
    private val chatRepository: ChatRepository,
    private val taskRepository: TaskRepository
) {
    private val tag = "AgentOrchestrator"

    companion object {
        const val MAX_CONTEXT_MESSAGES = 12
        const val EXECUTION_TIMEOUT_MS = 60_000L
    }

    suspend fun execute(
        rawInput: String,
        onProgress: ((statusMessage: String) -> Unit)? = null
    ): AgentExecutionResult {
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) return AgentExecutionResult.Empty

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

        // 2. Create Task Record (PENDING -> RUNNING)
        val initialTask = TaskItem(
            id = taskId,
            request = trimmed,
            status = TaskStatus.PENDING,
            createdAt = timestamp
        )
        taskRepository.insertTask(initialTask)
        taskRepository.updateTaskStatus(taskId, TaskStatus.RUNNING)

        try {
            return withTimeout(EXECUTION_TIMEOUT_MS) {
                val preferredAiApp = settingsRepository.getPreferredAiApp().firstOrNull() ?: SupportedAiApp.AUTO

                // 3. Local Task Planner Execution (Zero API Key required)
                val planResult = localTaskPlanner.plan(trimmed, preferredAiApp)

                when (planResult) {
                    is LocalPlanResult.DirectToolExecution -> {
                        OmniLogger.i(tag, "Executing via direct local tool path: ${planResult.toolName}")
                        onProgress?.invoke(planResult.description)

                        val toolResult = commandDispatcher.dispatch(planResult.toolName, planResult.arguments)
                        val completionTime = System.currentTimeMillis()
                        val isSuccess = toolResult.success
                        val finalStatus = if (isSuccess) TaskStatus.SUCCEEDED else TaskStatus.FAILED

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
                            isError = !isSuccess
                        )
                        chatRepository.insertMessage(assistantMessage)

                        return@withTimeout if (isSuccess) {
                            AgentExecutionResult.Success(
                                taskId = taskId,
                                userMessage = userMessage,
                                assistantMessage = assistantMessage,
                                executedTools = listOf(planResult.toolName)
                            )
                        } else {
                            AgentExecutionResult.Failure(
                                taskId = taskId,
                                userMessage = userMessage,
                                assistantMessage = assistantMessage,
                                errorMessage = toolResult.error ?: "Tool execution failed"
                            )
                        }
                    }

                    is LocalPlanResult.NeedsAiBridge -> {
                        val config = aiConfigRepository.getCurrentConfig()

                        // If user explicitly configured and requested Legacy Cloud Gemini, use cloud loop
                        if (config.useCloudAi && config.hasValidApiKey) {
                            OmniLogger.i(tag, "Using explicitly configured legacy cloud AI provider")
                            // Falls through to Cloud AI loop below
                        } else {
                            // Default: On-device AI App Bridge (ChatGPT / Gemini app)
                            OmniLogger.i(tag, "Delegating to on-device AI App Bridge: ${planResult.targetApp}")
                            onProgress?.invoke("Delegating to installed AI app...")

                            val bridgeResponse = aiAppBridge.askInstalledAiApp(
                                query = AiBridgeQuery(
                                    prompt = planResult.prompt,
                                    targetApp = planResult.targetApp,
                                    returnToPackage = "com.omni.assistant.debug"
                                ),
                                onProgress = onProgress
                            )

                            val completionTime = System.currentTimeMillis()
                            val isSuccess = bridgeResponse.success
                            val responseText = if (isSuccess) {
                                "[${bridgeResponse.appUsed}]\n${bridgeResponse.responseText}"
                            } else {
                                bridgeResponse.error ?: "Failed to receive response from AI application."
                            }

                            taskRepository.updateTaskStatus(
                                taskId = taskId,
                                status = if (isSuccess) TaskStatus.SUCCEEDED else TaskStatus.FAILED,
                                completedAt = completionTime,
                                resultSummary = responseText,
                                errorDetails = if (!isSuccess) bridgeResponse.error else null
                            )

                            val assistantMessage = ChatMessage(
                                id = UUID.randomUUID().toString(),
                                sender = MessageSender.ASSISTANT,
                                content = responseText,
                                timestamp = completionTime,
                                taskId = taskId,
                                isError = !isSuccess
                            )
                            chatRepository.insertMessage(assistantMessage)

                            return@withTimeout if (isSuccess) {
                                AgentExecutionResult.Success(
                                    taskId = taskId,
                                    userMessage = userMessage,
                                    assistantMessage = assistantMessage,
                                    executedTools = listOf("ai_app_bridge")
                                )
                            } else {
                                AgentExecutionResult.Failure(
                                    taskId = taskId,
                                    userMessage = userMessage,
                                    assistantMessage = assistantMessage,
                                    errorMessage = bridgeResponse.error ?: "AI App Bridge failed"
                                )
                            }
                        }
                    }

                    is LocalPlanResult.Ambiguous -> {
                        val completionTime = System.currentTimeMillis()
                        val assistantMessage = ChatMessage(
                            id = UUID.randomUUID().toString(),
                            sender = MessageSender.ASSISTANT,
                            content = planResult.clarificationMessage,
                            timestamp = completionTime,
                            taskId = taskId,
                            isError = false
                        )
                        chatRepository.insertMessage(assistantMessage)
                        return@withTimeout AgentExecutionResult.Success(
                            taskId = taskId,
                            userMessage = userMessage,
                            assistantMessage = assistantMessage
                        )
                    }
                }

                // 5. Multi-turn Agent Execution Loop
                onProgress?.invoke("Understanding your request...")
                val toolSchemas = toolRegistry.getAllDefinitions().map { it.toAiSchema() }

                // Load recent context
                val recentMessages = chatRepository.getMessages().firstOrNull()?.takeLast(MAX_CONTEXT_MESSAGES) ?: emptyList()
                val history = recentMessages.filter { it.id != userMessage.id }.map { msg ->
                    AiMessage(
                        role = if (msg.sender == MessageSender.USER) AiRole.USER else AiRole.MODEL,
                        text = msg.content
                    )
                }.toMutableList()

                // Append the current user request turn
                history.add(AiMessage(role = AiRole.USER, text = trimmed))

                val executedTools = mutableListOf<String>()
                val config = aiConfigRepository.getConfig().firstOrNull() ?: com.omni.assistant.ai.config.AiConfig()
                val maxIterations = config.maxIterations.coerceIn(1, 10)
                var currentIteration = 0
                var finalAnswerText: String? = null
                var hasError = false
                var errorSummary: String? = null
                var consecutiveFailures = 0
                val maxConsecutiveFailures = 3

                while (currentIteration < maxIterations) {
                    coroutineContext.ensureActive()
                    currentIteration++
                    OmniLogger.d(tag, "Agent iteration $currentIteration of $maxIterations")

                    val aiRequest = AiRequest(
                        prompt = "",
                        conversationHistory = history,
                        tools = toolSchemas,
                        systemInstruction = "You are Omni, a personal AI assistant on Android. Answer user queries by selecting and invoking available tools. You have access to device info tools and accessibility automation tools (get_accessibility_status, get_ui_snapshot, launch_app, tap_element, enter_text, scroll_ui, navigate_back). For UI automation: 1) Verify accessibility status if needed, 2) Launch target app if needed, 3) Inspect screen with get_ui_snapshot, 4) Interact using snapshot element IDs (e.g. 'e1'), 5) Verify outcome using the fresh snapshot returned, 6) Stop safely and formulate concise, helpful final responses grounded in tool results."
                    )

                    val aiResponse = cloudAiProvider.generateResponse(aiRequest)

                    if (!aiResponse.isHandled) {
                        hasError = true
                        errorSummary = aiResponse.replyText ?: "Provider returned an unhandled response."
                        break
                    }

                    // Check if model requested tool calls
                    if (aiResponse.toolCalls.isNotEmpty()) {
                        val toolResults = mutableListOf<AiToolResult>()

                        for (toolCall in aiResponse.toolCalls) {
                            coroutineContext.ensureActive()
                            val toolName = toolCall.name
                            OmniLogger.i(tag, "Model requested tool: $toolName with args: ${toolCall.arguments}")

                            val registeredTool = toolRegistry.getTool(toolName)
                            if (registeredTool == null) {
                                OmniLogger.w(tag, "Rejecting unknown tool: $toolName")
                                toolResults.add(
                                    AiToolResult(
                                        toolCallId = toolCall.id,
                                        toolName = toolName,
                                        content = mapOf(
                                            "success" to false,
                                            "error" to "Tool '$toolName' is not registered or supported."
                                        )
                                    )
                                )
                                continue
                            }

                            // Evaluate policy
                            val policy = actionPolicy.evaluate(registeredTool.definition, toolCall.arguments)
                            if (policy is PolicyDecision.Deny) {
                                OmniLogger.w(tag, "Tool execution denied by policy: ${policy.reason}")
                                toolResults.add(
                                    AiToolResult(
                                        toolCallId = toolCall.id,
                                        toolName = toolName,
                                        content = mapOf(
                                            "success" to false,
                                            "error" to "Action denied by security policy: ${policy.reason}"
                                        )
                                    )
                                )
                                continue
                            }

                            onProgress?.invoke("Executing ${toolName.replace('_', ' ')}...")
                            executedTools.add(toolName)

                            val executionResult = commandDispatcher.dispatch(toolName, toolCall.arguments)
                            toolResults.add(
                                AiToolResult(
                                    toolCallId = toolCall.id,
                                    toolName = toolName,
                                    content = executionResult.data ?: mapOf(
                                        "success" to executionResult.success,
                                        "summary" to executionResult.summaryText,
                                        "error" to executionResult.error
                                    )
                                )
                            )
                        }

                        val anySuccess = toolResults.any { 
                            val content = it.content
                            (content["success"] as? Boolean) != false && content["error"] == null
                        }
                        if (anySuccess) {
                            consecutiveFailures = 0
                        } else {
                            consecutiveFailures++
                        }

                        // Add model turn with tool calls and tool responses to history
                        history.add(
                            AiMessage(
                                role = AiRole.MODEL,
                                text = aiResponse.replyText,
                                toolCalls = aiResponse.toolCalls,
                                rawContentJson = aiResponse.rawContentJson
                            )
                        )
                        history.add(
                            AiMessage(
                                role = AiRole.TOOL,
                                toolResults = toolResults
                            )
                        )

                        if (consecutiveFailures >= maxConsecutiveFailures) {
                            hasError = true
                            errorSummary = "Stopped automation: $maxConsecutiveFailures consecutive action failures occurred."
                            break
                        }

                        onProgress?.invoke("Synthesizing results...")
                        // Continue loop to receive grounded final response
                    } else if (!aiResponse.replyText.isNullOrBlank()) {
                        finalAnswerText = aiResponse.replyText
                        break
                    } else {
                        hasError = true
                        errorSummary = "AI returned an empty response without tool calls."
                        break
                    }
                }

                if (finalAnswerText == null && currentIteration >= maxIterations) {
                    hasError = true
                    errorSummary = "Agent reached maximum iteration limit ($maxIterations steps)."
                }

                val completionTime = System.currentTimeMillis()
                val responseContent = finalAnswerText ?: errorSummary ?: "Operation concluded without output."
                val isSuccess = !hasError && finalAnswerText != null

                taskRepository.updateTaskStatus(
                    taskId = taskId,
                    status = if (isSuccess) TaskStatus.SUCCEEDED else TaskStatus.FAILED,
                    completedAt = completionTime,
                    resultSummary = if (isSuccess) responseContent else "Execution failed",
                    errorDetails = if (!isSuccess) errorSummary else null
                )

                val assistantMessage = ChatMessage(
                    id = UUID.randomUUID().toString(),
                    sender = MessageSender.ASSISTANT,
                    content = responseContent,
                    timestamp = completionTime,
                    taskId = taskId,
                    isError = !isSuccess
                )
                chatRepository.insertMessage(assistantMessage)

                if (isSuccess) {
                    AgentExecutionResult.Success(
                        taskId = taskId,
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        executedTools = executedTools
                    )
                } else {
                    AgentExecutionResult.Failure(
                        taskId = taskId,
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        errorMessage = errorSummary ?: "Unknown failure"
                    )
                }
            }
        } catch (e: CancellationException) {
            OmniLogger.i(tag, "Agent execution cancelled by user")
            val completionTime = System.currentTimeMillis()
            taskRepository.updateTaskStatus(
                taskId = taskId,
                status = TaskStatus.CANCELLED,
                completedAt = completionTime,
                resultSummary = "Task cancelled by user"
            )
            val assistantMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.ASSISTANT,
                content = "Operation was cancelled.",
                timestamp = completionTime,
                taskId = taskId,
                isError = false
            )
            chatRepository.insertMessage(assistantMessage)
            return AgentExecutionResult.Cancelled(taskId)
        } catch (e: Exception) {
            OmniLogger.e(tag, "Unexpected error during agent orchestration", e)
            val completionTime = System.currentTimeMillis()
            val errorMsg = e.localizedMessage ?: "Timeout or unexpected internal error"
            taskRepository.updateTaskStatus(
                taskId = taskId,
                status = TaskStatus.FAILED,
                completedAt = completionTime,
                resultSummary = "Execution encountered an error",
                errorDetails = errorMsg
            )
            val assistantMessage = ChatMessage(
                id = UUID.randomUUID().toString(),
                sender = MessageSender.ASSISTANT,
                content = "Error: $errorMsg",
                timestamp = completionTime,
                taskId = taskId,
                isError = true
            )
            chatRepository.insertMessage(assistantMessage)
            return AgentExecutionResult.Failure(
                taskId = taskId,
                userMessage = userMessage,
                assistantMessage = assistantMessage,
                errorMessage = errorMsg
            )
        }
    }
}
