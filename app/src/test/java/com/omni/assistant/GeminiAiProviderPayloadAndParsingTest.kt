package com.omni.assistant

import com.omni.assistant.ai.AiMessage
import com.omni.assistant.ai.AiRequest
import com.omni.assistant.ai.AiRole
import com.omni.assistant.ai.AiToolCall
import com.omni.assistant.ai.AiToolProperty
import com.omni.assistant.ai.AiToolResult
import com.omni.assistant.ai.AiToolSchema
import com.omni.assistant.ai.config.AiConfig
import com.omni.assistant.ai.config.AiConfigRepository
import com.omni.assistant.ai.gemini.GeminiAiProvider
import kotlinx.coroutines.flow.flowOf
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GeminiAiProviderPayloadAndParsingTest {

    private lateinit var provider: GeminiAiProvider

    private val fakeConfigRepo = object : AiConfigRepository {
        var storedConfig = AiConfig(apiKey = "AIzaFakeKey1234567890", modelName = "gemini-2.0-flash")
        override fun getConfig() = flowOf(storedConfig)
        override fun getCurrentConfig() = storedConfig
        override suspend fun updateApiKey(apiKey: String) { storedConfig = storedConfig.copy(apiKey = apiKey) }
        override suspend fun updateModel(modelName: String) { storedConfig = storedConfig.copy(modelName = modelName) }
        override suspend fun updateUseCloudAi(useCloudAi: Boolean) { storedConfig = storedConfig.copy(useCloudAi = useCloudAi) }
    }

    @Before
    fun setUp() {
        provider = GeminiAiProvider(fakeConfigRepo)
    }

    @Test
    fun testBuildJsonPayloadWithToolsAndHistory() {
        val toolSchema = AiToolSchema(
            name = "get_battery_info",
            description = "Get battery status",
            properties = mapOf("details" to AiToolProperty(type = "boolean", description = "Include health"))
        )

        val history = listOf(
            AiMessage(role = AiRole.USER, text = "Hello"),
            AiMessage(role = AiRole.MODEL, toolCalls = listOf(AiToolCall(name = "get_battery_info", arguments = mapOf("details" to true)))),
            AiMessage(role = AiRole.TOOL, toolResults = listOf(AiToolResult(toolName = "get_battery_info", content = mapOf("level" to 85))))
        )

        val request = AiRequest(
            prompt = "What is my battery level?",
            conversationHistory = history,
            tools = listOf(toolSchema),
            systemInstruction = "You are Omni assistant."
        )

        val json = provider.buildJsonPayload(request)

        assertTrue(json.has("systemInstruction"))
        assertTrue(json.has("contents"))
        assertTrue(json.has("tools"))

        val toolsArray = json.getJSONArray("tools")
        val funcDecls = toolsArray.getJSONObject(0).getJSONArray("functionDeclarations")
        assertEquals("get_battery_info", funcDecls.getJSONObject(0).getString("name"))

        val contents = json.getJSONArray("contents")
        assertEquals(4, contents.length()) // 3 history turns + 1 current prompt turn

        // Verify turn 3 has role 'function' and functionResponse object
        val toolTurn = contents.getJSONObject(2)
        assertEquals("user", toolTurn.getString("role"))
        val funcRespPart = toolTurn.getJSONArray("parts").getJSONObject(0)
        assertTrue(funcRespPart.has("functionResponse"))
        val funcRespObj = funcRespPart.getJSONObject("functionResponse")
        assertEquals("get_battery_info", funcRespObj.getString("name"))
        assertEquals(85, funcRespObj.getJSONObject("response").getInt("level"))
    }

    @Test
    fun testParseGeminiFunctionCallResponse() {
        val rawJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "functionCall": {
                          "name": "get_battery_info",
                          "args": {
                            "verbose": true
                          }
                        }
                      }
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()

        val response = provider.parseGeminiResponse(rawJson)

        assertTrue(response.isHandled)
        assertEquals(1, response.toolCalls.size)
        val toolCall = response.toolCalls[0]
        assertEquals("get_battery_info", toolCall.name)
        assertEquals(true, toolCall.arguments["verbose"])
    }

    @Test
    fun testParseGeminiTextResponse() {
        val rawJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "text": "Your battery is currently at 95%."
                      }
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()

        val response = provider.parseGeminiResponse(rawJson)

        assertTrue(response.isHandled)
        assertEquals(0, response.toolCalls.size)
        assertEquals("Your battery is currently at 95%.", response.replyText)
    }

    @Test
    fun testParseGeminiThoughtSignatureResponse() {
        val rawJson = """
            {
              "candidates": [
                {
                  "content": {
                    "parts": [
                      {
                        "functionCall": {
                          "name": "get_battery_info",
                          "args": {}
                        },
                        "thoughtSignature": "opaque_thought_signature_token_12345"
                      }
                    ],
                    "role": "model"
                  },
                  "finishReason": "STOP"
                }
              ]
            }
        """.trimIndent()

        val response = provider.parseGeminiResponse(rawJson)

        assertTrue(response.isHandled)
        assertEquals(1, response.toolCalls.size)
        val toolCall = response.toolCalls[0]
        assertEquals("get_battery_info", toolCall.name)
        assertEquals("opaque_thought_signature_token_12345", toolCall.thoughtSignature)
        assertNotNull(response.rawContentJson)

        // Verify that passing rawContentJson in history preserves thoughtSignature
        val request = AiRequest(
            prompt = "",
            conversationHistory = listOf(
                AiMessage(role = AiRole.USER, text = "what's my battery"),
                AiMessage(role = AiRole.MODEL, toolCalls = response.toolCalls, rawContentJson = response.rawContentJson),
                AiMessage(role = AiRole.TOOL, toolResults = listOf(AiToolResult(toolName = "get_battery_info", content = mapOf("level" to 85))))
            )
        )

        val payload = provider.buildJsonPayload(request)
        val contents = payload.getJSONArray("contents")
        assertEquals(3, contents.length())

        val modelTurn = contents.getJSONObject(1)
        assertEquals("model", modelTurn.getString("role"))
        val modelParts = modelTurn.getJSONArray("parts")
        val funcCallPart = modelParts.getJSONObject(0)
        assertEquals("opaque_thought_signature_token_12345", funcCallPart.getString("thoughtSignature"))
    }

    @Test
    fun testParseGeminiBlockedResponse() {
        val rawJson = """
            {
              "candidates": [],
              "promptFeedback": {
                "blockReason": "SAFETY"
              }
            }
        """.trimIndent()

        val response = provider.parseGeminiResponse(rawJson)

        assertFalse(response.isHandled)
        assertTrue(response.replyText?.contains("SAFETY") == true)
    }

    @Test
    fun testNetworkExceptionMapping() = kotlinx.coroutines.runBlocking {
        // OkHttpClient with interceptor that throws UnknownHostException
        val throwingClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor {
                throw java.net.UnknownHostException("generativelanguage.googleapis.com")
            }
            .build()

        val customProvider = GeminiAiProvider(fakeConfigRepo, throwingClient)
        val response = customProvider.generateResponse(
            AiRequest(prompt = "Test DNS failure")
        )

        assertFalse(response.isHandled)
        assertTrue(response.replyText?.contains("DNS resolution failed") == true)
    }

    @Test
    fun testTimeoutExceptionMapping() = kotlinx.coroutines.runBlocking {
        val timeoutClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor {
                throw java.net.SocketTimeoutException("Read timed out")
            }
            .build()

        val customProvider = GeminiAiProvider(fakeConfigRepo, timeoutClient)
        val response = customProvider.generateResponse(
            AiRequest(prompt = "Test timeout")
        )

        assertFalse(response.isHandled)
        assertTrue(response.replyText?.contains("timed out") == true)
    }

    @Test
    fun testHttp400InvalidApiKeyErrorMapping() = kotlinx.coroutines.runBlocking {
        val errorJson = """
            {
              "error": {
                "code": 400,
                "message": "API key not valid. Please pass a valid API key.",
                "status": "INVALID_ARGUMENT"
              }
            }
        """.trimIndent()

        val fakeClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(400)
                    .message("Bad Request")
                    .body(okhttp3.ResponseBody.create("application/json".toMediaType(), errorJson))
                    .build()
            }
            .build()

        val customProvider = GeminiAiProvider(fakeConfigRepo, fakeClient)
        val response = customProvider.generateResponse(
            AiRequest(prompt = "Test invalid key")
        )

        assertFalse(response.isHandled)
        assertTrue(response.replyText?.contains("INVALID_ARGUMENT: API key not valid") == true)
    }

    @Test
    fun testDirectDnsResolverQueryBuildingAndParsing() {
        val hostname = "generativelanguage.googleapis.com"
        val query = com.omni.assistant.ai.gemini.DirectDnsResolver.buildDnsQuery(hostname)
        assertTrue(query.isNotEmpty())
        assertTrue(query.size > 12)

        // Construct synthetic DNS response with 1 A record for 172.217.114.4
        val out = java.io.ByteArrayOutputStream()
        val dataOut = java.io.DataOutputStream(out)
        dataOut.writeShort(0x4A1F) // ID
        dataOut.writeShort(0x8180) // Flags: Standard response, no error
        dataOut.writeShort(1)      // QDCOUNT = 1
        dataOut.writeShort(1)      // ANCOUNT = 1
        dataOut.writeShort(0)      // NSCOUNT = 0
        dataOut.writeShort(0)      // ARCOUNT = 0

        // Question section
        for (part in hostname.split('.')) {
            val bytes = part.toByteArray()
            dataOut.writeByte(bytes.size)
            dataOut.write(bytes)
        }
        dataOut.writeByte(0)
        dataOut.writeShort(1) // QTYPE = A
        dataOut.writeShort(1) // QCLASS = IN

        // Answer section (Name pointer: 0xC00C)
        dataOut.writeShort(0xC00C.toInt())
        dataOut.writeShort(1) // TYPE = A
        dataOut.writeShort(1) // CLASS = IN
        dataOut.writeInt(300) // TTL
        dataOut.writeShort(4) // RDLENGTH = 4
        dataOut.write(byteArrayOf(172.toByte(), 217.toByte(), 114.toByte(), 4.toByte()))
        dataOut.flush()

        val responseBytes = out.toByteArray()
        val parsed = com.omni.assistant.ai.gemini.DirectDnsResolver.parseDnsResponse(
            responseBytes,
            responseBytes.size,
            hostname
        )

        assertEquals(1, parsed.size)
        assertEquals("172.217.114.4", parsed[0].hostAddress)
        assertEquals(hostname, parsed[0].hostName)
    }

    @Test
    fun testDirectDnsResolverDoHResponseParsing() {
        val hostname = "generativelanguage.googleapis.com"
        val dohJson = """
            {
              "Status": 0,
              "Answer": [
                {
                  "name": "generativelanguage.googleapis.com",
                  "type": 1,
                  "TTL": 230,
                  "data": "172.217.114.4"
                },
                {
                  "name": "generativelanguage.googleapis.com",
                  "type": 1,
                  "TTL": 230,
                  "data": "172.217.115.4"
                }
              ]
            }
        """.trimIndent()

        val parsed = com.omni.assistant.ai.gemini.DirectDnsResolver.parseDohJsonResponse(dohJson, hostname)
        assertEquals(2, parsed.size)
        assertEquals("172.217.114.4", parsed[0].hostAddress)
        assertEquals("172.217.115.4", parsed[1].hostAddress)
        assertEquals(hostname, parsed[0].hostName)
    }
}
