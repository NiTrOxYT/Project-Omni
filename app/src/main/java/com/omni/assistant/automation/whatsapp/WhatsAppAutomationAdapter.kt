package com.omni.assistant.automation.whatsapp

import android.content.Context
import android.content.Intent
import com.omni.assistant.accessibility.AccessibilityConnectionManager
import com.omni.assistant.accessibility.UiElement
import com.omni.assistant.accessibility.UiSnapshot
import com.omni.assistant.automation.messaging.MessagingExecutionResult
import com.omni.assistant.automation.messaging.MessagingRequest
import com.omni.assistant.automation.messaging.MessagingState
import com.omni.assistant.core.logging.OmniLogger
import kotlinx.coroutines.delay
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

open class WhatsAppAutomationAdapter(
    private val context: Context? = null,
    private val experienceRepository: com.omni.assistant.domain.repository.ExperienceRepository? = null
) {
    private val tag = "WhatsAppAutomationAdapter"

    companion object {
        const val WHATSAPP_PACKAGE = "com.whatsapp"
        const val WHATSAPP_BUSINESS_PACKAGE = "com.whatsapp.w4b"
    }

    open fun getInstalledWhatsAppPackage(): String? {
        val pm = context?.packageManager ?: return null
        val candidates = listOf(WHATSAPP_PACKAGE, WHATSAPP_BUSINESS_PACKAGE)
        for (pkg in candidates) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return null
    }

    open suspend fun executeMessagingTask(
        request: MessagingRequest,
        autoSend: Boolean = false,
        onProgress: ((status: String) -> Unit)? = null
    ): MessagingExecutionResult {
        coroutineContext.ensureActive()

        try {
            // 1. Validation of Accessibility & WhatsApp installation
            if (!AccessibilityConnectionManager.isServiceConnected()) {
                return MessagingExecutionResult(
                    success = false,
                    state = MessagingState.FAILED,
                    recipient = request.recipient,
                    messageText = request.messageText,
                    summaryText = "Accessibility service is not enabled. Enable Omni in Accessibility Settings.",
                    error = "SERVICE_NOT_CONNECTED"
                )
            }

            val targetPackage = getInstalledWhatsAppPackage()
            if (targetPackage == null) {
                return MessagingExecutionResult(
                    success = false,
                    state = MessagingState.FAILED,
                    recipient = request.recipient,
                    messageText = request.messageText,
                    summaryText = "WhatsApp is not installed on this device.",
                    error = "WHATSAPP_NOT_INSTALLED"
                )
            }

            // 2. OPENING_APP
            onProgress?.invoke("Opening WhatsApp...")
            val pm = context?.packageManager
            val launchIntent = pm?.getLaunchIntentForPackage(targetPackage)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            }

            if (launchIntent == null) {
                return MessagingExecutionResult(
                    success = false,
                    state = MessagingState.FAILED,
                    recipient = request.recipient,
                    messageText = request.messageText,
                    summaryText = "Unable to create launch intent for WhatsApp.",
                    error = "NO_LAUNCH_INTENT"
                )
            }

            context?.startActivity(launchIntent)
            var snapshot = waitForForegroundPackage(targetPackage, timeoutMs = 6000L)
            if (!isWhatsAppPackage(snapshot.packageName ?: "")) {
                OmniLogger.w(tag, "WhatsApp not in foreground yet (${snapshot.packageName}), retrying launch...")
                context?.startActivity(launchIntent)
                snapshot = waitForForegroundPackage(targetPackage, timeoutMs = 4000L)
            }

            if (!isWhatsAppPackage(snapshot.packageName ?: "")) {
                return MessagingExecutionResult(
                    success = false,
                    state = MessagingState.FAILED,
                    recipient = request.recipient,
                    messageText = request.messageText,
                    summaryText = "Unable to bring WhatsApp to foreground (current app: ${snapshot.packageName ?: "unknown"}).",
                    error = "APP_NOT_IN_FOREGROUND"
                )
            }

            coroutineContext.ensureActive()

            // 3. RESOLVING_RECIPIENT & OPENING_CONVERSATION
            onProgress?.invoke("Checking screen for contact '${request.recipient}'...")

            // Check if we are already in the target conversation
            if (!isConversationWithRecipient(snapshot, request.recipient)) {
                // First check if contact is already visible on the current screen (e.g. top of chats list)
                val initialMatches = findContactMatches(snapshot, request.recipient)
                val initialTarget = pickBestContactMatch(initialMatches, request.recipient)

                val targetContact: UiElement = if (initialTarget != null) {
                    OmniLogger.i(tag, "Contact '${request.recipient}' found directly on active screen.")
                    initialTarget
                } else {
                    // Not visible on active screen -> use search or new chat FAB
                    onProgress?.invoke("Searching for contact '${request.recipient}'...")
                    val searchButton = findSearchButton(snapshot) ?: findFabButton(snapshot)
                    if (searchButton != null) {
                        AccessibilityConnectionManager.performTap(snapshot.snapshotId, searchButton.id)
                        snapshot = waitForSearchInputReady(timeoutMs = 3000L)
                    }

                    coroutineContext.ensureActive()

                    // Find search input field
                    val searchInput = findSearchInput(snapshot)
                    if (searchInput != null) {
                        AccessibilityConnectionManager.performEnterText(
                            snapshotId = snapshot.snapshotId,
                            elementId = searchInput.id,
                            text = request.recipient
                        )
                    }

                    // Poll for contact matches from search up to 5 seconds
                    val (freshSnapshot, searchCandidates) = waitForContactMatches(request.recipient, timeoutMs = 5000L)
                    snapshot = freshSnapshot

                    if (searchCandidates.isEmpty()) {
                        returnToOmni()
                        return MessagingExecutionResult(
                            success = false,
                            state = MessagingState.FAILED,
                            recipient = request.recipient,
                            messageText = request.messageText,
                            summaryText = "Contact '${request.recipient}' was not found in WhatsApp.",
                            error = "CONTACT_NOT_FOUND"
                        )
                    }

                    val bestMatch = pickBestContactMatch(searchCandidates, request.recipient)
                    if (bestMatch == null) {
                        val distinctNames = searchCandidates.mapNotNull { it.text ?: it.contentDescription }.filter { it.isNotBlank() }.distinct()
                        returnToOmni()
                        return MessagingExecutionResult(
                            success = false,
                            state = MessagingState.FAILED,
                            recipient = request.recipient,
                            messageText = request.messageText,
                            summaryText = "Multiple contacts match '${request.recipient}': ${distinctNames.take(3).joinToString(", ")}. Please specify the exact name.",
                            error = "AMBIGUOUS_RECIPIENT"
                        )
                    }
                    bestMatch
                }

                onProgress?.invoke("Opening conversation with '${request.recipient}'...")
                AccessibilityConnectionManager.performTap(snapshot.snapshotId, targetContact.id)
                snapshot = waitForConversationScreen(timeoutMs = 4000L)
            }

            coroutineContext.ensureActive()

            // 4. COMPOSING_MESSAGE
            onProgress?.invoke("Composing message...")
            val messageInputPair = waitForMessageInput(timeoutMs = 4000L)
            if (messageInputPair == null) {
                returnToOmni()
                return MessagingExecutionResult(
                    success = false,
                    state = MessagingState.FAILED,
                    recipient = request.recipient,
                    messageText = request.messageText,
                    summaryText = "Could not locate message input box in WhatsApp. Ensure conversation with '${request.recipient}' is open.",
                    error = "INPUT_BOX_NOT_FOUND"
                )
            }
            val (inputSnapshot, messageInput) = messageInputPair
            snapshot = inputSnapshot

            val enterResult = AccessibilityConnectionManager.performEnterText(
                snapshotId = snapshot.snapshotId,
                elementId = messageInput.id,
                text = request.messageText
            )

            if (!enterResult.success) {
                returnToOmni()
                return MessagingExecutionResult(
                    success = false,
                    state = MessagingState.FAILED,
                    recipient = request.recipient,
                    messageText = request.messageText,
                    summaryText = "Failed to enter message: ${enterResult.error ?: enterResult.message}",
                    error = enterResult.error
                )
            }

            delay(600)
            snapshot = enterResult.freshSnapshot ?: AccessibilityConnectionManager.takeSnapshot()

            // 5. READ-BACK VERIFICATION
            val updatedInput = findMessageInput(snapshot)
            val composedText = updatedInput?.text?.toString() ?: ""
            if (composedText.isNotBlank() && !composedText.equals(request.messageText, ignoreCase = false)) {
                OmniLogger.w(tag, "Composed text '$composedText' differed from requested '${request.messageText}'")
            }

            coroutineContext.ensureActive()

            // 6. SENDING or AWAITING_CONFIRMATION
            if (autoSend) {
                onProgress?.invoke("Sending message to '${request.recipient}'...")
                val sendBtnPair = waitForSendButton(messageInput, timeoutMs = 3000L)
                if (sendBtnPair != null) {
                    val (sendSnapshot, sendBtn) = sendBtnPair
                    snapshot = sendSnapshot
                    AccessibilityConnectionManager.performTap(snapshot.snapshotId, sendBtn.id)
                    delay(1200)
                    snapshot = AccessibilityConnectionManager.takeSnapshot()

                    // 7. VERIFYING SENT MESSAGE
                    val isSentVisible = verifySentMessage(snapshot, request.messageText)
                    returnToOmni()

                    val result = if (isSentVisible) {
                        MessagingExecutionResult(
                            success = true,
                            state = MessagingState.COMPLETED,
                            recipient = request.recipient,
                            messageText = request.messageText,
                            summaryText = "Successfully sent WhatsApp message to '${request.recipient}': \"${request.messageText}\""
                        )
                    } else {
                        MessagingExecutionResult(
                            success = true,
                            state = MessagingState.COMPLETED,
                            recipient = request.recipient,
                            messageText = request.messageText,
                            summaryText = "Send action completed for '${request.recipient}'. (Delivery status: DELIVERY_UNKNOWN)",
                            error = "DELIVERY_UNKNOWN"
                        )
                    }

                    // Record verified experience for WhatsApp messaging
                    experienceRepository?.recordExperience(
                        com.omni.assistant.domain.model.ExperienceRecord(
                            id = java.util.UUID.randomUUID().toString(),
                            taskCategory = "MESSAGING_WHATSAPP",
                            generalizedInitialState = "pkg:com.whatsapp|screen:CHAT_LIST",
                            intendedOutcome = "SEND_WHATSAPP_MESSAGE",
                            actionsAttempted = listOf("open_whatsapp", "search_contact", "open_conversation", "compose_message", "send_message"),
                            stateTransitions = listOf("CHAT_LIST", "SEARCH_ACTIVE", "CONVERSATION", "CONVERSATION_SENT"),
                            successfulActions = listOf("open_whatsapp", "search_contact", "open_conversation", "compose_message", "send_message"),
                            finalOutcome = com.omni.assistant.domain.model.ExperienceOutcome.SUCCESS,
                            executionDurationMs = 3000L
                        )
                    )

                    return result
                } else {
                    returnToOmni()
                    val failResult = MessagingExecutionResult(
                        success = false,
                        state = MessagingState.FAILED,
                        recipient = request.recipient,
                        messageText = request.messageText,
                        summaryText = "Message composed but send button could not be located.",
                        error = "SEND_BUTTON_NOT_FOUND"
                    )

                    experienceRepository?.recordExperience(
                        com.omni.assistant.domain.model.ExperienceRecord(
                            id = java.util.UUID.randomUUID().toString(),
                            taskCategory = "MESSAGING_WHATSAPP",
                            generalizedInitialState = "pkg:com.whatsapp|screen:CHAT_LIST",
                            intendedOutcome = "SEND_WHATSAPP_MESSAGE",
                            actionsAttempted = listOf("open_whatsapp", "search_contact", "open_conversation", "compose_message"),
                            stateTransitions = listOf("CHAT_LIST", "SEARCH_ACTIVE", "CONVERSATION"),
                            successfulActions = listOf("open_whatsapp", "search_contact", "open_conversation", "compose_message"),
                            failedActions = listOf("send_button_tap"),
                            finalOutcome = com.omni.assistant.domain.model.ExperienceOutcome.FAILURE,
                            failureCategory = "SEND_BUTTON_NOT_FOUND",
                            executionDurationMs = 3000L
                        )
                    )

                    return failResult
                }
            } else {
                // Prepared state awaiting user approval
                returnToOmni()
                return MessagingExecutionResult(
                    success = true,
                    state = MessagingState.AWAITING_CONFIRMATION,
                    recipient = request.recipient,
                    messageText = request.messageText,
                    summaryText = "Prepared WhatsApp message to '${request.recipient}': \"${request.messageText}\". Confirmation required before sending."
                )
            }
        } finally {
            AccessibilityConnectionManager.clearSnapshots()
        }
    }

    private suspend fun waitForForegroundPackage(targetPackage: String, timeoutMs: Long = 6000L): UiSnapshot {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            coroutineContext.ensureActive()
            val snapshot = AccessibilityConnectionManager.takeSnapshot()
            val pkg = snapshot.packageName ?: ""
            if (pkg == targetPackage || pkg.contains("whatsapp", ignoreCase = true)) {
                if (snapshot.elements.isNotEmpty()) {
                    return snapshot
                }
            }
            delay(400)
        }
        return AccessibilityConnectionManager.takeSnapshot()
    }

    private suspend fun waitForSearchInputReady(timeoutMs: Long = 3000L): UiSnapshot {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            coroutineContext.ensureActive()
            val snapshot = AccessibilityConnectionManager.takeSnapshot()
            if (findSearchInput(snapshot) != null) {
                return snapshot
            }
            delay(350)
        }
        return AccessibilityConnectionManager.takeSnapshot()
    }

    private suspend fun waitForContactMatches(recipient: String, timeoutMs: Long = 5000L): Pair<UiSnapshot, List<UiElement>> {
        val start = System.currentTimeMillis()
        var latestSnapshot = AccessibilityConnectionManager.takeSnapshot()
        while (System.currentTimeMillis() - start < timeoutMs) {
            coroutineContext.ensureActive()
            latestSnapshot = AccessibilityConnectionManager.takeSnapshot()
            val matches = findContactMatches(latestSnapshot, recipient)
            if (matches.isNotEmpty()) {
                return Pair(latestSnapshot, matches)
            }
            delay(400)
        }
        return Pair(latestSnapshot, findContactMatches(latestSnapshot, recipient))
    }

    private suspend fun waitForConversationScreen(timeoutMs: Long = 4000L): UiSnapshot {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            coroutineContext.ensureActive()
            val snapshot = AccessibilityConnectionManager.takeSnapshot()
            if (findMessageInput(snapshot) != null) {
                return snapshot
            }
            delay(400)
        }
        return AccessibilityConnectionManager.takeSnapshot()
    }

    private suspend fun waitForMessageInput(timeoutMs: Long = 4000L): Pair<UiSnapshot, UiElement>? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            coroutineContext.ensureActive()
            val snapshot = AccessibilityConnectionManager.takeSnapshot()
            val input = findMessageInput(snapshot)
            if (input != null) return Pair(snapshot, input)
            delay(400)
        }
        val finalSnap = AccessibilityConnectionManager.takeSnapshot()
        val finalInput = findMessageInput(finalSnap) ?: return null
        return Pair(finalSnap, finalInput)
    }

    private suspend fun waitForSendButton(inputField: UiElement, timeoutMs: Long = 3000L): Pair<UiSnapshot, UiElement>? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            coroutineContext.ensureActive()
            val snapshot = AccessibilityConnectionManager.takeSnapshot()
            val sendBtn = findSendButton(snapshot, inputField)
            if (sendBtn != null) return Pair(snapshot, sendBtn)
            delay(350)
        }
        val finalSnap = AccessibilityConnectionManager.takeSnapshot()
        val finalBtn = findSendButton(finalSnap, inputField) ?: return null
        return Pair(finalSnap, finalBtn)
    }

    fun isWhatsAppPackage(pkg: String): Boolean {
        return pkg == WHATSAPP_PACKAGE || pkg == WHATSAPP_BUSINESS_PACKAGE || pkg.contains("whatsapp", ignoreCase = true)
    }

    fun normalizeContactName(name: String): String {
        if (name.isBlank()) return ""
        return name
            .replace(Regex("[^\\p{L}\\p{Nd}\\s]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    private fun findSearchButton(snapshot: UiSnapshot): UiElement? {
        if (!isWhatsAppPackage(snapshot.packageName ?: "")) return null
        val keywords = listOf("search", "search…", "search contacts", "find", "ask meta ai")
        val idKeywords = listOf("search_bar", "search_holder", "menuitem_search", "action_search", "search_icon", "search_button")
        return snapshot.elements.find { elem ->
            val desc = (elem.contentDescription ?: elem.text ?: "").lowercase()
            val resId = (elem.resourceId ?: "").lowercase()
            val descMatch = keywords.any { desc.contains(it) }
            val idMatch = idKeywords.any { resId.contains(it) }
            (descMatch || idMatch) && (elem.isClickable || elem.bounds.top < 600)
        }
    }

    private fun findFabButton(snapshot: UiSnapshot): UiElement? {
        if (!isWhatsAppPackage(snapshot.packageName ?: "")) return null
        val fabKeywords = listOf("new chat", "select contact", "start chat", "chat")
        return snapshot.elements.find { elem ->
            val desc = (elem.contentDescription ?: elem.text ?: "").lowercase()
            val resId = (elem.resourceId ?: "").lowercase()
            resId.contains("fab") || (elem.isClickable && fabKeywords.any { desc.contains(it) })
        }
    }

    private fun findSearchInput(snapshot: UiSnapshot): UiElement? {
        if (!isWhatsAppPackage(snapshot.packageName ?: "")) return null
        val editables = snapshot.elements.filter { it.isEditable || it.className.contains("EditText", ignoreCase = true) }
        val searchKeywords = listOf("search", "search…", "type a name", "type name", "ask meta ai")
        val idKeywords = listOf("search_input", "search_src_text", "search")
        return editables.find { elem ->
            val desc = (elem.text ?: elem.contentDescription ?: "").lowercase()
            val resId = (elem.resourceId ?: "").lowercase()
            searchKeywords.any { desc.contains(it) } || idKeywords.any { resId.contains(it) }
        } ?: editables.firstOrNull()
    }

    private fun findContactMatches(snapshot: UiSnapshot, recipient: String): List<UiElement> {
        val currentPkg = snapshot.packageName ?: ""
        if (!isWhatsAppPackage(currentPkg)) return emptyList()

        val normRecipient = normalizeContactName(recipient)
        if (normRecipient.isBlank()) return emptyList()

        val blacklistedPhrases = listOf(
            "open whatsapp", "text ", "message to", "processing whatsapp",
            "search for", "ask meta ai", "meta ai", "type a name"
        )

        return snapshot.elements.filter { elem ->
            val rawText = (elem.text ?: elem.contentDescription ?: "").trim()
            val normText = normalizeContactName(rawText)
            val resId = (elem.resourceId ?: "").lowercase()

            // Exclude search input box itself or edit fields
            val isSearchBox = elem.isEditable || elem.className.contains("EditText", ignoreCase = true) || 
                              resId.contains("search_input") || resId.contains("search_src_text")
            if (isSearchBox) return@filter false

            // Exclude avatar / profile photo - clicking avatar opens photo dialog popup instead of chat
            if (resId.contains("contact_photo") || resId.contains("avatar") || resId.contains("profile_photo")) {
                return@filter false
            }

            // Exclude message preview/snippet views (e.g. single_msg_tv, msg_preview, last_msg)
            if (resId.contains("single_msg") || resId.contains("msg_preview") || resId.contains("last_msg")) {
                return@filter false
            }

            // Exclude blacklisted command/status phrases
            val rawLower = rawText.lowercase()
            if (blacklistedPhrases.any { rawLower.contains(it) } && !resId.contains("contact_name") && !resId.contains("conversations_row")) {
                return@filter false
            }

            // Exclude text that contains colons (indicates command format like 'command : message')
            if (rawText.contains(":")) return@filter false

            // Exclude text that is excessively long (contact names are concise)
            if (rawText.length > 40) return@filter false

            val isMatch = normText.contains(normRecipient) || rawText.contains(recipient.trim(), ignoreCase = true)
            if (!isMatch) return@filter false

            true
        }
    }

    fun pickBestContactMatch(candidates: List<UiElement>, recipient: String): UiElement? {
        val normRecipient = normalizeContactName(recipient)
        if (normRecipient.isBlank() || candidates.isEmpty()) return null

        // 1. Exact raw text match prioritizing contact name resource IDs
        val contactNameMatches = candidates.filter {
            val resId = (it.resourceId ?: "").lowercase()
            resId.contains("contact_name") || resId.contains("conversations_row") || resId.contains("chat_title")
        }
        val exactContactRow = contactNameMatches.find {
            val text = (it.text ?: it.contentDescription ?: "").trim()
            text.equals(recipient.trim(), ignoreCase = true) || normalizeContactName(text) == normRecipient
        }
        if (exactContactRow != null) return exactContactRow

        // 2. Exact raw text match (ignoring case)
        val rawExact = candidates.find {
            val text = (it.text ?: it.contentDescription ?: "").trim()
            text.equals(recipient.trim(), ignoreCase = true)
        }
        if (rawExact != null) return rawExact

        // 3. Exact normalized text match (e.g. "Priyanka 🫧" -> "priyanka" equals "priyanka")
        val normExactMatches = candidates.filter {
            val norm = normalizeContactName(it.text ?: it.contentDescription ?: "")
            norm == normRecipient
        }
        if (normExactMatches.isNotEmpty()) {
            return normExactMatches.find { 
                it.resourceId?.contains("contact_name") == true || it.resourceId?.contains("conversations_row") == true 
            } ?: normExactMatches.first()
        }

        // 4. Contact row resource id match
        if (contactNameMatches.size == 1) {
            return contactNameMatches.first()
        }

        // 5. Check distinct normalized names
        val distinctNormalized = candidates.map {
            normalizeContactName(it.text ?: it.contentDescription ?: "")
        }.filter { it.isNotBlank() }.distinct()

        if (distinctNormalized.size == 1) {
            return candidates.find { it.resourceId?.contains("contact_name") == true } ?: candidates.first()
        }

        // 6. If all matching items belong to the same base name (e.g., prefix match)
        val matchingPrefixes = distinctNormalized.filter { it.startsWith(normRecipient) || normRecipient.startsWith(it) }
        if (matchingPrefixes.size == 1) {
            return candidates.find {
                normalizeContactName(it.text ?: it.contentDescription ?: "") == matchingPrefixes.first()
            }
        }

        // 7. If only 1 candidate remains after filtering
        if (candidates.size == 1) {
            return candidates.first()
        }

        // Truly ambiguous with multiple distinctly named people
        return null
    }

    private fun isConversationWithRecipient(snapshot: UiSnapshot, recipient: String): Boolean {
        if (!isWhatsAppPackage(snapshot.packageName ?: "")) return false
        // Must have message input field and no chats list navigation tabs
        val hasMessageInput = findMessageInput(snapshot) != null
        val hasChatsTabs = snapshot.elements.any { elem ->
            val desc = (elem.contentDescription ?: elem.text ?: "").lowercase()
            desc == "chats" || desc == "updates" || desc == "communities" || desc == "calls"
        }
        if (hasChatsTabs || !hasMessageInput) return false

        val normRecipient = normalizeContactName(recipient)
        if (normRecipient.isBlank()) return false
        return snapshot.elements.any { elem ->
            val normText = normalizeContactName(elem.text ?: elem.contentDescription ?: "")
            (normText == normRecipient || normText.contains(normRecipient)) && elem.bounds.top < 500
        }
    }

    private fun findMessageInput(snapshot: UiSnapshot): UiElement? {
        if (!isWhatsAppPackage(snapshot.packageName ?: "")) return null
        val editables = snapshot.elements.filter { it.isEditable || it.className.contains("EditText", ignoreCase = true) }
        if (editables.size == 1) return editables.first()

        val messageKeywords = listOf("message", "type a message", "type message", "chat")
        val match = editables.find { elem ->
            val desc = (elem.text ?: elem.contentDescription ?: "").lowercase()
            val resId = (elem.resourceId ?: "").lowercase()
            resId.endsWith(":id/entry") || messageKeywords.any { desc.contains(it) }
        }
        if (match != null) return match

        // Return the lowest editable element on screen (chat input is at bottom)
        return editables.maxByOrNull { it.bounds.bottom }
    }

    private fun findSendButton(snapshot: UiSnapshot, inputField: UiElement): UiElement? {
        if (!isWhatsAppPackage(snapshot.packageName ?: "")) return null
        val sendKeywords = listOf("send", "send message", "submit")
        val candidate = snapshot.elements.find { elem ->
            val desc = (elem.contentDescription ?: elem.text ?: "").lowercase()
            val resId = (elem.resourceId ?: "").lowercase()
            resId.endsWith(":id/send") || resId.contains("send_btn") || (elem.isClickable && sendKeywords.any { desc == it || desc.startsWith("send") })
        }
        if (candidate != null) return candidate

        // Search for clickable icon adjacent to input field in the bottom region
        return snapshot.elements.find { elem ->
            elem.isClickable && elem.id != inputField.id &&
                    elem.bounds.top >= inputField.bounds.top - 100 &&
                    elem.bounds.bottom <= inputField.bounds.bottom + 100 &&
                    elem.bounds.left >= inputField.bounds.left
        }
    }

    private fun verifySentMessage(snapshot: UiSnapshot, expectedText: String): Boolean {
        if (!isWhatsAppPackage(snapshot.packageName ?: "")) return false
        return snapshot.elements.any { elem ->
            val text = elem.text ?: elem.contentDescription ?: ""
            text.contains(expectedText)
        }
    }

    open suspend fun returnToOmni() {
        val pm = context?.packageManager ?: return
        val omniIntent = pm.getLaunchIntentForPackage("com.omni.assistant.debug")?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (omniIntent != null) {
            context?.startActivity(omniIntent)
        } else {
            AccessibilityConnectionManager.performNavigateBack()
        }
    }
}
