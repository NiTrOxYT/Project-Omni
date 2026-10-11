package com.omni.assistant.accessibility.observation

import com.omni.assistant.accessibility.UiElement
import com.omni.assistant.accessibility.UiSnapshot

object ObservationClassifier {

    fun classify(snapshot: UiSnapshot): ScreenObservation {
        val elements = snapshot.elements
        val pkg = snapshot.packageName ?: ""
        val isTruncated = snapshot.isTruncated

        val status = when {
            elements.isEmpty() -> ObservationStatus.UNAVAILABLE
            isTruncated -> ObservationStatus.PARTIAL
            else -> ObservationStatus.COMPLETE
        }

        val editables = elements.filter { it.isEditable || it.className.contains("EditText", ignoreCase = true) }
        val clickables = elements.filter { it.isClickable }
        val prominentTexts = elements.mapNotNull { it.text ?: it.contentDescription }
            .filter { it.isNotBlank() && it != "[PROTECTED]" }
            .distinct()
            .take(6)

        val classification = determineClassification(pkg, elements, editables, clickables)
        val primaryTitle = extractPrimaryTitle(elements, prominentTexts)

        val compactSummary = buildCompactSummary(
            pkg = pkg,
            classification = classification,
            title = primaryTitle,
            editablesCount = editables.size,
            clickablesCount = clickables.size,
            prominentTexts = prominentTexts
        )

        return ScreenObservation(
            snapshotId = snapshot.snapshotId,
            timestamp = snapshot.timestamp,
            packageName = snapshot.packageName,
            screenClassification = classification,
            primaryTitle = primaryTitle,
            interactiveCount = editables.size + clickables.size,
            editableControls = editables,
            clickableControls = clickables,
            prominentTexts = prominentTexts,
            status = status,
            compactSummary = compactSummary,
            rawSnapshot = snapshot
        )
    }

    private fun determineClassification(
        pkg: String,
        elements: List<UiElement>,
        editables: List<UiElement>,
        clickables: List<UiElement>
    ): ScreenClassification {
        val pkgLower = pkg.lowercase()

        // 1. WhatsApp screens
        if (pkgLower.contains("whatsapp")) {
            val hasEntryField = elements.any {
                val res = (it.resourceId ?: "").lowercase()
                val text = (it.text ?: it.contentDescription ?: "").lowercase()
                res.endsWith(":id/entry") || text.contains("type a message") || text.contains("message")
            }
            val hasSendButton = elements.any {
                val res = (it.resourceId ?: "").lowercase()
                val desc = (it.contentDescription ?: "").lowercase()
                res.endsWith(":id/send") || desc == "send"
            }

            if (hasEntryField || hasSendButton) {
                return ScreenClassification.CONVERSATION
            }

            val hasSearchField = editables.any {
                val res = (it.resourceId ?: "").lowercase()
                val desc = (it.text ?: it.contentDescription ?: "").lowercase()
                res.contains("search") || desc.contains("search")
            }

            if (hasSearchField) {
                return if (elements.size > 10) ScreenClassification.SEARCH_RESULTS else ScreenClassification.SEARCH_ACTIVE
            }

            val hasChatsTabOrFab = elements.any {
                val text = (it.text ?: it.contentDescription ?: "").lowercase()
                text.contains("chats") || text.contains("new chat") || (it.resourceId ?: "").contains("fab")
            }
            if (hasChatsTabOrFab) {
                return ScreenClassification.CHAT_LIST
            }
        }

        // 2. Settings screens
        if (pkgLower.contains("settings")) {
            return ScreenClassification.SETTINGS_MAIN
        }

        // 3. Launcher / Home screens
        if (pkgLower.contains("launcher") || pkgLower.contains("home") || pkgLower.contains("quickstep")) {
            return ScreenClassification.APP_LAUNCHER_HOME
        }

        // 4. Form inputs
        if (editables.isNotEmpty()) {
            return ScreenClassification.INPUT_FORM
        }

        return ScreenClassification.UNKNOWN
    }

    private fun extractPrimaryTitle(elements: List<UiElement>, prominentTexts: List<String>): String? {
        val topElements = elements.filter { it.bounds.top < 400 }
        return topElements.firstNotNullOfOrNull { it.text ?: it.contentDescription }
            ?: prominentTexts.firstOrNull()
    }

    private fun buildCompactSummary(
        pkg: String,
        classification: ScreenClassification,
        title: String?,
        editablesCount: Int,
        clickablesCount: Int,
        prominentTexts: List<String>
    ): String {
        val app = if (pkg.isNotBlank()) pkg.substringAfterLast('.') else "Unknown"
        val titlePart = if (!title.isNullOrBlank()) " | Title: '$title'" else ""
        val sampleItems = if (prominentTexts.isNotEmpty()) " | Samples: [${prominentTexts.take(4).joinToString(", ")}]" else ""
        return "[$app] Screen: $classification$titlePart (Clickable: $clickablesCount, Editable: $editablesCount)$sampleItems"
    }
}
