package com.omni.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.assistant.ai.LocalPlanResult
import com.omni.assistant.ai.LocalTaskPlanner
import com.omni.assistant.ai.bridge.AiAppBridge
import com.omni.assistant.ai.bridge.SupportedAiApp
import com.omni.assistant.device.AppInventoryProvider
import com.omni.assistant.execution.ToolRegistry
import com.omni.assistant.execution.tools.LaunchAppTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OnDeviceAppLaunchTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var appInventoryProvider: AppInventoryProvider
    private lateinit var aiAppBridge: AiAppBridge
    private lateinit var launchAppTool: LaunchAppTool
    private lateinit var planner: LocalTaskPlanner

    @Before
    fun setUp() {
        appInventoryProvider = AppInventoryProvider(context)
        aiAppBridge = AiAppBridge(context)
        launchAppTool = LaunchAppTool(context, appInventoryProvider)

        val toolRegistry = ToolRegistry().apply {
            register(launchAppTool)
        }
        planner = LocalTaskPlanner(toolRegistry, appInventoryProvider, aiAppBridge)
    }

    @Test
    fun testAppInventoryDiscoversAppsOnDevice() {
        val apps = appInventoryProvider.getVisibleInstalledApplications()
        assertTrue("Device must have visible installed applications", apps.isNotEmpty())

        val chatGpt = apps.find { it.packageName == "com.openai.chatgpt" }
        assertNotNull("ChatGPT should be discovered in app inventory", chatGpt)

        val gemini = apps.find { it.packageName == "com.google.android.apps.bard" }
        assertNotNull("Gemini should be discovered in app inventory", gemini)
    }

    @Test
    fun testAiAppBridgeDiscoversBothApps() {
        val aiApps = aiAppBridge.discoverInstalledAiApps()
        val chatGpt = aiApps.find { it.app == SupportedAiApp.CHATGPT }
        val gemini = aiApps.find { it.app == SupportedAiApp.GEMINI }

        assertNotNull(chatGpt)
        assertTrue("ChatGPT must be reported as installed", chatGpt!!.isInstalled)
        assertEquals("com.openai.chatgpt", chatGpt.packageName)

        assertNotNull(gemini)
        assertTrue("Gemini must be reported as installed", gemini!!.isInstalled)
        assertEquals("com.google.android.apps.bard", gemini.packageName)
    }

    @Test
    fun testPlannerRoutesOpenCommandsLocally() {
        val planChatGPT = planner.plan("Open ChatGPT")
        assertTrue(planChatGPT is LocalPlanResult.DirectToolExecution)
        assertEquals("launch_app", (planChatGPT as LocalPlanResult.DirectToolExecution).toolName)
        assertEquals("ChatGPT", planChatGPT.arguments["appName"])

        val planGemini = planner.plan("Open Gemini")
        assertTrue(planGemini is LocalPlanResult.DirectToolExecution)
        assertEquals("launch_app", (planGemini as LocalPlanResult.DirectToolExecution).toolName)
        assertEquals("Gemini", planGemini.arguments["appName"])
    }

    @Test
    fun testLaunchAppToolWithChatGPT() = runBlocking {
        val result = launchAppTool.execute(mapOf("appName" to "ChatGPT"))
        assertTrue("LaunchAppTool should succeed for ChatGPT: ${result.summaryText}", result.success)
        assertEquals("com.openai.chatgpt", result.data?.get("packageName"))
        assertTrue(result.summaryText.contains("com.openai.chatgpt"))
    }

    @Test
    fun testLaunchAppToolWithGemini() = runBlocking {
        val result = launchAppTool.execute(mapOf("appName" to "Gemini"))
        assertTrue("LaunchAppTool should succeed for Gemini: ${result.summaryText}", result.success)
        assertEquals("com.google.android.apps.bard", result.data?.get("packageName"))
        assertTrue(result.summaryText.contains("com.google.android.apps.bard"))
    }

    @Test
    fun testLaunchAppToolWithNonExistentApp() = runBlocking {
        val result = launchAppTool.execute(mapOf("appName" to "NonExistentApp9999"))
        assertFalse("LaunchAppTool should fail for non-existent app", result.success)
        assertEquals("APP_NOT_INSTALLED", result.error)
        assertTrue(result.summaryText.contains("not installed"))
    }
}
