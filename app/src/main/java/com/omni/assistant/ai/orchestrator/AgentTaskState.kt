package com.omni.assistant.ai.orchestrator

import com.omni.assistant.domain.model.ExperienceRecord
import com.omni.assistant.domain.model.TaskStatus

data class AgentTaskState(
    val taskId: String,
    val originalRequest: String,
    val intendedOutcome: String,
    var status: TaskStatus = TaskStatus.RUNNING,
    var currentStep: Int = 0,
    val completedSteps: MutableList<String> = mutableListOf(),
    val observedStateTransitions: MutableList<String> = mutableListOf(),
    val executedActions: MutableList<String> = mutableListOf(),
    val failedActions: MutableList<String> = mutableListOf(),
    var lastObservedSummary: String? = null,
    var lastAction: String? = null,
    var lastActionResult: String? = null,
    var expectedNextState: String? = null,
    var retryCount: Int = 0,
    var isCancelled: Boolean = false,
    var pendingConfirmation: Boolean = false,
    var recalledStrategy: ExperienceRecord? = null,
    var finalResponseText: String? = null,
    var finalErrorText: String? = null
)
