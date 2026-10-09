package com.omni.assistant

import com.omni.assistant.domain.model.RiskLevel
import com.omni.assistant.execution.CommandDispatcher
import com.omni.assistant.execution.OmniTool
import com.omni.assistant.execution.ToolDefinition
import com.omni.assistant.execution.ToolRegistry
import com.omni.assistant.execution.ToolResult
import com.omni.assistant.security.PolicyDecision
import com.omni.assistant.security.StandardActionPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolRegistryAndExecutionTest {

    private lateinit var registry: ToolRegistry
    private lateinit var policy: StandardActionPolicy
    private lateinit var dispatcher: CommandDispatcher

    @Before
    fun setUp() {
        registry = ToolRegistry()
        policy = StandardActionPolicy()
        dispatcher = CommandDispatcher(registry, policy)
    }

    @Test
    fun testToolRegistrationAndLookup() {
        val dummyTool = object : OmniTool {
            override val definition = ToolDefinition(
                name = "test_tool",
                description = "A test tool",
                riskLevel = RiskLevel.LOW
            )

            override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
                return ToolResult(
                    success = true,
                    tool = definition.name,
                    summaryText = "Test executed successfully",
                    data = mapOf("result" to "ok")
                )
            }
        }

        registry.register(dummyTool)

        assertNotNull(registry.getTool("test_tool"))
        assertNotNull(registry.getTool("TEST_TOOL")) // Case insensitive
        assertEquals(1, registry.getAllTools().size)
    }

    @Test
    fun testToolDispatchSuccess() = runBlocking {
        val dummyTool = object : OmniTool {
            override val definition = ToolDefinition(
                name = "success_tool",
                description = "Success tool",
                riskLevel = RiskLevel.LOW
            )

            override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
                return ToolResult(
                    success = true,
                    tool = definition.name,
                    summaryText = "Done",
                    data = mapOf("status" to "healthy")
                )
            }
        }

        registry.register(dummyTool)

        val result = dispatcher.dispatch("success_tool")
        assertTrue(result.success)
        assertEquals("success_tool", result.tool)
        assertEquals("Done", result.summaryText)
        assertEquals("healthy", result.data?.get("status"))
        assertNull(result.error)
    }

    @Test
    fun testToolDispatchNotFound() = runBlocking {
        val result = dispatcher.dispatch("non_existent_tool")
        assertFalse(result.success)
        assertEquals("ToolNotFound", result.error)
    }

    @Test
    fun testToolDispatchPolicyDenial() = runBlocking {
        val criticalTool = object : OmniTool {
            override val definition = ToolDefinition(
                name = "critical_tool",
                description = "Dangerous operation",
                riskLevel = RiskLevel.CRITICAL
            )

            override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
                return ToolResult(
                    success = true,
                    tool = definition.name,
                    summaryText = "Executed dangerous operation"
                )
            }
        }

        registry.register(criticalTool)

        val result = dispatcher.dispatch("critical_tool")
        assertFalse(result.success)
        assertEquals("PolicyDenial", result.error)
    }

    @Test
    fun testToolFailureHandling() = runBlocking {
        val failingTool = object : OmniTool {
            override val definition = ToolDefinition(
                name = "failing_tool",
                description = "Throws exception",
                riskLevel = RiskLevel.LOW
            )

            override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
                throw IllegalStateException("Hardware communication timeout")
            }
        }

        registry.register(failingTool)

        val result = dispatcher.dispatch("failing_tool")
        assertFalse(result.success)
        assertTrue(result.error?.contains("Hardware communication timeout") == true || result.summaryText.contains("Hardware communication timeout"))
    }
}
