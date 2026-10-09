package com.omni.assistant.ai

import com.omni.assistant.core.logging.OmniLogger

class LocalCommandProcessor : AiProvider {
    private val tag = "LocalCommandProcessor"

    companion object {
        const val UNSUPPORTED_MESSAGE =
            "This command is not supported yet. Additional AI and device-control capabilities will be added in later phases."

        fun normalize(input: String): String {
            return input
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9\\s]"), "") // Strip punctuation
                .replace(Regex("\\s+"), " ")        // Collapse whitespace
        }
    }

    override suspend fun process(request: AiRequest): AiResponse {
        val normalized = normalize(request.prompt)
        OmniLogger.d(tag, "Processing prompt: '$normalized'")

        val matchedTool = matchCommandToTool(normalized)

        return if (matchedTool != null) {
            AiResponse(
                isHandled = true,
                matchedToolName = matchedTool,
                replyText = "Executing $matchedTool...",
                isSupportedCommand = true
            )
        } else {
            AiResponse(
                isHandled = false,
                matchedToolName = null,
                replyText = UNSUPPORTED_MESSAGE,
                isSupportedCommand = false
            )
        }
    }

    /**
     * Deterministically maps supported user natural-language commands to tool names.
     */
    fun matchCommandToTool(normalizedInput: String): String? {
        return when {
            // "Show device information."
            normalizedInput == "show device information" ||
            normalizedInput == "device info" ||
            normalizedInput == "show device info" ||
            normalizedInput == "get device info" ||
            normalizedInput == "device information" -> "get_device_info"

            // "Check my storage."
            normalizedInput == "check my storage" ||
            normalizedInput == "check storage" ||
            normalizedInput == "show storage" ||
            normalizedInput == "storage info" ||
            normalizedInput == "get storage info" -> "get_storage_info"

            // "Check my battery."
            normalizedInput == "check my battery" ||
            normalizedInput == "check battery" ||
            normalizedInput == "show battery" ||
            normalizedInput == "battery status" ||
            normalizedInput == "get battery info" -> "get_battery_info"

            // "What version of Omni am I using?"
            normalizedInput == "what version of omni am i using" ||
            normalizedInput == "what version of omni" ||
            normalizedInput == "app version" ||
            normalizedInput == "omni version" ||
            normalizedInput == "check version" ||
            normalizedInput == "what is my omni version" -> "get_app_version"

            else -> null
        }
    }
}
