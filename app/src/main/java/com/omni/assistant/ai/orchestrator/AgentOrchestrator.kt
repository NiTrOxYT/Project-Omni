package com.omni.assistant.ai.orchestrator

import com.omni.assistant.accessibility.AccessibilityConnectionManager
import com.omni.assistant.accessibility.lifecycle.SnapshotLifecycleManager
import com.omni.assistant.accessibility.observation.ObservationClassifier
import com.omni.assistant.accessibility.observation.ScreenObservation
import com.omni.assistant.ai.AiProvider
import com.omni.assistant.ai.LocalPlanResult
import com.omni.assistant.ai.LocalTaskPlanner
import com.omni.assistant.ai.bridge.AiAppBridge
import com.omni.assistant.ai.bridge.AiBridgeQuery
import com.omni.assistant.ai.bridge.SupportedAiApp
import com.omni.assistant.ai.config.AiConfigRepository
import com.omni.assistant.core.logging.OmniLogger
import com.omni.assistant.domain.model.ChatMessage
import com.omni.assistant.domain.model.ExperienceOutcome
import com.omni.assistant.domain.model.ExperienceRecord
import com.omni.assistant.domain.model.MessageSender
import com.omni.assistant.domain.model.TaskItem
import com.omni.assistant.domain.model.TaskStatus
import com.omni.assistant.domain.repository.ChatRepository
import com.omni.assistant.domain.repository.ExperienceRepository
import com.omni.assistant.domain.repository.SettingsRepository
import com.omni.assistant.domain.repository.TaskRepository
import com.omni.assistant.execution.CommandDispatcher
import com.omni.assistant.execution.ToolRegistry
import com.omni.assistant.security.ActionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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
    private val taskRepository: TaskRepository,
    private val experienceRepository: ExperienceRepository,
    private val snapshotLifecycleManager: SnapshotLifecycleManager
) {
    private val tag = "AgentOrchestrator"

    companion object {
        const val MAX_EXECUTION_STEPS = 8
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

        // Create temporary task-scoped workspace on disk
        snapshotLifecycleManager.getTaskWorkspace(taskId)

        val taskCategory = localTaskPlanner.extractTaskCategory(trimmed)
        val intendedOutcome = localTaskPlanner.extractIntendedOutcome(trimmed)
        val taskState = AgentTaskState(
            taskId = taskId,
            originalRequest = trimmed,
            intendedOutcome = intendedOutcome
        )

        try {
            return withTimeout(EXECUTION_TIMEOUT_MS) {
                val preferredAiApp = settingsRepository.getPreferredAiApp().firstOrNull() ?: SupportedAiApp.AUTO

                // UNDERSTAND & RECALL
                onProgress?.invoke("Understanding request & recalling experience...")
                val initialSnapshot = AccessibilityConnectionManager.takeSnapshot()
                val initialObservation = ObservationClassifier.classify(initialSnapshot)
                taskState.lastObservedSummary = initialObservation.compactSummary
                taskState.observedStateTransitions.add(initialObservation.toStateKey())

                val initialStateKey = localTaskPlanner.extractGeneralizedInitialState(trimmed, initialObservation.packageName)
                val recalled = experienceRepository.recallStrategy(taskCategory, initialStateKey)
                    ?: experienceRepository.recallCategoryStrategies(taskCategory).firstOrNull()
                taskState.recalledStrategy = recalled
                if (recalled != null) {
                    OmniLogger.i(tag, "Recalled previous strategy with ${recalled.successCount} successes.")
                    onProgress?.invoke("Recalled learned strategy for $taskCategory...")
                }

                var isDone = false

                // REUSABLE AGENT LOOP
                while (taskState.currentStep < MAX_EXECUTION_STEPS && !isDone) {
                    taskState.currentStep++
                    coroutineContext.ensureActive()

                    val currentStep = taskState.currentStep
                    var nextToolName: String? = null
                    var nextToolArgs: Map<String, Any?> = emptyMap()

                    // Step 1: LocalTaskPlanner takes priority
                    if (currentStep == 1) {
                        val planResult = localTaskPlanner.plan(trimmed, preferredAiApp)
                        when (planResult) {
                            is LocalPlanResult.UserConfirmation -> {
                                val recentTasks = taskRepository.getRecentTasks(3).firstOrNull() ?: emptyList()
                                val pendingTask = recentTasks.find { it.status == TaskStatus.AWAITING_CONFIRMATION }

                                if (pendingTask != null) {
                                    if (planResult.isConfirmed) {
                                        onProgress?.invoke("Authorizing and sending message...")
                                        val messagingRequest = com.omni.assistant.automation.messaging.MessagingCommandParser.parse(pendingTask.request)
                                        if (messagingRequest != null) {
                                            nextToolName = "send_whatsapp_message"
                                            nextToolArgs = mapOf(
                                                "recipient" to messagingRequest.recipient,
                                                "message" to messagingRequest.messageText,
                                                "autoSend" to true
                                            )
                                        } else {
                                            taskState.finalResponseText = "Confirmed pending task '${pendingTask.request}'."
                                            taskState.status = TaskStatus.SUCCEEDED
                                            isDone = true
                                        }
                                    } else {
                                        onProgress?.invoke("Cancelling pending action...")
                                        taskRepository.updateTaskStatus(
                                            taskId = pendingTask.id,
                                            status = TaskStatus.CANCELLED,
                                            completedAt = System.currentTimeMillis(),
                                            resultSummary = "Task cancelled by user"
                                        )
                                        taskState.finalResponseText = "Pending action for '${pendingTask.request}' was cancelled."
                                        taskState.status = TaskStatus.CANCELLED
                                        isDone = true
                                    }
                                } else {
                                    taskState.finalResponseText = if (planResult.isConfirmed) "Acknowledged." else "Cancelled."
                                    taskState.status = TaskStatus.SUCCEEDED
                                    isDone = true
                                }
                            }

                            is LocalPlanResult.Ambiguous -> {
                                taskState.finalResponseText = planResult.clarificationMessage
                                taskState.status = TaskStatus.SUCCEEDED
                                isDone = true
                            }

                            is LocalPlanResult.DirectToolExecution -> {
                                nextToolName = planResult.toolName
                                var effectiveArgs = planResult.arguments
                                if (nextToolName == "send_whatsapp_message" && !effectiveArgs.containsKey("autoSend")) {
                                    val isAutoSend = settingsRepository.isMessagingAutoSendEnabled().firstOrNull() ?: false
                                    effectiveArgs = effectiveArgs + ("autoSend" to isAutoSend)
                                }
                                nextToolArgs = effectiveArgs
                                onProgress?.invoke(planResult.description)
                            }

                            is LocalPlanResult.NeedsAiBridge -> {
                                if (planResult.isDirectQuery) {
                                    val targetApp = if (planResult.targetApp != SupportedAiApp.AUTO) planResult.targetApp else preferredAiApp
                                    onProgress?.invoke("Asking ${targetApp.displayName}...")
                                    val bridgeResponse = aiAppBridge.askInstalledAiApp(
                                        query = AiBridgeQuery(
                                            prompt = planResult.prompt,
                                            targetApp = targetApp,
                                            returnToPackage = "com.omni.assistant.debug"
                                        ),
                                        onProgress = onProgress
                                    )
                                    if (bridgeResponse.success) {
                                        taskState.finalResponseText = bridgeResponse.responseText ?: "Received response from ${targetApp.displayName}."
                                        taskState.status = TaskStatus.SUCCEEDED
                                    } else {
                                        taskState.finalErrorText = bridgeResponse.error ?: "Failed to get response from ${targetApp.displayName}."
                                        taskState.finalResponseText = taskState.finalErrorText
                                        taskState.status = TaskStatus.FAILED
                                    }
                                    isDone = true
                                }
                            }
                        }
                    }

                    if (isDone) break

                    // Step > 1 or NeedsAiBridge: Screen Observation + Reasoning
                    if (nextToolName == null) {
                        onProgress?.invoke("Observing screen state...")
                        val liveSnapshot = AccessibilityConnectionManager.takeSnapshot()
                        val liveObservation = ObservationClassifier.classify(liveSnapshot)
                        taskState.lastObservedSummary = liveObservation.compactSummary
                        taskState.observedStateTransitions.add(liveObservation.toStateKey())

                        val reasoningPrompt = buildReasoningPrompt(
                            goal = trimmed,
                            observation = liveObservation,
                            stepCount = currentStep,
                            executedTools = taskState.executedActions,
                            recalledStrategy = taskState.recalledStrategy
                        )

                        onProgress?.invoke("Reasoning with on-device AI...")
                        val bridgeResponse = aiAppBridge.askInstalledAiApp(
                            query = AiBridgeQuery(
                                prompt = reasoningPrompt,
                                targetApp = preferredAiApp,
                                returnToPackage = "com.omni.assistant.debug"
                            ),
                            onProgress = onProgress
                        )

                        if (!bridgeResponse.success) {
                            taskState.status = TaskStatus.FAILED
                            taskState.finalErrorText = bridgeResponse.error ?: "AI reasoning application could not be reached."
                            taskState.finalResponseText = taskState.finalErrorText
                            isDone = true
                            break
                        }

                        val rawAiText = bridgeResponse.responseText ?: ""
                        val proposal = AiActionParser.parse(rawAiText)

                        if (proposal == null || proposal.action == "finish") {
                            taskState.finalResponseText = proposal?.message ?: rawAiText.ifBlank { "Operation completed successfully." }
                            taskState.status = TaskStatus.SUCCEEDED
                            isDone = true
                            break
                        }

                        nextToolName = proposal.action
                        nextToolArgs = proposal.arguments
                    }

                    // Check tool registration
                    val toolToRun = toolRegistry.getTool(nextToolName)
                    if (toolToRun == null) {
                        OmniLogger.w(tag, "Unrecognized tool '$nextToolName'.")
                        taskState.finalResponseText = "Unrecognized tool '$nextToolName'."
                        taskState.status = TaskStatus.FAILED
                        isDone = true
                        break
                    }

                    // ACT
                    taskState.lastAction = nextToolName
                    onProgress?.invoke("Executing ${nextToolName.replace('_', ' ')}...")
                    val toolResult = commandDispatcher.dispatch(nextToolName, nextToolArgs)
                    taskState.executedActions.add(nextToolName)
                    taskState.lastActionResult = toolResult.summaryText

                    // Check for Confirmation State
                    if (toolResult.data?.get("state") == "AWAITING_CONFIRMATION") {
                        taskState.status = TaskStatus.AWAITING_CONFIRMATION
                        taskState.finalResponseText = toolResult.summaryText
                        taskState.pendingConfirmation = true
                        isDone = true
                        break
                    }

                    // OBSERVE AGAIN & VERIFY
                    delay(800)
                    val postSnapshot = AccessibilityConnectionManager.takeSnapshot()
                    val postObservation = ObservationClassifier.classify(postSnapshot)
                    taskState.observedStateTransitions.add(postObservation.toStateKey())

                    if (!toolResult.success) {
                        taskState.failedActions.add(nextToolName)
                        OmniLogger.w(tag, "Step $currentStep tool '$nextToolName' failed: ${toolResult.error}")
                        if (taskState.retryCount >= 2 || currentStep >= MAX_EXECUTION_STEPS) {
                            taskState.status = TaskStatus.FAILED
                            taskState.finalErrorText = toolResult.error ?: toolResult.summaryText
                            taskState.finalResponseText = taskState.finalErrorText
                            isDone = true
                            break
                        } else {
                            taskState.retryCount++
                        }
                    } else {
                        // Check if task completed
                        val isSelfTerminating = nextToolName in listOf(
                            "send_whatsapp_message", "get_battery_info", "get_device_info",
                            "get_storage_info", "get_app_version", "get_accessibility_status", "get_ui_snapshot"
                        )
                        val isMultiStep = trimmed.contains(" and ", ignoreCase = true) || trimmed.contains(" then ", ignoreCase = true)

                        if (isSelfTerminating || (!isMultiStep && currentStep == 1 && nextToolName != "launch_app")) {
                            taskState.finalResponseText = toolResult.summaryText
                            taskState.status = TaskStatus.SUCCEEDED
                            isDone = true
                            break
                        }
                    }
                }

                // LEARN from execution (Both Success and Failure)
                val isSuccess = taskState.status == TaskStatus.SUCCEEDED || taskState.status == TaskStatus.AWAITING_CONFIRMATION
                val experience = ExperienceRecord(
                    id = UUID.randomUUID().toString(),
                    taskCategory = taskCategory,
                    generalizedInitialState = initialStateKey,
                    intendedOutcome = intendedOutcome,
                    actionsAttempted = taskState.executedActions.toList(),
                    stateTransitions = taskState.observedStateTransitions.toList(),
                    successfulActions = if (isSuccess) taskState.executedActions.toList() else emptyList(),
                    failedActions = taskState.failedActions.toList(),
                    finalOutcome = if (isSuccess) ExperienceOutcome.SUCCESS else ExperienceOutcome.FAILURE,
                    failureCategory = taskState.finalErrorText,
                    executionDurationMs = System.currentTimeMillis() - timestamp
                )
                experienceRepository.recordExperience(experience)

                val completionTime = System.currentTimeMillis()
                val responseContent = taskState.finalResponseText ?: taskState.finalErrorText ?: "Task completed."

                taskRepository.updateTaskStatus(
                    taskId = taskId,
                    status = taskState.status,
                    completedAt = completionTime,
                    resultSummary = responseContent,
                    errorDetails = taskState.finalErrorText
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
                        executedTools = taskState.executedActions
                    )
                } else {
                    AgentExecutionResult.Failure(
                        taskId = taskId,
                        userMessage = userMessage,
                        assistantMessage = assistantMessage,
                        errorMessage = taskState.finalErrorText ?: "Task failed"
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

            // Learn cancellation
            experienceRepository.recordExperience(
                ExperienceRecord(
                    id = UUID.randomUUID().toString(),
                    taskCategory = taskCategory,
                    generalizedInitialState = localTaskPlanner.extractGeneralizedInitialState(trimmed, null),
                    intendedOutcome = intendedOutcome,
                    actionsAttempted = taskState.executedActions.toList(),
                    stateTransitions = taskState.observedStateTransitions.toList(),
                    successfulActions = emptyList(),
                    failedActions = emptyList(),
                    finalOutcome = ExperienceOutcome.CANCELLED,
                    executionDurationMs = System.currentTimeMillis() - timestamp
                )
            )

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
        } finally {
            // CLEAN UP: Purge temporary task workspace and transient memory snapshots
            snapshotLifecycleManager.cleanupTaskWorkspace(taskId)
            AccessibilityConnectionManager.clearSnapshots()
        }
    }

    private fun buildReasoningPrompt(
        goal: String,
        observation: ScreenObservation,
        stepCount: Int,
        executedTools: List<String>,
        recalledStrategy: ExperienceRecord?
    ): String {
        val elementList = observation.rawSnapshot.elements.take(25).joinToString("\n") { elem ->
            val desc = elem.text ?: elem.contentDescription ?: ""
            "- [${elem.id}] class=${elem.className.substringAfterLast('.')} text=\"$desc\" bounds=${elem.bounds.toCompactString()}"
        }
        val toolDefs = toolRegistry.getAllDefinitions().joinToString("\n") { def ->
            val params = def.parameters.joinToString(", ") { "${it.name}: ${it.type}" }
            "- ${def.name}($params): ${def.description}"
        }

        val strategyHint = if (recalledStrategy != null && recalledStrategy.successfulActions.isNotEmpty()) {
            "\nPrior verified successful action sequence for this category: [${recalledStrategy.successfulActions.joinToString(" -> ")}]"
        } else ""

        return """
        Goal: $goal$strategyHint
        Step: $stepCount (Completed so far: ${executedTools.joinToString(", ").ifBlank { "None" }})
        Current Screen Observation: ${observation.compactSummary}
        Visible Screen Elements:
        $elementList

        Available Tools:
        $toolDefs

        Instructions:
        If you want to perform an action on screen, respond with ONLY valid JSON:
        {"thought": "reasoning", "action": "tool_name", "arguments": {"param": "value"}}

        If the goal is finished or you are answering the user, respond with ONLY valid JSON:
        {"thought": "reasoning", "action": "finish", "message": "your final response"}
        """.trimIndent()
    }
}
