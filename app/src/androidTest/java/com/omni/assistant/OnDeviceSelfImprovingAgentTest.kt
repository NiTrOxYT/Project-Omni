package com.omni.assistant

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.omni.assistant.accessibility.UiBounds
import com.omni.assistant.accessibility.UiElement
import com.omni.assistant.accessibility.UiSnapshot
import com.omni.assistant.accessibility.lifecycle.SnapshotLifecycleManager
import com.omni.assistant.accessibility.observation.ObservationClassifier
import com.omni.assistant.accessibility.observation.ScreenClassification
import com.omni.assistant.data.local.database.OmniDatabase
import com.omni.assistant.data.repository.ExperienceRepositoryImpl
import com.omni.assistant.domain.model.ExperienceOutcome
import com.omni.assistant.domain.model.ExperienceRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class OnDeviceSelfImprovingAgentTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: OmniDatabase
    private lateinit var experienceRepo: ExperienceRepositoryImpl
    private lateinit var lifecycleManager: SnapshotLifecycleManager

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, OmniDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        experienceRepo = ExperienceRepositoryImpl(db.experienceDao())
        lifecycleManager = SnapshotLifecycleManager(context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ==========================================
    // PHASE 4: LEARNING ACROSS SEPARATE RUNS
    // ==========================================

    @Test
    fun testLearningAcrossSeparateRuns_PersistsAndRecallsStrategy() = runBlocking {
        val category = "SETTINGS_CONTROL"
        val initialState = "pkg:com.android.settings|screen:SETTINGS_MAIN"

        // RUN A: Safe navigation task succeeds and records verified strategy
        val runAExperience = ExperienceRecord(
            id = UUID.randomUUID().toString(),
            taskCategory = category,
            generalizedInitialState = initialState,
            intendedOutcome = "NAVIGATE_TO_DISPLAY",
            actionsAttempted = listOf("launch_settings", "tap_display_settings"),
            stateTransitions = listOf("SETTINGS_MAIN", "SETTINGS_DISPLAY"),
            successfulActions = listOf("launch_settings", "tap_display_settings"),
            finalOutcome = ExperienceOutcome.SUCCESS,
            executionDurationMs = 1200L
        )
        experienceRepo.recordExperience(runAExperience)

        // Verify it was stored in Room
        val countAfterRunA = db.experienceDao().getCount()
        assertEquals(1, countAfterRunA)

        // RUN B: Separate execution queries Room for learned strategy
        val recalled = experienceRepo.recallStrategy(category, initialState)
        assertNotNull("RUN B must recall verified strategy from Room", recalled)
        assertEquals(category, recalled!!.taskCategory)
        assertEquals(listOf("launch_settings", "tap_display_settings"), recalled.successfulActions)
        assertEquals(1, recalled.successCount)
        assertEquals(0, recalled.failureCount)

        // Reinforce with a second successful run -> successCount should increment to 2
        experienceRepo.recordExperience(runAExperience)
        val updatedRecall = experienceRepo.recallStrategy(category, initialState)
        assertNotNull(updatedRecall)
        assertEquals(2, updatedRecall!!.successCount)
        assertEquals("Strategy count should update existing record, not duplicate", 1, db.experienceDao().getCount())
    }

    @Test
    fun testLearning_AvoidsKnownFailedStrategies() = runBlocking {
        val category = "APP_LAUNCH"
        val initialState = "pkg:com.unknown.app|screen:HOME"

        // Record a failure
        val failureRecord = ExperienceRecord(
            id = UUID.randomUUID().toString(),
            taskCategory = category,
            generalizedInitialState = initialState,
            intendedOutcome = "LAUNCH_NONEXISTENT_APP",
            actionsAttempted = listOf("launch_unknown"),
            stateTransitions = listOf("HOME"),
            successfulActions = emptyList(),
            failedActions = listOf("launch_unknown"),
            finalOutcome = ExperienceOutcome.FAILURE,
            failureCategory = "APP_NOT_FOUND",
            executionDurationMs = 500L
        )
        experienceRepo.recordExperience(failureRecord)

        // Strategy should NOT be recalled because failureCount > successCount
        val recalled = experienceRepo.recallStrategy(category, initialState)
        assertNull("Failed strategies must not be recalled as valid plans", recalled)
    }

    @Test
    fun testLearning_SanitizesPiiBeforeStorage() = runBlocking {
        val category = "MESSAGING_WHATSAPP"
        val initialState = "pkg:com.whatsapp|screen:CHAT_LIST"

        val experienceWithPii = ExperienceRecord(
            id = UUID.randomUUID().toString(),
            taskCategory = category,
            generalizedInitialState = initialState,
            intendedOutcome = "SEND_TO_user@example.com_AND_+19876543210",
            actionsAttempted = listOf("open_whatsapp", "text_private_body_here"),
            stateTransitions = listOf("CHAT_LIST", "CONVERSATION"),
            successfulActions = listOf("open_whatsapp", "text_private_body_here"),
            finalOutcome = ExperienceOutcome.SUCCESS,
            executionDurationMs = 2000L
        )
        experienceRepo.recordExperience(experienceWithPii)

        val recalled = experienceRepo.recallStrategy(category, initialState)
        assertNotNull(recalled)
        // Verify phone and email are redacted
        assertFalse(recalled!!.intendedOutcome.contains("user@example.com"))
        assertFalse(recalled.intendedOutcome.contains("+19876543210"))
        assertTrue(recalled.intendedOutcome.contains("[EMAIL]"))
        assertTrue(recalled.intendedOutcome.contains("[PHONE]"))
    }

    // ==========================================
    // PHASE 5: STORAGE & SNAPSHOT CLEANUP
    // ==========================================

    @Test
    fun testSnapshotLifecycleManager_CleansUpOnTerminalStates() {
        val terminalStates = listOf("COMPLETED", "FAILED", "CANCELLED", "DELIVERY_UNKNOWN")

        for (state in terminalStates) {
            val taskId = "test-task-${UUID.randomUUID()}-$state"
            val workspace = lifecycleManager.getTaskWorkspace(taskId)
            assertTrue("Workspace must exist on disk", workspace.exists())

            // Create temporary heavy artifacts: screenshot, raw tree dump, AI debugging log
            val screenshot = lifecycleManager.saveTemporaryArtifact(taskId, "screen.raw", ByteArray(1024) { 0xFF.toByte() })
            val treeDump = lifecycleManager.saveTemporaryArtifact(taskId, "a11y_tree.json", "{\"nodes\": []}".toByteArray())
            val aiLog = lifecycleManager.saveTemporaryArtifact(taskId, "ai_prompt_debug.txt", "prompt and response".toByteArray())

            assertNotNull(screenshot)
            assertNotNull(treeDump)
            assertNotNull(aiLog)
            assertTrue(screenshot!!.exists())
            assertTrue(treeDump!!.exists())
            assertTrue(aiLog!!.exists())

            // Run terminal state cleanup
            val cleaned = lifecycleManager.cleanupTaskWorkspace(taskId)
            assertTrue("Cleanup must return true", cleaned)
            assertFalse("Workspace directory must be deleted after $state", workspace.exists())
            assertFalse("Screenshot file must be deleted", screenshot.exists())
            assertFalse("Tree dump must be deleted", treeDump.exists())
            assertFalse("AI debug log must be deleted", aiLog.exists())
        }
    }

    @Test
    fun testSnapshotLifecycleManager_CleansStaleWorkspacesOnStartup() {
        // Simulate interrupted task leaving orphaned directories
        val orphanTask1 = "orphan-1-${UUID.randomUUID()}"
        val orphanTask2 = "orphan-2-${UUID.randomUUID()}"

        val dir1 = lifecycleManager.getTaskWorkspace(orphanTask1)
        val dir2 = lifecycleManager.getTaskWorkspace(orphanTask2)
        lifecycleManager.saveTemporaryArtifact(orphanTask1, "leak.tmp", ByteArray(512))
        lifecycleManager.saveTemporaryArtifact(orphanTask2, "leak.tmp", ByteArray(512))

        assertTrue(dir1.exists())
        assertTrue(dir2.exists())

        // Run startup purge
        val count = lifecycleManager.cleanupStaleWorkspaces()
        assertTrue("Must clean at least 2 stale workspaces", count >= 2)
        assertFalse(dir1.exists())
        assertFalse(dir2.exists())
    }

    // ==========================================
    // PHASE 6: SCREEN CLASSIFICATION & ADAPTATION
    // ==========================================

    @Test
    fun testObservationClassifier_IdentifiesKeyScreensAccurately() {
        // Chat list screen
        val chatListSnapshot = UiSnapshot(
            snapshotId = "s1",
            timestamp = System.currentTimeMillis(),
            packageName = "com.whatsapp",
            elements = listOf(
                UiElement(id = "e1", className = "TextView", text = "Chats", bounds = UiBounds(0, 0, 100, 50)),
                UiElement(id = "e2", className = "ImageView", contentDescription = "Search", bounds = UiBounds(100, 0, 150, 50)),
                UiElement(id = "e3", className = "Button", contentDescription = "New chat", bounds = UiBounds(0, 500, 50, 550))
            ),
            totalNodesCount = 3,
            isTruncated = false,
            summary = "Chats, Search, New chat"
        )
        val chatListObs = ObservationClassifier.classify(chatListSnapshot)
        assertEquals(ScreenClassification.CHAT_LIST, chatListObs.screenClassification)

        // Conversation screen
        val conversationSnapshot = UiSnapshot(
            snapshotId = "s2",
            timestamp = System.currentTimeMillis(),
            packageName = "com.whatsapp",
            elements = listOf(
                UiElement(id = "e1", className = "EditText", text = "", contentDescription = "Message", isEditable = true, bounds = UiBounds(0, 500, 300, 550)),
                UiElement(id = "e2", className = "ImageButton", contentDescription = "Send", isClickable = true, bounds = UiBounds(300, 500, 350, 550))
            ),
            totalNodesCount = 2,
            isTruncated = false,
            summary = "Message input, Send"
        )
        val convObs = ObservationClassifier.classify(conversationSnapshot)
        assertEquals(ScreenClassification.CONVERSATION, convObs.screenClassification)

        // Settings screen
        val settingsSnapshot = UiSnapshot(
            snapshotId = "s3",
            timestamp = System.currentTimeMillis(),
            packageName = "com.android.settings",
            elements = listOf(
                UiElement(id = "e1", className = "TextView", text = "Settings", bounds = UiBounds(0, 0, 200, 50)),
                UiElement(id = "e2", className = "TextView", text = "Network & internet", bounds = UiBounds(0, 60, 200, 100))
            ),
            totalNodesCount = 2,
            isTruncated = false,
            summary = "Settings, Network & internet"
        )
        val settingsObs = ObservationClassifier.classify(settingsSnapshot)
        assertEquals(ScreenClassification.SETTINGS_MAIN, settingsObs.screenClassification)
    }
}
