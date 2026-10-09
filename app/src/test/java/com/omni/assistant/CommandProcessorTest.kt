package com.omni.assistant

import com.omni.assistant.ai.AiRequest
import com.omni.assistant.ai.LocalCommandProcessor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CommandProcessorTest {

    private lateinit var processor: LocalCommandProcessor

    @Before
    fun setUp() {
        processor = LocalCommandProcessor()
    }

    @Test
    fun testCommandNormalization() {
        val input = "   Show   Device   Information.!!  "
        val normalized = LocalCommandProcessor.normalize(input)
        assertEquals("show device information", normalized)
    }

    @Test
    fun testSupportedCommandMatching_DeviceInfo() = runBlocking {
        val request = AiRequest("Show device information.")
        val response = processor.process(request)

        assertTrue(response.isHandled)
        assertTrue(response.isSupportedCommand)
        assertEquals("get_device_info", response.matchedToolName)
    }

    @Test
    fun testSupportedCommandMatching_Storage() = runBlocking {
        val request = AiRequest("  Check my storage! ")
        val response = processor.process(request)

        assertTrue(response.isHandled)
        assertTrue(response.isSupportedCommand)
        assertEquals("get_storage_info", response.matchedToolName)
    }

    @Test
    fun testSupportedCommandMatching_Battery() = runBlocking {
        val request = AiRequest("Check my battery")
        val response = processor.process(request)

        assertTrue(response.isHandled)
        assertTrue(response.isSupportedCommand)
        assertEquals("get_battery_info", response.matchedToolName)
    }

    @Test
    fun testSupportedCommandMatching_AppVersion() = runBlocking {
        val request = AiRequest("What version of Omni am I using?")
        val response = processor.process(request)

        assertTrue(response.isHandled)
        assertTrue(response.isSupportedCommand)
        assertEquals("get_app_version", response.matchedToolName)
    }

    @Test
    fun testUnsupportedCommandHandling() = runBlocking {
        val request = AiRequest("Play some music on Spotify")
        val response = processor.process(request)

        assertFalse(response.isHandled)
        assertFalse(response.isSupportedCommand)
        assertNull(response.matchedToolName)
        assertEquals(LocalCommandProcessor.UNSUPPORTED_MESSAGE, response.replyText)
    }
}
