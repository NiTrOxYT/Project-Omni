package com.omni.assistant.ai.bridge

import android.content.Context
import android.content.Intent
import com.omni.assistant.accessibility.AccessibilityConnectionManager
import com.omni.assistant.accessibility.UiElement
import com.omni.assistant.accessibility.UiSnapshot
import com.omni.assistant.core.logging.OmniLogger
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

open class AiAppBridge(
    private val context: Context? = null
) {
    private val tag = "AiAppBridge"

    companion object {
        const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        const val GEMINI_PACKAGE = "com.google.android.apps.bard"
        const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
        const val MAX_POLL_DURATION_MS = 28_000L
        const val POLL_INTERVAL_MS = 800L
    }

    open fun discoverInstalledAiApps(): List<InstalledAiApp> {
        val pm = context?.packageManager ?: return emptyList()
        val candidates = listOf(
            SupportedAiApp.CHATGPT to CHATGPT_PACKAGE,
            SupportedAiApp.GEMINI to GEMINI_PACKAGE
        )

        return candidates.map { (app, pkg) ->
            try {
                val appInfo = pm.getApplicationInfo(pkg, 0)
                val packageInfo = pm.getPackageInfo(pkg, 0)
                val label = pm.getApplicationLabel(appInfo).toString()
                InstalledAiApp(
                    app = app,
                    isInstalled = true,
                    packageName = pkg,
                    appLabel = label,
                    versionName = packageInfo.versionName
                )
            } catch (_: Exception) {
                InstalledAiApp(
                    app = app,
                    isInstalled = false,
                    packageName = pkg,
                    appLabel = app.displayName,
                    versionName = null
                )
            }
        }
    }

    open fun getPreferredOrAvailablePackage(preferred: SupportedAiApp): String? {
        val installed = discoverInstalledAiApps().filter { it.isInstalled }
        if (installed.isEmpty()) return null

        return when (preferred) {
            SupportedAiApp.CHATGPT -> installed.find { it.app == SupportedAiApp.CHATGPT }?.packageName ?: installed.first().packageName
            SupportedAiApp.GEMINI -> installed.find { it.app == SupportedAiApp.GEMINI }?.packageName ?: installed.first().packageName
            SupportedAiApp.AUTO -> installed.find { it.app == SupportedAiApp.CHATGPT }?.packageName ?: installed.first().packageName
        }
    }

    open suspend fun askInstalledAiApp(
        query: AiBridgeQuery,
        onProgress: ((statusMessage: String) -> Unit)? = null
    ): AiBridgeResponse {
        val startTime = System.currentTimeMillis()

        if (!AccessibilityConnectionManager.isServiceConnected()) {
            return AiBridgeResponse(
                success = false,
                prompt = query.prompt,
                responseText = null,
                appUsed = "none",
                error = "Omni Accessibility Service is not connected. Enable Omni in Android Accessibility Settings to use on-device AI apps."
            )
        }

        val targetPackage = getPreferredOrAvailablePackage(query.targetApp)
        if (targetPackage == null) {
            return AiBridgeResponse(
                success = false,
                prompt = query.prompt,
                responseText = null,
                appUsed = "none",
                error = "No supported AI application (ChatGPT or Google Gemini) is installed on this device."
            )
        }

        val appDisplayName = if (targetPackage == CHATGPT_PACKAGE) "ChatGPT" else "Google Gemini"
        OmniLogger.i(tag, "Launching $appDisplayName ($targetPackage) for query: '${query.prompt}'")
        onProgress?.invoke("Opening $appDisplayName...")

        val pm = context?.packageManager
        val launchIntent = pm?.getLaunchIntentForPackage(targetPackage)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (launchIntent == null) {
            return AiBridgeResponse(
                success = false,
                prompt = query.prompt,
                responseText = null,
                appUsed = appDisplayName,
                error = "Unable to create launch intent for $appDisplayName."
            )
        }

        context?.startActivity(launchIntent)

        // Wait for AI app window to become active in Accessibility
        var isAppForeground = false
        val maxWaitLaunch = 6_000L
        val launchStart = System.currentTimeMillis()

        while (System.currentTimeMillis() - launchStart < maxWaitLaunch) {
            coroutineContext.ensureActive()
            delay(500)
            val snapshot = AccessibilityConnectionManager.takeSnapshot()
            val currentPkg = snapshot.packageName ?: ""
            if (currentPkg == targetPackage || currentPkg.contains(targetPackage.substringAfterLast('.'))) {
                isAppForeground = true
                break
            }
        }

        if (!isAppForeground) {
            OmniLogger.w(tag, "App $targetPackage did not become foreground in time.")
        }

        onProgress?.invoke("Waiting for $appDisplayName interface...")

        var snapshot = AccessibilityConnectionManager.takeSnapshot()
        var inputElement: UiElement? = null
        val findInputStart = System.currentTimeMillis()
        val maxFindInputDuration = 8_000L

        while (System.currentTimeMillis() - findInputStart < maxFindInputDuration) {
            coroutineContext.ensureActive()
            snapshot = AccessibilityConnectionManager.takeSnapshot()

            OmniLogger.d(tag, "Polling $appDisplayName for input: elements=${snapshot.elements.size}, pkg=${snapshot.packageName}")

            // Check if navigation drawer is open (e.g. "Chat" button with pencil icon)
            val drawerChatButton = snapshot.elements.find { elem ->
                val text = (elem.text ?: elem.contentDescription ?: "").lowercase()
                (text == "chat" || text == "new chat") && elem.isClickable && elem.bounds.bottom > 2000
            }
            if (drawerChatButton != null && snapshot.elements.none { it.isEditable }) {
                OmniLogger.i(tag, "Drawer or new chat button detected in $appDisplayName. Tapping to open chat.")
                AccessibilityConnectionManager.performTap(snapshot.snapshotId, drawerChatButton.id)
                delay(800)
                snapshot = AccessibilityConnectionManager.takeSnapshot()
            }

            val candidate = findPromptInputField(snapshot)
            if (candidate != null) {
                if (!candidate.isEditable && !candidate.className.contains("EditText", ignoreCase = true)) {
                    // Tap placeholder view (e.g. "+ Ask ChatGPT") to activate the real EditText
                    OmniLogger.i(tag, "Tapping placeholder input field '${candidate.text ?: candidate.contentDescription}' to activate editor.")
                    AccessibilityConnectionManager.performTap(snapshot.snapshotId, candidate.id)
                    delay(600)
                    snapshot = AccessibilityConnectionManager.takeSnapshot()
                    inputElement = findPromptInputField(snapshot) ?: candidate
                } else {
                    inputElement = candidate
                }

                if (inputElement != null) {
                    break
                }
            }
            delay(500)
        }

        if (inputElement == null) {
            OmniLogger.w(tag, "Could not locate prompt input field in $appDisplayName snapshot. Total elements: ${snapshot.elements.size}")
            snapshot.elements.take(20).forEach {
                OmniLogger.w(tag, "  -> [${it.id}] ${it.className} text='${it.text}' desc='${it.contentDescription}' edit=${it.isEditable} click=${it.isClickable} bounds=${it.bounds.toCompactString()}")
            }
            return AiBridgeResponse(
                success = false,
                prompt = query.prompt,
                responseText = null,
                appUsed = appDisplayName,
                error = "Could not locate prompt input box in $appDisplayName. Ensure the app is signed in and on the chat screen."
            )
        }

        onProgress?.invoke("Entering prompt into $appDisplayName...")
        val enterResult = AccessibilityConnectionManager.performEnterText(
            snapshotId = snapshot.snapshotId,
            elementId = inputElement.id,
            text = query.prompt
        )

        if (!enterResult.success) {
            return AiBridgeResponse(
                success = false,
                prompt = query.prompt,
                responseText = null,
                appUsed = appDisplayName,
                error = "Failed to input prompt into $appDisplayName: ${enterResult.error ?: enterResult.message}"
            )
        }

        delay(500)
        snapshot = enterResult.freshSnapshot ?: AccessibilityConnectionManager.takeSnapshot()

        // Find and tap send button
        val sendButton = findSendButton(snapshot, inputElement)
        if (sendButton != null) {
            onProgress?.invoke("Submitting prompt...")
            AccessibilityConnectionManager.performTap(snapshot.snapshotId, sendButton.id)
        } else {
            OmniLogger.w(tag, "Send button not found; attempting tap on right side of input field.")
        }

        onProgress?.invoke("Waiting for $appDisplayName to generate response...")

        // Bounded polling for AI response
        var latestExtractedText: String? = null
        var lastCandidateText = ""
        var stableCount = 0
        val pollStart = System.currentTimeMillis()

        while (System.currentTimeMillis() - pollStart < MAX_POLL_DURATION_MS) {
            coroutineContext.ensureActive()
            delay(POLL_INTERVAL_MS)
            val freshSnap = AccessibilityConnectionManager.takeSnapshot()

            val isGenerating = isStillGenerating(freshSnap)
            val extracted = extractAiResponseText(freshSnap, query.prompt, targetPackage)

            if (!extracted.isNullOrBlank()) {
                if (extracted == lastCandidateText && !isGenerating) {
                    stableCount++
                    // If text remained stable for 2 polls (~1.6s) and is non-empty, generation is finished
                    if (stableCount >= 2 && extracted.length > 10) {
                        latestExtractedText = extracted
                        break
                    }
                } else {
                    stableCount = 0
                    lastCandidateText = extracted
                }
            }
        }

        val finalAnswer = latestExtractedText ?: lastCandidateText.ifBlank { null }

        // Return to caller app if requested
        if (!query.returnToPackage.isNullOrBlank() && query.returnToPackage != targetPackage) {
            onProgress?.invoke("Returning to Omni...")
            val returnIntent = pm?.getLaunchIntentForPackage(query.returnToPackage)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (returnIntent != null) {
                context?.startActivity(returnIntent)
            } else {
                AccessibilityConnectionManager.performNavigateBack()
            }
            delay(600)
        }

        val totalTime = System.currentTimeMillis() - startTime

        return if (!finalAnswer.isNullOrBlank()) {
            AiBridgeResponse(
                success = true,
                prompt = query.prompt,
                responseText = finalAnswer,
                appUsed = appDisplayName,
                executionTimeMs = totalTime
            )
        } else {
            AiBridgeResponse(
                success = false,
                prompt = query.prompt,
                responseText = null,
                appUsed = appDisplayName,
                error = "Timed out waiting for response from $appDisplayName. Ensure the app is signed in and has internet access.",
                executionTimeMs = totalTime
            )
        }
    }

    private fun findPromptInputField(snapshot: UiSnapshot): UiElement? {
        val editables = snapshot.elements.filter {
            it.isEditable || it.className.contains("EditText", ignoreCase = true)
        }
        if (editables.size == 1) return editables.first()

        val promptKeywords = listOf("message", "ask", "prompt", "type", "search", "anything", "chatgpt", "gemini", "write", "reply")
        val match = editables.find { elem ->
            val desc = (elem.text ?: elem.contentDescription ?: "").lowercase()
            promptKeywords.any { desc.contains(it) }
        }
        if (match != null) return match

        if (editables.isNotEmpty()) {
            return editables.maxByOrNull { it.bounds.bottom }
        }

        // Fallback 1: search for placeholder views (e.g. "+ Ask ChatGPT", "Message", "Write anything", "Reply to ChatGPT")
        val placeholder = snapshot.elements.find { elem ->
            val desc = (elem.text ?: elem.contentDescription ?: "").lowercase()
            (elem.isClickable || elem.className.contains("TextView") || elem.className.contains("View")) &&
                    promptKeywords.any { desc.contains(it) } &&
                    elem.bounds.bottom > 1200
        }
        if (placeholder != null) return placeholder

        // Fallback 2: In chat apps, the lowest wide clickable view is the prompt bar container
        return snapshot.elements.filter {
            it.isClickable && (it.bounds.right - it.bounds.left > 600) && it.bounds.bottom > 1000
        }.maxByOrNull { it.bounds.bottom }
    }

    private fun findSendButton(snapshot: UiSnapshot, inputField: UiElement): UiElement? {
        val sendKeywords = listOf("send message", "send prompt", "send query", "send", "submit", "arrow", "post", "enter", "up")
        // 1. Check by content description or text (Compose icon nodes often have clickable=false, but performTap handles parent/gestures)
        val candidate = snapshot.elements.find { elem ->
            val desc = (elem.contentDescription ?: elem.text ?: "").lowercase()
            sendKeywords.any { desc.contains(it) } &&
                    !desc.contains("voice") && !desc.contains("recording") && !desc.contains("dictation")
        }
        if (candidate != null) return candidate

        // 2. Search for clickable button horizontally adjacent to input field in bottom region
        val adjacent = snapshot.elements.filter { elem ->
            elem.isClickable && elem.id != inputField.id &&
                    elem.bounds.top >= inputField.bounds.top - 150 &&
                    elem.bounds.bottom <= inputField.bounds.bottom + 150 &&
                    elem.bounds.left >= inputField.bounds.left &&
                    !(elem.contentDescription ?: "").lowercase().let { it.contains("dictation") || it.contains("voice") || it.contains("record") }
        }.maxByOrNull { it.bounds.left }
        if (adjacent != null) return adjacent

        // 3. Fallback: rightmost clickable button in bottom right corner (x > 850, bottom > 1000), excluding voice/dictation
        return snapshot.elements.filter { elem ->
            elem.isClickable && elem.id != inputField.id && elem.bounds.left > 850 && elem.bounds.bottom > 1000 &&
                    !(elem.contentDescription ?: "").lowercase().let { it.contains("dictation") || it.contains("voice") || it.contains("record") }
        }.maxByOrNull { it.bounds.left }
    }

    private fun isStillGenerating(snapshot: UiSnapshot): Boolean {
        return snapshot.elements.any { elem ->
            val desc = (elem.text ?: elem.contentDescription ?: "").lowercase()
            desc.contains("stop generating") || desc.contains("stop response") || desc == "stop"
        }
    }

    private fun extractAiResponseText(snapshot: UiSnapshot, prompt: String, targetPackage: String): String? {
        val promptClean = prompt.trim().lowercase()
        val ignoredTerms = listOf(
            "chatgpt", "gemini", "openai", "google", "message", "ask gemini", "send", "submit",
            "copy", "share", "read aloud", "good response", "bad response", "regenerate", "edit",
            "more options", "new chat", "attach", "drawer", "menu", "navigate up", "back",
            "[protected]", "create an image", "write or edit", "reply to chatgpt", "ask chatgpt",
            "cancel recording", "stop recording", "tap to send recording", "dictation"
        )

        // Candidate response elements: text-bearing views that do not match the prompt or system navigation labels
        val textElements = snapshot.elements.filter { elem ->
            val t = elem.text?.trim() ?: ""
            if (t.length < 3) return@filter false
            val tLower = t.lowercase()

            // Exclude exact match with user prompt
            if (tLower == promptClean) return@filter false
            // Exclude editable input field still containing text
            if (elem.isEditable && tLower.contains(promptClean)) return@filter false
            // Exclude system controls
            if (ignoredTerms.any { tLower == it || tLower.startsWith("$it ") || tLower.endsWith(" $it") }) return@filter false
            true
        }

        if (textElements.isEmpty()) return null

        // In chat apps, the latest response is usually the longest or bottom-most text bubble above the input box
        val sortedByPosition = textElements.sortedByDescending { it.bounds.bottom }
        val latest = sortedByPosition.firstOrNull() ?: textElements.maxByOrNull { it.text?.length ?: 0 }

        return latest?.text
    }
}
