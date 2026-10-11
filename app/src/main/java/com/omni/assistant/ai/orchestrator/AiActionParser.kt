package com.omni.assistant.ai.orchestrator

import org.json.JSONObject

object AiActionParser {

    data class ParsedProposal(
        val thought: String? = null,
        val action: String,
        val arguments: Map<String, Any?> = emptyMap(),
        val message: String? = null
    )

    fun parse(rawResponse: String): ParsedProposal? {
        val trimmed = rawResponse.trim()
        if (trimmed.isBlank()) return null

        val jsonCandidate = extractJsonBlock(trimmed) ?: trimmed
        return try {
            val json = JSONObject(jsonCandidate)
            val action = json.optString("action").trim().lowercase()
            if (action.isBlank()) return null

            val thought = json.optString("thought").takeIf { it.isNotBlank() }
            val message = json.optString("message").takeIf { it.isNotBlank() }
            val args = mutableMapOf<String, Any?>()
            val argsObj = json.optJSONObject("arguments")
            if (argsObj != null) {
                for (key in argsObj.keys()) {
                    args[key] = argsObj.get(key)
                }
            }
            ParsedProposal(
                thought = thought,
                action = action,
                arguments = args,
                message = message
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun extractJsonBlock(text: String): String? {
        val markdownPattern = Regex("""```(?:json)?\s*(\{[\s\S]*?\})\s*```""", RegexOption.IGNORE_CASE)
        val match = markdownPattern.find(text)
        if (match != null) return match.groupValues[1].trim()

        val firstBrace = text.indexOf('{')
        val lastBrace = text.lastIndexOf('}')
        if (firstBrace != -1 && lastBrace > firstBrace) {
            return text.substring(firstBrace, lastBrace + 1).trim()
        }
        return null
    }
}
