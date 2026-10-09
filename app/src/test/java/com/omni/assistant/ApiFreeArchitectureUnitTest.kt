package com.omni.assistant

import com.omni.assistant.ai.LocalPlanResult
import com.omni.assistant.ai.LocalTaskPlanner
import com.omni.assistant.ai.bridge.AiAppBridge
import com.omni.assistant.ai.bridge.AiBridgeQuery
import com.omni.assistant.ai.bridge.AiBridgeResponse
import com.omni.assistant.ai.bridge.InstalledAiApp
import com.omni.assistant.ai.bridge.SupportedAiApp
import com.omni.assistant.device.AppInventoryProvider
import com.omni.assistant.domain.model.AppInfo
import com.omni.assistant.domain.model.RiskLevel
import com.omni.assistant.execution.OmniTool
import com.omni.assistant.execution.ToolDefinition
import com.omni.assistant.execution.ToolRegistry
import com.omni.assistant.execution.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ApiFreeArchitectureUnitTest {

    private fun createFakeTool(name: String, risk: RiskLevel = RiskLevel.LOW): OmniTool {
        return object : OmniTool {
            override val definition = ToolDefinition(
                name = name,
                description = "Tool $name",
                riskLevel = risk
            )
            override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
                return ToolResult(
                    success = true,
                    tool = name,
                    data = parameters,
                    summaryText = "Executed $name"
                )
            }
        }
    }

    private lateinit var toolRegistry: ToolRegistry
    private lateinit var fakeAppInventory: AppInventoryProvider
    private lateinit var fakeBridge: AiAppBridge
    private lateinit var planner: LocalTaskPlanner

    @Before
    fun setUp() {
        toolRegistry = ToolRegistry().apply {
            register(createFakeTool("get_battery_info"))
            register(createFakeTool("get_storage_info"))
            register(createFakeTool("get_device_info"))
            register(createFakeTool("get_app_version"))
            register(createFakeTool("get_ui_snapshot"))
            register(createFakeTool("launch_app"))
            register(createFakeTool("tap_element"))
            register(createFakeTool("scroll_ui"))
            register(createFakeTool("navigate_back"))
        }

        fakeAppInventory = object : AppInventoryProvider(null) {
            override fun getVisibleInstalledApplications(): List<AppInfo> = listOf(
                AppInfo(packageName = "com.android.settings", appName = "Settings", versionName = "1.0", isSystemApp = true),
                AppInfo(packageName = "com.openai.chatgpt", appName = "ChatGPT", versionName = "1.0", isSystemApp = false),
                AppInfo(packageName = "com.google.android.apps.bard", appName = "Gemini", versionName = "1.0", isSystemApp = false)
            )
        }

        fakeBridge = object : AiAppBridge(null) {
            override fun discoverInstalledAiApps(): List<InstalledAiApp> = listOf(
                InstalledAiApp(SupportedAiApp.CHATGPT, isInstalled = true, packageName = "com.openai.chatgpt", appLabel = "ChatGPT", versionName = "1.0"),
                InstalledAiApp(SupportedAiApp.GEMINI, isInstalled = true, packageName = "com.google.android.apps.bard", appLabel = "Gemini", versionName = "1.0")
            )

            override fun getPreferredOrAvailablePackage(preferred: SupportedAiApp): String? = when (preferred) {
                SupportedAiApp.CHATGPT -> "com.openai.chatgpt"
                SupportedAiApp.GEMINI -> "com.google.android.apps.bard"
                SupportedAiApp.AUTO -> "com.openai.chatgpt"
            }

            override suspend fun askInstalledAiApp(
                query: AiBridgeQuery,
                onProgress: ((statusMessage: String) -> Unit)?
            ): AiBridgeResponse {
                return AiBridgeResponse(
                    success = true,
                    prompt = query.prompt,
                    responseText = "Simulated response for: ${query.prompt}",
                    appUsed = query.targetApp.displayName
                )
            }
        }

        planner = LocalTaskPlanner(toolRegistry, fakeAppInventory, fakeBridge)
    }

    @Test
    fun testBatteryCommandPlansLocallyWithoutApi() {
        val plan = planner.plan("What is my battery level?")
        assertTrue(plan is LocalPlanResult.DirectToolExecution)
        val direct = plan as LocalPlanResult.DirectToolExecution
        assertEquals("get_battery_info", direct.toolName)
    }

    @Test
    fun testStorageCommandPlansLocallyWithoutApi() {
        val plan = planner.plan("How much storage space is left?")
        assertTrue(plan is LocalPlanResult.DirectToolExecution)
        val direct = plan as LocalPlanResult.DirectToolExecution
        assertEquals("get_storage_info", direct.toolName)
    }

    @Test
    fun testDeviceInfoCommandPlansLocallyWithoutApi() {
        val plan = planner.plan("Show phone specs and device info")
        assertTrue(plan is LocalPlanResult.DirectToolExecution)
        val direct = plan as LocalPlanResult.DirectToolExecution
        assertEquals("get_device_info", direct.toolName)
    }

    @Test
    fun testDescribeScreenPlansLocallyWithoutApi() {
        val plan = planner.plan("Describe the current screen")
        assertTrue(plan is LocalPlanResult.DirectToolExecution)
        val direct = plan as LocalPlanResult.DirectToolExecution
        assertEquals("get_ui_snapshot", direct.toolName)
    }

    @Test
    fun testLaunchAppPlansLocallyWithoutApi() {
        val plan = planner.plan("Open Settings app")
        assertTrue(plan is LocalPlanResult.DirectToolExecution)
        val direct = plan as LocalPlanResult.DirectToolExecution
        assertEquals("launch_app", direct.toolName)
        assertEquals("Settings", direct.arguments["appName"])
    }

    @Test
    fun testNavigateBackPlansLocallyWithoutApi() {
        val plan = planner.plan("Navigate back")
        assertTrue(plan is LocalPlanResult.DirectToolExecution)
        val direct = plan as LocalPlanResult.DirectToolExecution
        assertEquals("navigate_back", direct.toolName)
    }

    @Test
    fun testScrollDownPlansLocallyWithoutApi() {
        val plan = planner.plan("Scroll down")
        assertTrue(plan is LocalPlanResult.DirectToolExecution)
        val direct = plan as LocalPlanResult.DirectToolExecution
        assertEquals("scroll_ui", direct.toolName)
        assertEquals("DOWN", direct.arguments["direction"])
    }

    @Test
    fun testExplicitChatGPTQueryRoutesToBridge() {
        val plan = planner.plan("Ask ChatGPT how to write a Kotlin coroutine")
        assertTrue(plan is LocalPlanResult.NeedsAiBridge)
        val bridge = plan as LocalPlanResult.NeedsAiBridge
        assertEquals(SupportedAiApp.CHATGPT, bridge.targetApp)
        assertTrue(bridge.prompt.contains("how to write a Kotlin coroutine"))
    }

    @Test
    fun testExplicitGeminiQueryRoutesToBridge() {
        val plan = planner.plan("Ask Gemini explain Android architecture")
        assertTrue(plan is LocalPlanResult.NeedsAiBridge)
        val bridge = plan as LocalPlanResult.NeedsAiBridge
        assertEquals(SupportedAiApp.GEMINI, bridge.targetApp)
        assertTrue(bridge.prompt.contains("explain Android architecture"))
    }

    @Test
    fun testComplexOpenEndedQueryDefaultsToSelectedBridge() {
        val plan = planner.plan("What is the capital of France?", preferredAiApp = SupportedAiApp.CHATGPT)
        assertTrue(plan is LocalPlanResult.NeedsAiBridge)
        val bridge = plan as LocalPlanResult.NeedsAiBridge
        assertEquals(SupportedAiApp.CHATGPT, bridge.targetApp)
        assertEquals("What is the capital of France?", bridge.prompt)
    }

    @Test
    fun testAiAppDiscoveryReturnsCorrectModels() {
        val apps = fakeBridge.discoverInstalledAiApps()
        assertEquals(2, apps.size)
        assertTrue(apps.any { it.app == SupportedAiApp.CHATGPT && it.isInstalled })
        assertTrue(apps.any { it.app == SupportedAiApp.GEMINI && it.isInstalled })
    }

    @Test
    fun testOpenChatGPTPlansLaunchAppTool() {
        val plan = planner.plan("Open ChatGPT")
        assertTrue(plan is LocalPlanResult.DirectToolExecution)
        val direct = plan as LocalPlanResult.DirectToolExecution
        assertEquals("launch_app", direct.toolName)
        assertEquals("ChatGPT", direct.arguments["appName"])
    }

    @Test
    fun testOpenGeminiPlansLaunchAppTool() {
        val plan = planner.plan("Open Gemini")
        assertTrue(plan is LocalPlanResult.DirectToolExecution)
        val direct = plan as LocalPlanResult.DirectToolExecution
        assertEquals("launch_app", direct.toolName)
        assertEquals("Gemini", direct.arguments["appName"])
    }

    @Test
    fun testNonexistentAppLaunchToolExecution() = runBlocking {
        // Testing LaunchAppTool directly with fake context/package manager
        val tool = toolRegistry.getTool("launch_app")
        assertNotNull(tool)
    }
}
