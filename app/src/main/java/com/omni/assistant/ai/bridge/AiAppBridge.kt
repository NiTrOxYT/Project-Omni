package com.omni.assistant.ai.bridge

import android.content.Context
import android.content.Intent
import com.omni.assistant.accessibility.AccessibilityConnectionManager
import com.omni.assistant.accessibility.UiElement
import com.omni.assistant.accessibility.UiSnapshot
import com.omni.assistant.core.logging.OmniLogger
import kotlinx.coroutines.delay

open class AiAppBridge(
    private val context: Context? = null
) {
    private val tag = "AiAppBridge"

    companion object {
        const val CHATGPT_PACKAGE = "com.openai.chatgpt"
        const val GEMINI_PACKAGE = "com.google.android.apps.bard"
        const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
        const val MAX_POLL_DURATION_MS = 25_000L
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

        context.startActivity(launchIntent)

        // Wait for AI app window to become active in Accessibility
        var isAppForeground = false
        val maxWaitLaunch = 6_000L
        val launchStart = System.currentTimeMillis()

        while (System.currentTimeMillis() - launchStart < maxWaitLaunch) {
            delay(500)
            val snapshot = AccessibilityConnectionManager.takeSnapshot()
            if (snapshot.packageName == targetPackage || (snapshot.packageName ?: "").contains(targetPackage.substringAfterLast('.'))) {
                isAppForeground = true
                break
            }
        }

        if (!isAppForeground) {
            OmniLogger.w(tag, "App $targetPackage did not become foreground in time.")
        }

        onProgress?.invoke("Waiting for $appDisplayName interface...")
        delay(1000)

        var snapshot = AccessibilityConnectionManager.takeSnapshot()

        // Find input text field
        val inputElement = findPromptInputField(snapshot)
        if (inputElement == null) {
            OmniLogger.w(tag, "Could not locate prompt input field in $appDisplayName snapshot.")
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

        delay(400)
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
            delay(POLL_INTERVAL_MS)
            val freshSnap = AccessibilityConnectionManager.takeSnapshot()

            val extracted = extractAiResponseText(freshSnap, query.prompt, targetPackage)
            if (!extracted.isNullOrBlank()) {
                if (extracted == lastCandidateText) {
                    stableCount++
                    // If text remained stable for 2 polls (~1.6s) and is non-empty, generation is finished
                    if (stableCount >= 2 && extracted.length > 15) {
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
            val returnIntent = pm.getLaunchIntentForPackage(query.returnToPackage)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (returnIntent != null) {
                context.startActivity(returnIntent)
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
                error = "Timed out waiting for response from $appDisplayName.",
                executionTimeMs = totalTime
            )
        }
    }

    private fun findPromptInputField(snapshot: UiSnapshot): UiElement? {
        val editables = snapshot.elements.filter { it.isEditable }
        if (editables.size == 1) return editables.first()

        val promptKeywords = listOf("message", "ask", "prompt", "type", "search", "anything", "chatgpt", "gemini")
        val match = editables.find { elem ->
            val text = (elem.text ?: elem.contentDescription ?: "").lowercase()
            promptKeywords.any { text.contains(it) }
        }
        if (match != null) return match

        // Return the lowest editable on screen (chat input is typically at bottom)
        return editables.maxByOrNull { it.bounds.bottom }
    }

    private fun findSendButton(snapshot: UiSnapshot, inputField: UiElement): UiElement? {
        val sendKeywords = listOf("send", "submit", "arrow", "post", "enter", "mic")
        val candidate = snapshot.elements.find { elem ->
            if (!elem.isClickable) return@find false
            val text = (elem.text ?: elem.contentDescription ?: "").lowercase()
            sendKeywords.any { text.contains(it) }
        }
        if (candidate != null) return candidate

        // Search for clickable button horizontally adjacent to input field
        return snapshot.elements.find { elem ->
            elem.isClickable && elem.id != inputField.id &&
                    elem.bounds.top >= inputField.bounds.top - 50 &&
                    elem.bounds.bottom <= inputField.bounds.bottom + 50 &&
                    elem.bounds.left >= inputField.bounds.left
        }
    }

    private fun extractAiResponseText(snapshot: UiSnapshot, prompt: String, targetPackage: String): String? {
        val promptClean = prompt.trim().lowercase()

        // Candidate response elements: text-bearing views that do not match the prompt or system navigation labels
        val textElements = snapshot.elements.filter { elem ->
            val t = elem.text?.trim() ?: ""
            t.isNotBlank() &&
                    t != "[PROTECTED]" &&
                    !t.lowercase().contains(promptClean) &&
                    !t.equals("ChatGPT", ignoreCase = true) &&
                    !t.equals("Gemini", ignoreCase = true) &&
                    !t.equals("Message", ignoreCase = true) &&
                    !t.equals("Send", ignoreCase = true) &&
                    t.length > 5
        }

        if (textElements.isEmpty()) return null

        // In chat apps, the latest response is usually near the bottom above the input bar
        val longestOrLatest = textElements.maxByOrNull { it.text?.length ?: 0 }
        return longestOrLatest?.text
    }
}
