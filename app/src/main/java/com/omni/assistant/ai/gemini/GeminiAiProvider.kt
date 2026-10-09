package com.omni.assistant.ai.gemini

import com.omni.assistant.ai.AiMessage
import com.omni.assistant.ai.AiProvider
import com.omni.assistant.ai.AiRequest
import com.omni.assistant.ai.AiResponse
import com.omni.assistant.ai.AiRole
import com.omni.assistant.ai.AiToolCall
import com.omni.assistant.ai.AiToolSchema
import com.omni.assistant.ai.config.AiConfigRepository
import com.omni.assistant.core.logging.OmniLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class GeminiAiProvider(
    private val aiConfigRepository: AiConfigRepository,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .dns(ResilientDns())
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
) : AiProvider {

    private class ResilientDns : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            val trimmed = hostname.trim()
            if (trimmed.isBlank()) throw java.net.UnknownHostException("hostname is blank")

            // 1. Try standard system DNS first
            try {
                val addresses = okhttp3.Dns.SYSTEM.lookup(trimmed)
                if (addresses.isNotEmpty()) return addresses
            } catch (e: Exception) {
                OmniLogger.w("ResilientDns", "System DNS lookup failed for $trimmed: ${e.message}")
            }

            // 2. Fallback: Query public DNS directly via UDP socket (bypassing Android libc getaddrinfo lock issues)
            try {
                val directIps = DirectDnsResolver.resolve(trimmed)
                if (directIps.isNotEmpty()) {
                    OmniLogger.i("ResilientDns", "Direct DNS fallback resolved $trimmed to ${directIps.size} addresses")
                    return directIps
                }
            } catch (e: Exception) {
                OmniLogger.w("ResilientDns", "Direct DNS fallback failed for $trimmed: ${e.message}")
            }

            throw java.net.UnknownHostException("Unable to resolve host \"$trimmed\": No address associated with hostname")
        }
    }

    private val tag = "GeminiAiProvider"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    override suspend fun generateResponse(request: AiRequest): AiResponse = withContext(Dispatchers.IO) {
        val config = aiConfigRepository.getCurrentConfig()
        if (!config.hasValidApiKey) {
            return@withContext AiResponse(
                isHandled = false,
                replyText = "Gemini API key is not configured. Please add your API key in Settings.",
                isSupportedCommand = false
            )
        }

        val modelName = config.modelName.trim().ifBlank { "gemini-flash-latest" }
        val cleanedKey = config.apiKey.trim()
        val url = "$BASE_URL/$modelName:generateContent"

        val requestPayload = buildJsonPayload(request)
        OmniLogger.d(tag, "Sending request to model $modelName with ${request.tools.size} registered tools")

        val httpRequest = Request.Builder()
            .url(url)
            .addHeader("x-goog-api-key", cleanedKey)
            .addHeader("Content-Type", "application/json")
            .post(requestPayload.toString().toRequestBody(jsonMediaType))
            .build()

        try {
            client.newCall(httpRequest).execute().use { httpResponse ->
                val responseBody = httpResponse.body?.string() ?: ""

                if (!httpResponse.isSuccessful) {
                    val errorMsg = parseErrorMessage(httpResponse.code, responseBody)
                    OmniLogger.w(tag, "Gemini API request failed [HTTP ${httpResponse.code}]: $errorMsg")
                    return@withContext AiResponse(
                        isHandled = false,
                        replyText = "AI Service Error (HTTP ${httpResponse.code}): $errorMsg",
                        isSupportedCommand = false,
                        rawJson = responseBody
                    )
                }

                parseGeminiResponse(responseBody)
            }
        } catch (e: java.net.UnknownHostException) {
            val errorMsg = "DNS resolution failed for generativelanguage.googleapis.com (${e.message ?: "unknown host"}). Please check your internet connection."
            OmniLogger.e(tag, errorMsg, e)
            AiResponse(
                isHandled = false,
                replyText = errorMsg,
                isSupportedCommand = false
            )
        } catch (e: java.net.SocketTimeoutException) {
            val errorMsg = "Request to Gemini API timed out (${e.message ?: "timeout"}). Please try again."
            OmniLogger.e(tag, errorMsg, e)
            AiResponse(
                isHandled = false,
                replyText = errorMsg,
                isSupportedCommand = false
            )
        } catch (e: javax.net.ssl.SSLHandshakeException) {
            val errorMsg = "TLS/SSL security handshake failed (${e.message ?: "SSL handshake error"})."
            OmniLogger.e(tag, errorMsg, e)
            AiResponse(
                isHandled = false,
                replyText = errorMsg,
                isSupportedCommand = false
            )
        } catch (e: javax.net.ssl.SSLException) {
            val errorMsg = "Secure SSL connection failed (${e.message ?: "SSL error"})."
            OmniLogger.e(tag, errorMsg, e)
            AiResponse(
                isHandled = false,
                replyText = errorMsg,
                isSupportedCommand = false
            )
        } catch (e: java.net.ConnectException) {
            val errorMsg = "Failed to establish network connection (${e.message ?: "connection refused"})."
            OmniLogger.e(tag, errorMsg, e)
            AiResponse(
                isHandled = false,
                replyText = errorMsg,
                isSupportedCommand = false
            )
        } catch (e: IOException) {
            val errorMsg = "Network communication error (${e.javaClass.simpleName}): ${e.message ?: "I/O error"}"
            OmniLogger.e(tag, errorMsg, e)
            AiResponse(
                isHandled = false,
                replyText = errorMsg,
                isSupportedCommand = false
            )
        } catch (e: Exception) {
            val errorMsg = "Unexpected error (${e.javaClass.simpleName}): ${e.message ?: "internal error"}"
            OmniLogger.e(tag, errorMsg, e)
            AiResponse(
                isHandled = false,
                replyText = errorMsg,
                isSupportedCommand = false
            )
        }
    }

    fun buildJsonPayload(request: AiRequest): JSONObject {
        val root = JSONObject()

        // 1. System Instruction
        val systemText = request.systemInstruction ?: "You are Omni, an autonomous personal AI assistant on Android. Use available tools to inspect device state and give helpful, concise responses grounded in tool results."
        val systemObj = JSONObject()
        val systemParts = JSONArray().put(JSONObject().put("text", systemText))
        systemObj.put("parts", systemParts)
        root.put("systemInstruction", systemObj)

        // 2. Contents / Conversation History
        val contentsArray = JSONArray()

        // Add history messages
        for (msg in request.conversationHistory) {
            // If the message has raw content JSON preserved (e.g. model response with thought_signatures), use it directly
            if (!msg.rawContentJson.isNullOrBlank()) {
                try {
                    val rawContent = JSONObject(msg.rawContentJson)
                    contentsArray.put(rawContent)
                    continue
                } catch (_: Exception) {}
            }

            val contentObj = JSONObject()
            val roleStr = when (msg.role) {
                AiRole.USER -> "user"
                AiRole.MODEL -> "model"
                AiRole.TOOL -> "user"
                AiRole.SYSTEM -> "user"
            }
            contentObj.put("role", roleStr)

            val partsArray = JSONArray()
            if (!msg.text.isNullOrBlank()) {
                partsArray.put(JSONObject().put("text", msg.text))
            }

            // If model called tools in this turn
            for (toolCall in msg.toolCalls) {
                if (!toolCall.rawPartJson.isNullOrBlank()) {
                    try {
                        val rawPart = JSONObject(toolCall.rawPartJson)
                        partsArray.put(rawPart)
                        continue
                    } catch (_: Exception) {}
                }
                val partObj = JSONObject()
                val funcCallObj = JSONObject()
                funcCallObj.put("name", toolCall.name)
                if (toolCall.id.isNotBlank()) {
                    funcCallObj.put("id", toolCall.id)
                }
                val argsObj = JSONObject()
                toolCall.arguments.forEach { (k, v) -> argsObj.put(k, v ?: JSONObject.NULL) }
                funcCallObj.put("args", argsObj)
                partObj.put("functionCall", funcCallObj)
                if (!toolCall.thoughtSignature.isNullOrBlank()) {
                    partObj.put("thoughtSignature", toolCall.thoughtSignature)
                }
                partsArray.put(partObj)
            }

            // If user/tool returned tool outputs
            for (toolResult in msg.toolResults) {
                val funcRespObj = JSONObject()
                funcRespObj.put("name", toolResult.toolName)
                if (toolResult.toolCallId.isNotBlank()) {
                    funcRespObj.put("id", toolResult.toolCallId)
                }
                val respObj = JSONObject()
                toolResult.content.forEach { (k, v) -> respObj.put(k, v ?: JSONObject.NULL) }
                funcRespObj.put("response", respObj)
                partsArray.put(JSONObject().put("functionResponse", funcRespObj))
            }

            if (partsArray.length() > 0) {
                contentObj.put("parts", partsArray)
                contentsArray.put(contentObj)
            }
        }

        // Add current prompt if not already in history
        if (request.prompt.isNotBlank()) {
            val userTurn = JSONObject()
            userTurn.put("role", "user")
            userTurn.put("parts", JSONArray().put(JSONObject().put("text", request.prompt)))
            contentsArray.put(userTurn)
        }

        root.put("contents", contentsArray)

        // 3. Tools / Function Declarations
        if (request.tools.isNotEmpty()) {
            val toolsArray = JSONArray()
            val toolObj = JSONObject()
            val funcDeclarations = JSONArray()

            for (tool in request.tools) {
                val funcDecl = JSONObject()
                funcDecl.put("name", tool.name)
                funcDecl.put("description", tool.description)

                val paramObj = JSONObject()
                paramObj.put("type", "OBJECT")
                val propsObj = JSONObject()
                for ((propName, propDef) in tool.properties) {
                    val p = JSONObject()
                    p.put("type", propDef.type.uppercase())
                    p.put("description", propDef.description)
                    if (!propDef.enumValues.isNullOrEmpty()) {
                        p.put("enum", JSONArray(propDef.enumValues))
                    }
                    propsObj.put(propName, p)
                }
                paramObj.put("properties", propsObj)
                if (tool.required.isNotEmpty()) {
                    paramObj.put("required", JSONArray(tool.required))
                }
                funcDecl.put("parameters", paramObj)
                funcDeclarations.put(funcDecl)
            }

            toolObj.put("functionDeclarations", funcDeclarations)
            toolsArray.put(toolObj)
            root.put("tools", toolsArray)
        }

        return root
    }

    fun parseGeminiResponse(jsonString: String): AiResponse {
        val root = JSONObject(jsonString)
        val candidates = root.optJSONArray("candidates")
        if (candidates == null || candidates.length() == 0) {
            val promptFeedback = root.optJSONObject("promptFeedback")
            val blockReason = promptFeedback?.optString("blockReason")
            return AiResponse(
                isHandled = false,
                replyText = if (blockReason.isNullOrBlank()) "No response generated by model." else "Response blocked: $blockReason",
                isSupportedCommand = false,
                rawJson = jsonString
            )
        }

        val firstCandidate = candidates.getJSONObject(0)
        val content = firstCandidate.optJSONObject("content")
        val rawContentJson = content?.toString()
        val parts = content?.optJSONArray("parts") ?: JSONArray()

        val textBuilder = StringBuilder()
        val toolCalls = mutableListOf<AiToolCall>()

        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.has("text")) {
                textBuilder.append(part.getString("text"))
            }
            if (part.has("functionCall")) {
                val funcCall = part.getJSONObject("functionCall")
                val toolName = funcCall.getString("name")
                val callId = funcCall.optString("id", "")
                val argsObj = funcCall.optJSONObject("args")
                val argsMap = mutableMapOf<String, Any?>()
                if (argsObj != null) {
                    val keys = argsObj.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        argsMap[key] = argsObj.opt(key)
                    }
                }
                val thoughtSig = if (part.has("thoughtSignature")) {
                    part.optString("thoughtSignature")
                } else if (part.has("thought_signature")) {
                    part.optString("thought_signature")
                } else if (funcCall.has("thoughtSignature")) {
                    funcCall.optString("thoughtSignature")
                } else if (funcCall.has("thought_signature")) {
                    funcCall.optString("thought_signature")
                } else {
                    null
                }
                toolCalls.add(
                    AiToolCall(
                        id = callId.ifBlank { UUID.randomUUID().toString() },
                        name = toolName,
                        arguments = argsMap,
                        thoughtSignature = thoughtSig,
                        rawPartJson = part.toString()
                    )
                )
            }
        }

        val reply = textBuilder.toString().trim().ifBlank { null }
        return AiResponse(
            isHandled = true,
            replyText = reply,
            toolCalls = toolCalls,
            matchedToolName = toolCalls.firstOrNull()?.name,
            toolParameters = toolCalls.firstOrNull()?.arguments ?: emptyMap(),
            rawJson = jsonString,
            rawContentJson = rawContentJson
        )
    }

    private fun parseErrorMessage(code: Int, body: String): String {
        return try {
            val json = JSONObject(body)
            val error = json.optJSONObject("error")
            val message = error?.optString("message")
            val status = error?.optString("status")
            if (!message.isNullOrBlank()) {
                if (!status.isNullOrBlank()) "$status: $message" else message
            } else {
                defaultErrorMessageForCode(code)
            }
        } catch (e: Exception) {
            defaultErrorMessageForCode(code)
        }
    }

    private fun defaultErrorMessageForCode(code: Int): String {
        return when (code) {
            400 -> "HTTP 400: Malformed request payload or invalid API key."
            401 -> "HTTP 401: Unauthorized. Please check your Gemini API key in Settings."
            403 -> "HTTP 403: Forbidden. API key lacks permissions or billing is disabled."
            404 -> "HTTP 404: Model not found or unavailable. Please verify the model identifier in Settings."
            429 -> "HTTP 429: Rate limit exceeded or quota exhausted. Please retry in a moment."
            500 -> "HTTP 500: Internal server error at Gemini AI service."
            503 -> "HTTP 503: Gemini AI service is temporarily unavailable."
            else -> "HTTP $code response from AI service."
        }
    }
}
