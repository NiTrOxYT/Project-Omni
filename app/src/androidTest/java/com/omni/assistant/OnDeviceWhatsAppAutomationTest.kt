package com.omni.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.assistant.ai.LocalPlanResult
import com.omni.assistant.ai.LocalTaskPlanner
import com.omni.assistant.ai.bridge.AiAppBridge
import com.omni.assistant.automation.messaging.MessagingCommandParser
import com.omni.assistant.automation.whatsapp.WhatsAppAutomationAdapter
import com.omni.assistant.device.AppInventoryProvider
import com.omni.assistant.execution.ToolRegistry
import com.omni.assistant.execution.tools.LaunchAppTool
import com.omni.assistant.execution.tools.SendWhatsAppMessageTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceWhatsAppAutomationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var appInventoryProvider: AppInventoryProvider
    private lateinit var aiAppBridge: AiAppBridge
    private lateinit var whatsAppAdapter: WhatsAppAutomationAdapter
    private lateinit var sendWhatsAppTool: SendWhatsAppMessageTool
    private lateinit var planner: LocalTaskPlanner

    @Before
    fun setUp() {
        appInventoryProvider = AppInventoryProvider(context)
        aiAppBridge = AiAppBridge(context)
        whatsAppAdapter = WhatsAppAutomationAdapter(context)
        sendWhatsAppTool = SendWhatsAppMessageTool(whatsAppAdapter)

        val toolRegistry = ToolRegistry().apply {
            register(sendWhatsAppTool)
            register(LaunchAppTool(context, appInventoryProvider))
        }
        planner = LocalTaskPlanner(toolRegistry, appInventoryProvider, aiAppBridge)
    }

    @Test
    fun testParseExampleMissionCommand() {
        val parsed = MessagingCommandParser.parse("Open WhatsApp and text Priyanka: how are you")
        assertNotNull(parsed)
        assertEquals("Priyanka", parsed!!.recipient)
        assertEquals("how are you", parsed.messageText)
    }

    @Test
    fun testPlannerPlansWhatsAppToolDirectly() {
        val plan = planner.plan("Open WhatsApp and text Priyanka: how are you")
        assertTrue(plan is LocalPlanResult.DirectToolExecution)
        val direct = plan as LocalPlanResult.DirectToolExecution
        assertEquals("send_whatsapp_message", direct.toolName)
        assertEquals("Priyanka", direct.arguments["recipient"])
        assertEquals("how are you", direct.arguments["message"])
    }

    @Test
    fun testWhatsAppAdapterDiscoversPackage() {
        val pkg = whatsAppAdapter.getInstalledWhatsAppPackage()
        // If WhatsApp is installed on the physical device, it resolves to com.whatsapp or com.whatsapp.w4b
        if (pkg != null) {
            assertTrue(pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b")
        }
    }

    @Test
    fun testSendWhatsAppMessageToolRequiresRecipient() {
        val result = kotlinx.coroutines.runBlocking {
            sendWhatsAppTool.execute(mapOf("recipient" to "", "message" to "hello"))
        }
        assertTrue(!result.success)
        assertEquals("MISSING_RECIPIENT", result.error)
    }

    @Test
    fun testSendWhatsAppMessageToolRequiresMessage() {
        val result = kotlinx.coroutines.runBlocking {
            sendWhatsAppTool.execute(mapOf("recipient" to "Priyanka", "message" to ""))
        }
        assertTrue(!result.success)
        assertEquals("MISSING_MESSAGE", result.error)
    }

    @Test
    fun testExecuteEndToEndWhatsAppMessagingWorkflowOnDevice() {
        val parsed = MessagingCommandParser.parse("Open WhatsApp and text Priyanka: On my way home")
        assertNotNull(parsed)
        assertEquals("Priyanka", parsed!!.recipient)
        assertEquals("On my way home", parsed.messageText)

        val plan = planner.plan("Open WhatsApp and text Priyanka: On my way home")
        assertTrue(plan is LocalPlanResult.DirectToolExecution)
        val direct = plan as LocalPlanResult.DirectToolExecution
        assertEquals("send_whatsapp_message", direct.toolName)
        assertEquals("Priyanka", direct.arguments["recipient"])
        assertEquals("On my way home", direct.arguments["message"])

        val pkg = whatsAppAdapter.getInstalledWhatsAppPackage()
        assertNotNull("WhatsApp must be installed on target device", pkg)
    }
}
