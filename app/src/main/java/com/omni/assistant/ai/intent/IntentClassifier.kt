package com.omni.assistant.ai.intent

import com.omni.assistant.ai.bridge.SupportedAiApp
import com.omni.assistant.automation.messaging.MessagingCommandParser
import com.omni.assistant.device.AppInventoryProvider

class IntentClassifier(
    private val appInventoryProvider: AppInventoryProvider? = null
) {

    fun classify(
        input: String,
        preferredAiApp: SupportedAiApp = SupportedAiApp.AUTO
    ): UserIntent {
        val trimmed = input.trim()
        val normalized = trimmed.lowercase()
        val stripped = normalized.trimEnd('.', '!', '?', ';', ',').trim()

        // 1. User Confirmation for Pending Actions
        if (isUserConfirmation(stripped)) {
            return UserIntent.UserConfirmation(isConfirmed = true)
        }
        if (isUserCancellation(stripped)) {
            return UserIntent.UserConfirmation(isConfirmed = false)
        }

        // 2. Explicit AI App Directives (e.g. "ask chatgpt ...", "chatgpt: ...")
        val explicitAiIntent = checkExplicitAiApp(trimmed, stripped)
        if (explicitAiIntent != null) {
            return explicitAiIntent
        }

        // 3. Ambiguous Action Detection (Pronouns with communication or destructive actions)
        val ambiguity = checkAmbiguousAction(trimmed, stripped)
        if (ambiguity != null) {
            return ambiguity
        }

        // 4. Communication / Sensitive Actions (WhatsApp Messaging)
        val messagingTask = MessagingCommandParser.parse(trimmed)
        if (messagingTask != null) {
            return UserIntent.SensitiveAction(
                actionType = "MESSAGING_WHATSAPP",
                toolName = "send_whatsapp_message",
                arguments = mapOf(
                    "recipient" to messagingTask.recipient,
                    "message" to messagingTask.messageText
                ),
                description = "Processing WhatsApp message to '${messagingTask.recipient}'",
                requiresConfirmation = true
            )
        }

        // Check for messaging commands with recipient but missing message body
        val incompleteRecipient = MessagingCommandParser.detectIncompleteRecipient(trimmed)
        if (incompleteRecipient != null) {
            return UserIntent.Ambiguous(
                clarificationPrompt = "Please specify what message you would like to send to $incompleteRecipient (e.g. \"Open WhatsApp and text $incompleteRecipient: how are you\").",
                missingFields = listOf("message")
            )
        }

        // 5. Multi-Step Tasks (e.g. "Open Settings and find the Bluetooth option")
        val multiStep = checkMultiStepTask(trimmed, stripped)
        if (multiStep != null) {
            return multiStep
        }

        // 6. Device State / Hardware Queries (Local Android tools)
        val deviceQuery = checkDeviceQuery(stripped)
        if (deviceQuery != null) {
            return deviceQuery
        }

        // 7. Actionable Reasoning (Complex questions that explicitly ask to check, fix, or inspect device)
        if (isActionableReasoning(stripped)) {
            return UserIntent.ActionableReasoning(
                prompt = trimmed,
                preferredAiApp = if (preferredAiApp != SupportedAiApp.AUTO) preferredAiApp else SupportedAiApp.CHATGPT
            )
        }

        // 8. Single App Actions (e.g. "Open Instagram.", "Launch Settings")
        val appAction = checkAppAction(trimmed, stripped)
        if (appAction != null) {
            return appAction
        }

        // 9. General Questions (e.g. "What is the largest ocean on Earth?", "Explain how Wi-Fi works")
        if (isGeneralQuestion(normalized)) {
            return UserIntent.GeneralQuestion(
                question = trimmed,
                preferredAiApp = if (preferredAiApp != SupportedAiApp.AUTO) preferredAiApp else SupportedAiApp.CHATGPT
            )
        }

        // 10. Default Fallback: Route arbitrary natural language to General Question for answering
        return UserIntent.GeneralQuestion(
            question = trimmed,
            preferredAiApp = if (preferredAiApp != SupportedAiApp.AUTO) preferredAiApp else SupportedAiApp.CHATGPT
        )
    }

    private fun isUserConfirmation(normalized: String): Boolean {
        val confirms = setOf("yes", "confirm", "send", "send it", "proceed", "approve", "yep", "sure", "ok", "okay", "do it")
        return normalized in confirms
    }

    private fun isUserCancellation(normalized: String): Boolean {
        val cancels = setOf("no", "cancel", "abort", "stop", "reject", "nope", "don't send")
        return normalized in cancels
    }

    private fun checkExplicitAiApp(trimmed: String, normalized: String): UserIntent? {
        if (normalized.startsWith("ask chatgpt") || normalized.startsWith("use chatgpt to") || normalized.startsWith("chatgpt:")) {
            val prompt = trimmed
                .removePrefix("ask chatgpt")
                .removePrefix("Ask chatgpt")
                .removePrefix("use chatgpt to")
                .removePrefix("chatgpt:")
                .trim()
            return UserIntent.GeneralQuestion(prompt.ifBlank { trimmed }, SupportedAiApp.CHATGPT)
        }
        if (normalized.startsWith("ask gemini") || normalized.startsWith("use gemini to") || normalized.startsWith("gemini:")) {
            val prompt = trimmed
                .removePrefix("ask gemini")
                .removePrefix("Ask gemini")
                .removePrefix("use gemini to")
                .removePrefix("gemini:")
                .trim()
            return UserIntent.GeneralQuestion(prompt.ifBlank { trimmed }, SupportedAiApp.GEMINI)
        }
        return null
    }

    private fun checkAmbiguousAction(trimmed: String, normalized: String): UserIntent? {
        val sendPronounPattern = Regex("""^(?:send|text|message|forward|share)\s+(?:it|this|that)?\s*(?:to\s+)?(her|him|them|someone)\b""", RegexOption.IGNORE_CASE)
        if (sendPronounPattern.containsMatchIn(normalized)) {
            return UserIntent.Ambiguous(
                clarificationPrompt = "Who would you like to send this to, and what message should I send?",
                missingFields = listOf("recipient", "message")
            )
        }

        if (normalized in setOf("send it", "send message", "send text", "send a message", "text her", "text him", "send to her", "send to him")) {
            return UserIntent.Ambiguous(
                clarificationPrompt = "Who would you like to send this to, and what message should I send?",
                missingFields = listOf("recipient", "message")
            )
        }

        if (normalized in setOf("delete it", "remove it", "delete this", "delete file", "delete the file")) {
            return UserIntent.Ambiguous(
                clarificationPrompt = "Please specify what you would like to delete.",
                missingFields = listOf("target")
            )
        }

        return null
    }

    private fun checkMultiStepTask(trimmed: String, normalized: String): UserIntent? {
        val multiStepRegex = Regex("""^(?:open|launch|start)\s+(.+?)\s+(?:and|then)\s+(?:find|locate|click|tap|select|choose|search for|go to)\s+(?:the\s+)?(.+)$""", RegexOption.IGNORE_CASE)
        val match = multiStepRegex.find(trimmed)
        if (match != null) {
            val appPart = match.groupValues[1].trim()
                .trimEnd('.', '!', '?', ';', ',')
                .removeSuffix(" app")
                .removeSuffix(" application")
                .trimEnd('.', '!', '?', ';', ',')
                .trim()

            val targetPart = match.groupValues[2].trim()
                .trimEnd('.', '!', '?', ';', ',')
                .removeSuffix(" option")
                .removeSuffix(" setting")
                .trimEnd('.', '!', '?', ';', ',')
                .trim()

            return UserIntent.MultiStepTask(
                targetApp = appPart,
                stepAction = "FIND_AND_TAP",
                targetElementText = targetPart,
                rawRequest = trimmed
            )
        }
        return null
    }

    private fun checkDeviceQuery(normalized: String): UserIntent? {
        // Battery
        if (normalized.contains("battery") || normalized.contains("charge level") || normalized.contains("power level") || normalized.contains("how much juice") || normalized == "check battery") {
            return UserIntent.DeviceQuery("get_battery_info", emptyMap(), "Checking battery status")
        }

        // Storage / Memory
        if (normalized.contains("storage") || normalized.contains("disk space") || normalized.contains("free space") || normalized.contains("available memory") || normalized.contains("internal storage")) {
            return UserIntent.DeviceQuery("get_storage_info", emptyMap(), "Checking storage space")
        }

        // Installed Applications
        if (normalized.contains("installed apps") || normalized.contains("installed applications") || normalized.contains("which apps are installed") || normalized.contains("what apps are installed") || normalized.contains("list installed") || normalized.contains("show installed") || normalized == "list apps" || normalized == "show apps") {
            return UserIntent.DeviceQuery("get_installed_apps", emptyMap(), "Retrieving installed applications inventory")
        }

        // Wireless / Connectivity / Bluetooth / Wi-Fi Device Status (Specific status checks, not general questions)
        val isWirelessStatus = normalized.contains("bluetooth enabled") || normalized.contains("bluetooth on") ||
                normalized.contains("bluetooth status") || normalized.contains("check bluetooth") ||
                normalized.contains("wifi connected") || normalized.contains("wifi on") ||
                normalized.contains("wifi status") || normalized.contains("check wifi") ||
                normalized.contains("wi-fi connected") || normalized.contains("wi-fi status") ||
                normalized.contains("network status") || normalized.contains("connectivity status") ||
                normalized.contains("is internet connected") || normalized.contains("internet connection") ||
                normalized == "check internet" || normalized == "bluetooth" || normalized == "wifi" || normalized == "wi-fi"

        if (isWirelessStatus) {
            return UserIntent.DeviceQuery("get_connectivity_status", emptyMap(), "Checking connectivity and wireless status")
        }

        // Device Specs / OS / Model
        if (normalized.contains("device info") || normalized.contains("phone info") || normalized.contains("device specs") || normalized.contains("model number") || normalized.contains("what phone is this") || normalized.contains("os version") || normalized.contains("android version")) {
            return UserIntent.DeviceQuery("get_device_info", emptyMap(), "Retrieving device details")
        }

        // App Version
        if (normalized.contains("app version") || normalized.contains("version of omni") || normalized.contains("build version") || normalized.contains("omni version")) {
            return UserIntent.DeviceQuery("get_app_version", emptyMap(), "Checking Omni version")
        }

        // Accessibility Status
        if (normalized.contains("accessibility status") || normalized.contains("is accessibility enabled") || normalized.contains("check accessibility")) {
            return UserIntent.DeviceQuery("get_accessibility_status", emptyMap(), "Checking accessibility status")
        }

        // Screen Inspection
        if (normalized.contains("ui snapshot") || (normalized.contains("screen") && (normalized.contains("describe") || normalized.contains("inspect") || normalized.contains("view") || normalized.contains("what is on") || normalized.contains("read")))) {
            return UserIntent.DeviceQuery("get_ui_snapshot", emptyMap(), "Inspecting active screen")
        }

        // Navigation Back
        if (normalized in setOf("back", "go back", "navigate back", "press back")) {
            return UserIntent.DeviceQuery("navigate_back", emptyMap(), "Navigating backward")
        }

        // Scroll
        if (normalized.contains("scroll down") || normalized.contains("scroll forward") || normalized == "scroll") {
            return UserIntent.DeviceQuery("scroll_ui", mapOf("direction" to "DOWN"), "Scrolling down")
        }
        if (normalized.contains("scroll up") || normalized.contains("scroll backward")) {
            return UserIntent.DeviceQuery("scroll_ui", mapOf("direction" to "UP"), "Scrolling up")
        }

        return null
    }

    private fun checkAppAction(trimmed: String, normalized: String): UserIntent? {
        val appPrefixes = listOf("open ", "launch ", "start ")
        for (prefix in appPrefixes) {
            if (normalized.startsWith(prefix)) {
                val appQuery = trimmed.substring(prefix.length).trim()
                    .trimEnd('.', '!', '?', ';', ',')
                    .removeSuffix(" app")
                    .removeSuffix(" application")
                    .trimEnd('.', '!', '?', ';', ',')
                    .trim()
                if (appQuery.isNotBlank()) {
                    return UserIntent.AppAction(appName = appQuery)
                }
            }
        }

        // Known app direct names
        val knownApps = listOf("instagram", "settings", "whatsapp", "camera", "photos", "gallery", "chrome", "youtube", "calculator", "clock", "messages", "phone", "files")
        if (normalized in knownApps) {
            val appQuery = trimmed.trimEnd('.', '!', '?', ';', ',').trim()
            return UserIntent.AppAction(appName = appQuery)
        }

        return null
    }

    private fun isActionableReasoning(normalized: String): Boolean {
        val actionDirectives = listOf("and check", "check it", "and fix", "fix it", "and test", "and change", "and set", "and run", "inspect", "inspect the")
        return actionDirectives.any { normalized.contains(it) }
    }

    private fun isGeneralQuestion(normalized: String): Boolean {
        if (normalized.endsWith("?")) return true

        val questionStarters = listOf(
            "what is", "what are", "what was", "what were",
            "who is", "who was", "who are", "who were",
            "why is", "why does", "why are", "why do", "why did",
            "how does", "how do", "how is", "how are", "how can", "how to",
            "where is", "where are", "where was", "where were",
            "when is", "when was", "when did",
            "which is", "which are",
            "how many", "how much",
            "explain ", "tell me about ", "can you explain ", "define ", "meaning of ",
            "what does ", "is it true that "
        )
        return questionStarters.any { normalized.startsWith(it) }
    }
}
