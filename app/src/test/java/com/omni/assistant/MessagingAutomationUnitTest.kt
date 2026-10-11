package com.omni.assistant

import com.omni.assistant.ai.LocalPlanResult
import com.omni.assistant.ai.LocalTaskPlanner
import com.omni.assistant.ai.bridge.AiAppBridge
import com.omni.assistant.automation.messaging.MessagingCommandParser
import com.omni.assistant.automation.messaging.MessagingExecutionResult
import com.omni.assistant.automation.messaging.MessagingRequest
import com.omni.assistant.automation.messaging.MessagingState
import com.omni.assistant.automation.whatsapp.WhatsAppAutomationAdapter
import com.omni.assistant.device.AppInventoryProvider
import com.omni.assistant.domain.model.AppInfo
import com.omni.assistant.execution.ToolRegistry
import com.omni.assistant.execution.tools.SendWhatsAppMessageTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessagingAutomationUnitTest {

    private lateinit var toolRegistry: ToolRegistry
    private lateinit var fakeAppInventory: AppInventoryProvider
    private lateinit var fakeBridge: AiAppBridge
    private lateinit var fakeWhatsAppAdapter: WhatsAppAutomationAdapter
    private lateinit var planner: LocalTaskPlanner
    private lateinit var sendWhatsAppTool: SendWhatsAppMessageTool

    @Before
    fun setUp() {
        toolRegistry = ToolRegistry()

        fakeAppInventory = object : AppInventoryProvider(null) {
            override fun getVisibleInstalledApplications(): List<AppInfo> = listOf(
                AppInfo(packageName = "com.whatsapp", appName = "WhatsApp", versionName = "2.24.1", isSystemApp = false),
                AppInfo(packageName = "com.android.settings", appName = "Settings", versionName = "1.0", isSystemApp = true)
            )
        }

        fakeBridge = object : AiAppBridge(null) {}

        fakeWhatsAppAdapter = object : WhatsAppAutomationAdapter(null) {
            override fun getInstalledWhatsAppPackage(): String? = "com.whatsapp"

            override suspend fun executeMessagingTask(
                request: MessagingRequest,
                autoSend: Boolean,
                onProgress: ((String) -> Unit)?
            ): MessagingExecutionResult {
                return MessagingExecutionResult(
                    success = true,
                    state = if (autoSend) MessagingState.COMPLETED else MessagingState.AWAITING_CONFIRMATION,
                    recipient = request.recipient,
                    messageText = request.messageText,
                    summaryText = "Successfully sent WhatsApp message to '${request.recipient}': \"${request.messageText}\""
                )
            }
        }

        sendWhatsAppTool = SendWhatsAppMessageTool(fakeWhatsAppAdapter)
        toolRegistry.register(sendWhatsAppTool)

        planner = LocalTaskPlanner(toolRegistry, fakeAppInventory, fakeBridge)
    }

    @Test
    fun testParseOpenAndTextCommand() {
        val parsed = MessagingCommandParser.parse("Open WhatsApp and text Priyanka: how are you")
        assertNotNull(parsed)
        assertEquals("Priyanka", parsed!!.recipient)
        assertEquals("how are you", parsed.messageText)
    }

    @Test
    fun testParseSendMessageSayingCommand() {
        val parsed = MessagingCommandParser.parse("Send Priyanka a WhatsApp message saying I'll be late")
        assertNotNull(parsed)
        assertEquals("Priyanka", parsed!!.recipient)
        assertEquals("I'll be late", parsed.messageText)
    }

    @Test
    fun testParseDirectWhatsAppCommand() {
        val parsed = MessagingCommandParser.parse("WhatsApp Priyanka: I will call you soon!")
        assertNotNull(parsed)
        assertEquals("Priyanka", parsed!!.recipient)
        assertEquals("I will call you soon!", parsed.messageText)
    }

    @Test
    fun testParseTextOnWhatsAppCommand() {
        val parsed = MessagingCommandParser.parse("Text Priyanka on WhatsApp: where are you?")
        assertNotNull(parsed)
        assertEquals("Priyanka", parsed!!.recipient)
        assertEquals("where are you?", parsed.messageText)
    }

    @Test
    fun testPreservesExactCapitalizationAndPunctuation() {
        val testMsg = "Hello Priyanka! Are we meeting at 5:00 PM today? :) Cheers."
        val parsed = MessagingCommandParser.parse("Open WhatsApp and text Priyanka: $testMsg")
        assertNotNull(parsed)
        assertEquals("Priyanka", parsed!!.recipient)
        assertEquals(testMsg, parsed.messageText)
    }

    @Test
    fun testPlannerRoutesMessagingCommandToWhatsAppTool() {
        val plan = planner.plan("Open WhatsApp and text Priyanka: how are you")
        assertTrue(plan is LocalPlanResult.DirectToolExecution)
        val direct = plan as LocalPlanResult.DirectToolExecution
        assertEquals("send_whatsapp_message", direct.toolName)
        assertEquals("Priyanka", direct.arguments["recipient"])
        assertEquals("how are you", direct.arguments["message"])
    }

    @Test
    fun testSendWhatsAppMessageToolExecution() = runBlocking {
        val result = sendWhatsAppTool.execute(
            mapOf("recipient" to "Priyanka", "message" to "how are you", "autoSend" to true)
        )
        assertTrue(result.success)
        assertEquals("send_whatsapp_message", result.tool)
        assertTrue(result.summaryText.contains("Priyanka"))
        assertTrue(result.summaryText.contains("how are you"))
    }

    @Test
    fun testMissingRecipientFailsGracefully() = runBlocking {
        val result = sendWhatsAppTool.execute(
            mapOf("recipient" to "", "message" to "hello")
        )
        assertTrue(!result.success)
        assertEquals("MISSING_RECIPIENT", result.error)
    }

    @Test
    fun testMissingMessageFailsGracefully() = runBlocking {
        val result = sendWhatsAppTool.execute(
            mapOf("recipient" to "Priyanka", "message" to "")
        )
        assertTrue(!result.success)
        assertEquals("MISSING_MESSAGE", result.error)
    }

    @Test
    fun testAwaitingConfirmationStatePreserved() = runBlocking {
        val result = sendWhatsAppTool.execute(
            mapOf("recipient" to "Priyanka", "message" to "how are you", "autoSend" to false)
        )
        assertTrue(result.success)
        assertEquals("AWAITING_CONFIRMATION", result.data?.get("state"))
    }

    @Test
    fun testParseMessageWithTrailingPlatform() {
        val parsed = MessagingCommandParser.parse("message priyanka : how are you on whatsapp")
        assertNotNull(parsed)
        assertEquals("priyanka", parsed!!.recipient)
        assertEquals("how are you", parsed.messageText)
    }

    @Test
    fun testNormalizeContactNameWithEmoji() {
        val adapter = WhatsAppAutomationAdapter(null)
        assertEquals("priyanka", adapter.normalizeContactName("Priyanka 🫂"))
        assertEquals("priyanka", adapter.normalizeContactName("  Priyanka  "))
        assertEquals("mom", adapter.normalizeContactName("Mom ❤️"))
    }

    @Test
    fun testPickBestContactMatchWithEmoji() {
        val adapter = WhatsAppAutomationAdapter(null)
        val candidate = com.omni.assistant.accessibility.UiElement(
            id = "e1",
            className = "android.widget.TextView",
            resourceId = "com.whatsapp:id/conversations_row_contact_name",
            text = "Priyanka 🫂",
            bounds = com.omni.assistant.accessibility.UiBounds(0, 0, 100, 100)
        )
        val matched = adapter.pickBestContactMatch(listOf(candidate), "priyanka")
        assertNotNull(matched)
        assertEquals("e1", matched!!.id)
    }

    @Test
    fun testEmptyOrInvalidCommandReturnsNull() {
        assertNull(MessagingCommandParser.parse(""))
        assertNull(MessagingCommandParser.parse("What is the weather today?"))
        assertNull(MessagingCommandParser.parse("Open WhatsApp")) // Only app launch, not messaging
    }
}
