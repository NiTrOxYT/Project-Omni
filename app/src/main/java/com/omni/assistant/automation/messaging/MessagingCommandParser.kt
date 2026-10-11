package com.omni.assistant.automation.messaging

object MessagingCommandParser {

    /**
     * Parses natural language instructions for messaging applications.
     * Preserves exact message text, capitalization, and punctuation.
     */
    fun parse(input: String): MessagingRequest? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        val lower = trimmed.lowercase()
        if (!lower.contains("whatsapp") && !lower.contains("text ") && !lower.contains("message ") && !lower.contains("send ")) {
            return null
        }

        // Pattern 1: "Open WhatsApp and text <Recipient>: <Message>"
        // or "Open WhatsApp and text <Recipient> saying <Message>"
        // or "Open WhatsApp and text <Recipient> <Message>"
        val openAndTextRegex = Regex(
            """^(?:open\s+whatsapp\s+and\s+)?(?:text|message|send\s+message\s+to|send\s+a\s+message\s+to)\s+([A-Za-z0-9\s+]+?)(?:\s+(?:on|via|in)\s+whatsapp)?(?:\s*:\s*|\s+saying\s+|\s+that\s+)(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val match1 = openAndTextRegex.find(trimmed)
        if (match1 != null) {
            val recipient = match1.groupValues[1].trim()
            val msg = cleanMessageText(match1.groupValues[2])
            if (recipient.isNotBlank() && msg.isNotBlank()) {
                return MessagingRequest(recipient = recipient, messageText = msg)
            }
        }

        // Pattern 2: "Send <Recipient> a WhatsApp message saying <Message>"
        // or "Send <Recipient> a message on WhatsApp: <Message>"
        val sendRecipientMsgRegex = Regex(
            """^send\s+([A-Za-z0-9\s+]+?)\s+(?:a\s+)?whatsapp\s+message(?:\s*:\s*|\s+saying\s+|\s+that\s+|\s+)(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val match2 = sendRecipientMsgRegex.find(trimmed)
        if (match2 != null) {
            val recipient = match2.groupValues[1].trim()
            val msg = cleanMessageText(match2.groupValues[2])
            if (recipient.isNotBlank() && msg.isNotBlank()) {
                return MessagingRequest(recipient = recipient, messageText = msg)
            }
        }

        // Pattern 3: "WhatsApp <Recipient>: <Message>"
        val directWhatsappRegex = Regex(
            """^(?:whatsapp|wa)\s+([A-Za-z0-9\s+]+?)(?:\s*:\s*|\s+saying\s+|\s+that\s+)(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val match3 = directWhatsappRegex.find(trimmed)
        if (match3 != null) {
            val recipient = match3.groupValues[1].trim()
            val msg = cleanMessageText(match3.groupValues[2])
            if (recipient.isNotBlank() && msg.isNotBlank()) {
                return MessagingRequest(recipient = recipient, messageText = msg)
            }
        }

        // Pattern 4: "Text <Recipient> on WhatsApp: <Message>"
        val textOnWhatsappRegex = Regex(
            """^(?:text|message)\s+([A-Za-z0-9\s+]+?)\s+(?:on|in|via)\s+whatsapp(?:\s*:\s*|\s+saying\s+|\s+that\s+|\s+)(.+)$""",
            RegexOption.IGNORE_CASE
        )
        val match4 = textOnWhatsappRegex.find(trimmed)
        if (match4 != null) {
            val recipient = match4.groupValues[1].trim()
            val msg = cleanMessageText(match4.groupValues[2])
            if (recipient.isNotBlank() && msg.isNotBlank()) {
                return MessagingRequest(recipient = recipient, messageText = msg)
            }
        }

        return null
    }

    /**
     * Detects if the user requested a messaging action to a recipient but omitted the message body.
     * Returns the recipient name if found.
     */
    fun detectIncompleteRecipient(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        val incompleteRegexes = listOf(
            Regex("""^(?:open\s+whatsapp\s+(?:through\s+app\s+)?and\s+)?(?:text|message|send\s+(?:a\s+)?(?:whatsapp\s+)?message\s+to)\s+([A-Za-z0-9\s+]+?)(?:\s+(?:a\s+message|on\s+whatsapp|in\s+whatsapp))?$""", RegexOption.IGNORE_CASE),
            Regex("""^send\s+([A-Za-z0-9\s+]+?)\s+(?:a\s+)?(?:whatsapp\s+)?message$""", RegexOption.IGNORE_CASE),
            Regex("""^text\s+([A-Za-z0-9\s+]+?)\s+on\s+whatsapp$""", RegexOption.IGNORE_CASE)
        )

        for (regex in incompleteRegexes) {
            val match = regex.find(trimmed)
            if (match != null) {
                val candidate = match.groupValues[1].trim()
                    .removePrefix("to ")
                    .removeSuffix(" a message")
                    .removeSuffix(" on whatsapp")
                    .trim()
                if (candidate.isNotBlank() && !candidate.equals("whatsapp", ignoreCase = true) && !candidate.equals("message", ignoreCase = true)) {
                    return candidate
                }
            }
        }
        return null
    }

    private fun cleanMessageText(raw: String): String {
        var msg = raw.trim()
        // Strip trailing platform annotations if user appended "on whatsapp" at the end of the sentence
        msg = msg.replace(Regex("""(?i)\s+(?:on|in|via|through)\s+whatsapp\s*[\.!]?$"""), "").trim()
        if (msg.startsWith("\"") && msg.endsWith("\"") && msg.length >= 2) {
            msg = msg.substring(1, msg.length - 1).trim()
        } else if (msg.startsWith("'") && msg.endsWith("'") && msg.length >= 2) {
            msg = msg.substring(1, msg.length - 1).trim()
        }
        return msg
    }
}
