package com.omni.assistant.execution.tools

import com.omni.assistant.automation.messaging.MessagingRequest
import com.omni.assistant.automation.whatsapp.WhatsAppAutomationAdapter
import com.omni.assistant.domain.model.RiskLevel
import com.omni.assistant.execution.OmniTool
import com.omni.assistant.execution.ToolDefinition
import com.omni.assistant.execution.ToolParameter
import com.omni.assistant.execution.ToolResult

class SendWhatsAppMessageTool(
    private val whatsAppAdapter: WhatsAppAutomationAdapter
) : OmniTool {

    override val definition = ToolDefinition(
        name = "send_whatsapp_message",
        description = "Searches for a contact on WhatsApp, opens the conversation, composes the exact message, and sends it upon authorization.",
        riskLevel = RiskLevel.HIGH,
        parameters = listOf(
            ToolParameter(
                name = "recipient",
                type = "string",
                description = "Name or phone number of the recipient (e.g. 'Priyanka', 'Mom', '+123456789')",
                isRequired = true
            ),
            ToolParameter(
                name = "message",
                type = "string",
                description = "Exact message text to compose and send",
                isRequired = true
            ),
            ToolParameter(
                name = "autoSend",
                type = "boolean",
                description = "Whether to immediately send the message after composing (default: true)",
                isRequired = false
            )
        )
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val recipient = parameters["recipient"]?.toString()?.trim()
        val message = parameters["message"]?.toString()?.trim()
        val autoSend = parameters["autoSend"] as? Boolean ?: true

        if (recipient.isNullOrBlank()) {
            return ToolResult(
                success = false,
                tool = definition.name,
                summaryText = "Please specify a recipient name for the WhatsApp message.",
                error = "MISSING_RECIPIENT"
            )
        }

        if (message.isNullOrBlank()) {
            return ToolResult(
                success = false,
                tool = definition.name,
                summaryText = "Please specify the message text to send to $recipient.",
                error = "MISSING_MESSAGE"
            )
        }

        val result = whatsAppAdapter.executeMessagingTask(
            request = MessagingRequest(recipient = recipient, messageText = message),
            autoSend = autoSend
        )

        return ToolResult(
            success = result.success,
            tool = definition.name,
            data = mapOf(
                "recipient" to result.recipient,
                "message" to result.messageText,
                "state" to result.state.name
            ),
            summaryText = result.summaryText,
            error = result.error
        )
    }
}
