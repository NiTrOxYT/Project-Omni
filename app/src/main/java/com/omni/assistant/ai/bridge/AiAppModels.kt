package com.omni.assistant.ai.bridge

enum class SupportedAiApp(
    val id: String,
    val displayName: String,
    val defaultPackageName: String
) {
    CHATGPT(
        id = "chatgpt",
        displayName = "ChatGPT",
        defaultPackageName = "com.openai.chatgpt"
    ),
    GEMINI(
        id = "gemini",
        displayName = "Google Gemini",
        defaultPackageName = "com.google.android.apps.bard"
    ),
    AUTO(
        id = "auto",
        displayName = "Auto-Detect Installed AI",
        defaultPackageName = ""
    )
}

data class InstalledAiApp(
    val app: SupportedAiApp,
    val isInstalled: Boolean,
    val packageName: String,
    val appLabel: String,
    val versionName: String? = null
)

data class AiBridgeQuery(
    val prompt: String,
    val targetApp: SupportedAiApp = SupportedAiApp.AUTO,
    val returnToPackage: String? = null,
    val timeoutMs: Long = 30000L
)

data class AiBridgeResponse(
    val success: Boolean,
    val prompt: String,
    val responseText: String?,
    val appUsed: String,
    val error: String? = null,
    val executionTimeMs: Long = 0
)
