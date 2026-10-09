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
    private val localCommandProcessor: LocalCommandProcessor,
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
        const val EXECUTION_TIMEOUT_MS = 45_000L
        const val NO_API_KEY_MESSAGE =
            "No cloud AI API key is configured. You can configure your Gemini API key in Settings, or use local device commands (e.g. 'Check my battery', 'Check my storage', 'Show device information')."
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
                // 3. Check Deterministic Local Fast Path first
                val normalized = LocalCommandProcessor.normalize(trimmed)
                val matchedLocalTool = localCommandProcessor.matchCommandToTool(normalized)

                if (matchedLocalTool != null) {
                    OmniLogger.i(tag, "Executing via deterministic local path: $matchedLocalTool")
                    onProgress?.invoke("Executing ${matchedLocalTool.replace('_', ' ')}...")

                    val toolResult = commandDispatcher.dispatch(matchedLocalTool)
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
                            executedTools = listOf(matchedLocalTool)
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

                // 4. Cloud AI Agent Execution Loop
                val config = aiConfigRepository.getCurrentConfig()

                if (!config.useCloudAi || !config.hasValidApiKey) {
                    OmniLogger.i(tag, "Cloud AI unavailable or unconfigured; falling back to limitation response")
                    val completionTime = System.currentTimeMillis()

                    taskRepository.updateTaskStatus(
                        taskId = taskId,
                        status = TaskStatus.FAILED,
                        completedAt = completionTime,
                        resultSummary = "Cloud AI not configured",
                        errorDetails = "Missing Gemini API key."
                    )

                    val assistantMessage = ChatMessage(
                        id = UUID.randomUUID().toString(),
                        sender = MessageSender.ASSISTANT,
                        content = NO_API_KEY_MESSAGE,
                        timestamp = completionTime,
                        taskId = taskId,
                        isError = false
                    )
                    chatRepository.insertMessage(assistantMessage)

                    return@withTimeout AgentExecutionResult.Failure(
                        taskId = taskId,
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        errorMessage = "API key not configured"
                    )
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
                val maxIterations = config.maxIterations.coerceIn(1, 10)
                var currentIteration = 0
                var finalAnswerText: String? = null
                var hasError = false
                var errorSummary: String? = null

                while (currentIteration < maxIterations) {
                    coroutineContext.ensureActive()
                    currentIteration++
                    OmniLogger.d(tag, "Agent iteration $currentIteration of $maxIterations")

                    val aiRequest = AiRequest(
                        prompt = "",
                        conversationHistory = history,
                        tools = toolSchemas,
                        systemInstruction = "You are Omni, a personal AI assistant on Android. Answer user queries by selecting and invoking available tools. Formulate concise, helpful final responses grounded in the tool results."
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
