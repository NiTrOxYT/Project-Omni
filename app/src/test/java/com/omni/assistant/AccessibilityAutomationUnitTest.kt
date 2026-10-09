package com.omni.assistant

import com.omni.assistant.accessibility.AccessibilityConnectionManager
import com.omni.assistant.accessibility.AccessibilitySnapshotEngine
import com.omni.assistant.accessibility.UiBounds
import com.omni.assistant.accessibility.UiElement
import com.omni.assistant.accessibility.UiSnapshot
import com.omni.assistant.domain.model.RiskLevel
import com.omni.assistant.execution.ToolParameter
import com.omni.assistant.execution.ToolRegistry
import com.omni.assistant.execution.tools.EnterTextTool
import com.omni.assistant.execution.tools.GetAccessibilityStatusTool
import com.omni.assistant.execution.tools.GetUiSnapshotTool
import com.omni.assistant.execution.tools.LaunchAppTool
import com.omni.assistant.execution.tools.NavigateBackTool
import com.omni.assistant.execution.tools.ScrollUiTool
import com.omni.assistant.execution.tools.TapElementTool
import com.omni.assistant.security.ActionPolicy
import com.omni.assistant.security.PolicyDecision
import com.omni.assistant.security.StandardActionPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityAutomationUnitTest {

    @Test
    fun testUiElementCompactMapping() {
        val element = UiElement(
            id = "e1",
            className = "android.widget.Button",
            text = "Wi-Fi",
            contentDescription = "Wi-Fi toggle switch",
            isClickable = true,
            isEditable = false,
            isScrollable = false,
            isEnabled = true,
            isFocused = false,
            isSelected = false,
            isPassword = false,
            bounds = UiBounds(100, 200, 500, 350),
            availableActions = listOf("click")
        )

        val map = element.toCompactMap()
        assertEquals("e1", map["id"])
        assertEquals("Button", map["class"])
        assertEquals("Wi-Fi", map["text"])
        assertEquals("Wi-Fi toggle switch", map["desc"])
        assertEquals(true, map["clickable"])
        assertEquals("[100,200][500,350]", map["bounds"])
    }

    @Test
    fun testUiSnapshotJsonSerialization() {
        val element = UiElement(
            id = "e1",
            className = "android.widget.TextView",
            text = "Settings",
            bounds = UiBounds(0, 0, 1080, 2400)
        )

        val snapshot = UiSnapshot(
            snapshotId = "snap-12345",
            timestamp = 1700000000L,
            packageName = "com.android.settings",
            elements = listOf(element),
            totalNodesCount = 10,
            isTruncated = false,
            summary = "Settings screen"
        )

        val jsonMap = snapshot.toCompactJsonMap()
        assertEquals("snap-12345", jsonMap["snapshotId"])
        assertEquals("com.android.settings", jsonMap["package"])
        assertEquals(1, jsonMap["elementsCount"])
        assertFalse(jsonMap["isTruncated"] as Boolean)
    }

    @Test
    fun testNullRootNodeProducesEmptySnapshot() {
        val snapshot = AccessibilitySnapshotEngine.createSnapshot(null)
        assertNotNull(snapshot.snapshotId)
        assertTrue(snapshot.elements.isEmpty())
        assertEquals(0, snapshot.totalNodesCount)
        assertTrue(snapshot.summary.contains("No active accessibility window"))
    }

    @Test
    fun testActionPolicyBlocksCredentialAndSensitiveInputs() {
        val policy: ActionPolicy = StandardActionPolicy()
        val enterTextTool = EnterTextTool()

        // Normal text -> Allow
        val normalDecision = policy.evaluate(
            enterTextTool.definition,
            mapOf("text" to "Search for Wi-Fi settings")
        )
        assertTrue(normalDecision is PolicyDecision.Allow)

        // Credential pattern -> Deny
        val sensitiveDecision = policy.evaluate(
            enterTextTool.definition,
            mapOf("text" to "my_super_secret_password_123456789")
        )
        assertTrue(sensitiveDecision is PolicyDecision.Deny)
        val denyReason = (sensitiveDecision as PolicyDecision.Deny).reason
        assertTrue(denyReason.contains("credential or payment"))
    }

    @Test
    fun testTapElementToolValidatesArguments() = runTest {
        val tapTool = TapElementTool()

        // Missing arguments
        val missingArgsResult = tapTool.execute(emptyMap())
        assertFalse(missingArgsResult.success)
        assertEquals("INVALID_ARGUMENTS", missingArgsResult.error)

        // Disconnected service
        val disconnectedResult = tapTool.execute(mapOf("snapshotId" to "s1", "elementId" to "e1"))
        assertFalse(disconnectedResult.success)
        assertEquals("SERVICE_NOT_CONNECTED", disconnectedResult.error)
    }

    @Test
    fun testEnterTextToolValidatesArguments() = runTest {
        val enterTextTool = EnterTextTool()

        val missingTextResult = enterTextTool.execute(mapOf("snapshotId" to "s1", "elementId" to "e1"))
        assertFalse(missingTextResult.success)
        assertEquals("INVALID_ARGUMENTS", missingTextResult.error)
    }

    @Test
    fun testGetUiSnapshotWhenDisconnected() = runTest {
        val getUiSnapshotTool = GetUiSnapshotTool()
        val result = getUiSnapshotTool.execute(emptyMap())
        assertFalse(result.success)
        assertEquals("ACCESSIBILITY_SERVICE_NOT_CONNECTED", result.error)
    }

    @Test
    fun testNavigateBackToolWhenDisconnected() = runTest {
        val backTool = NavigateBackTool()
        val result = backTool.execute(emptyMap())
        assertFalse(result.success)
        assertEquals("SERVICE_NOT_CONNECTED", result.error)
    }

    @Test
    fun testScrollUiToolWhenDisconnected() = runTest {
        val scrollTool = ScrollUiTool()
        val result = scrollTool.execute(mapOf("direction" to "DOWN"))
        assertFalse(result.success)
        assertEquals("SERVICE_NOT_CONNECTED", result.error)
    }

    @Test
    fun testToolRegistryRegistersAllPhase3Tools() {
        val registry = ToolRegistry().apply {
            register(GetUiSnapshotTool())
            register(TapElementTool())
            register(EnterTextTool())
            register(ScrollUiTool())
            register(NavigateBackTool())
        }

        assertNotNull(registry.getTool("get_ui_snapshot"))
        assertNotNull(registry.getTool("tap_element"))
        assertNotNull(registry.getTool("enter_text"))
        assertNotNull(registry.getTool("scroll_ui"))
        assertNotNull(registry.getTool("navigate_back"))
        assertEquals(5, registry.getAllTools().size)

        val tapSchema = registry.getTool("tap_element")!!.definition.toAiSchema()
        assertEquals("tap_element", tapSchema.name)
        assertTrue(tapSchema.required.contains("snapshotId"))
        assertTrue(tapSchema.required.contains("elementId"))
    }
}
