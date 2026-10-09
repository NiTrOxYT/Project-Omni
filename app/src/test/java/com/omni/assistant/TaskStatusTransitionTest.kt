package com.omni.assistant

import com.omni.assistant.domain.model.TaskItem
import com.omni.assistant.domain.model.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class TaskStatusTransitionTest {

    @Test
    fun testTaskLifecycleTransitions() {
        val taskId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()

        // 1. Initial creation
        val taskPending = TaskItem(
            id = taskId,
            request = "Check my battery",
            status = TaskStatus.PENDING,
            createdAt = createdAt
        )
        assertEquals(TaskStatus.PENDING, taskPending.status)
        assertNull(taskPending.completedAt)
        assertNull(taskPending.durationMillis)

        // 2. Transition to Running
        val taskRunning = taskPending.copy(status = TaskStatus.RUNNING)
        assertEquals(TaskStatus.RUNNING, taskRunning.status)

        // 3. Transition to Succeeded
        val completedAt = createdAt + 150
        val taskSucceeded = taskRunning.copy(
            status = TaskStatus.SUCCEEDED,
            completedAt = completedAt,
            resultSummary = "Battery is 85% and charging",
            toolName = "get_battery_info"
        )
        assertEquals(TaskStatus.SUCCEEDED, taskSucceeded.status)
        assertEquals(completedAt, taskSucceeded.completedAt)
        assertEquals(150L, taskSucceeded.durationMillis)
        assertEquals("get_battery_info", taskSucceeded.toolName)
    }

    @Test
    fun testFailedTaskTransition() {
        val taskId = UUID.randomUUID().toString()
        val createdAt = System.currentTimeMillis()

        val taskFailed = TaskItem(
            id = taskId,
            request = "Unsupported request",
            status = TaskStatus.FAILED,
            createdAt = createdAt,
            completedAt = createdAt + 50,
            resultSummary = "Execution failed",
            errorDetails = "Command not supported in Phase 1"
        )

        assertEquals(TaskStatus.FAILED, taskFailed.status)
        assertEquals("Command not supported in Phase 1", taskFailed.errorDetails)
        assertNotNull(taskFailed.durationMillis)
    }
}
