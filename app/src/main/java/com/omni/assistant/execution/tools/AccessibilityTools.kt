package com.omni.assistant.execution.tools

import android.content.Context
import android.content.Intent
import com.omni.assistant.accessibility.AccessibilityConnectionManager
import com.omni.assistant.accessibility.AccessibilityStatus
import com.omni.assistant.core.logging.OmniLogger
import com.omni.assistant.device.AppInventoryProvider
import com.omni.assistant.domain.model.RiskLevel
import com.omni.assistant.execution.OmniTool
import com.omni.assistant.execution.ToolDefinition
import com.omni.assistant.execution.ToolParameter
import com.omni.assistant.execution.ToolResult

class GetAccessibilityStatusTool(
    private val context: Context
) : OmniTool {
    override val definition = ToolDefinition(
        name = "get_accessibility_status",
        description = "Checks whether Omni Accessibility Service is enabled in Android settings and currently connected.",
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            AccessibilityConnectionManager.checkAndRefreshStatus(context)
            val isEnabledInSettings = AccessibilityConnectionManager.isServiceEnabledInSettings(context)
            val isConnected = AccessibilityConnectionManager.isServiceConnected()

            val statusStr = when {
                isConnected -> "CONNECTED (Ready for UI automation)"
                isEnabledInSettings -> "DISCONNECTED (Enabled in settings but service process not attached)"
                else -> "NOT_ENABLED (Must be enabled in Android Accessibility Settings)"
            }

            val instruction = if (!isConnected) {
                "Open Android Settings -> Accessibility -> Downloaded Apps -> Omni, and enable the service."
            } else {
                "Accessibility service is active and ready."
            }

            val data = mapOf(
                "status" to statusStr,
                "isEnabled" to isEnabledInSettings,
                "isConnected" to isConnected,
                "instruction" to instruction
            )

            ToolResult(
                success = true,
                tool = definition.name,
                data = data,
                summaryText = "Accessibility Status: $statusStr. $instruction"
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                tool = definition.name,
                summaryText = "Failed to check accessibility status: ${e.message}",
                error = e.localizedMessage ?: "Unknown error"
            )
        }
    }
}

class GetUiSnapshotTool : OmniTool {
    override val definition = ToolDefinition(
        name = "get_ui_snapshot",
        description = "Captures a structured, compact snapshot of interactive and visible elements on the active Android screen.",
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            if (!AccessibilityConnectionManager.isServiceConnected()) {
                return ToolResult(
                    success = false,
                    tool = definition.name,
                    summaryText = "Accessibility service is not connected. Please enable Omni in Android Accessibility Settings.",
                    error = "ACCESSIBILITY_SERVICE_NOT_CONNECTED"
                )
            }

            val snapshot = AccessibilityConnectionManager.takeSnapshot()
            val data = snapshot.toCompactJsonMap()

            ToolResult(
                success = true,
                tool = definition.name,
                data = data,
                summaryText = "UI Snapshot captured: ${snapshot.summary}"
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                tool = definition.name,
                summaryText = "Failed to capture UI snapshot: ${e.message}",
                error = e.localizedMessage ?: "Unknown error"
            )
        }
    }
}

class LaunchAppTool(
    private val context: Context,
    private val appInventoryProvider: AppInventoryProvider
) : OmniTool {
    override val definition = ToolDefinition(
        name = "launch_app",
        description = "Launches an installed Android application by package name or friendly application name (e.g. 'Settings', 'Camera', 'com.android.settings').",
        riskLevel = RiskLevel.LOW,
        parameters = listOf(
            ToolParameter(
                name = "appName",
                type = "string",
                description = "Name of the app (e.g. 'Settings', 'Chrome', 'Calculator', 'Camera')",
                isRequired = false
            ),
            ToolParameter(
                name = "packageName",
                type = "string",
                description = "Exact Android package name (e.g. 'com.android.settings')",
                isRequired = false
            )
        )
    )

    private val tag = "LaunchAppTool"

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            val appNameQuery = parameters["appName"]?.toString()?.trim()
            val packageQuery = parameters["packageName"]?.toString()?.trim()

            if (appNameQuery.isNullOrBlank() && packageQuery.isNullOrBlank()) {
                return ToolResult(
                    success = false,
                    tool = definition.name,
                    summaryText = "Please provide an appName or packageName to launch.",
                    error = "MISSING_ARGUMENTS"
                )
            }

            val packageManager = context.packageManager
            var resolvedPackage: String? = null
            var resolvedLabel: String? = null

            if (!packageQuery.isNullOrBlank()) {
                val launchIntent = packageManager.getLaunchIntentForPackage(packageQuery)
                if (launchIntent != null) {
                    resolvedPackage = packageQuery
                    resolvedLabel = try {
                        val appInfo = packageManager.getApplicationInfo(packageQuery, 0)
                        packageManager.getApplicationLabel(appInfo).toString()
                    } catch (_: Exception) { packageQuery }
                }
            }

            if (resolvedPackage == null && !appNameQuery.isNullOrBlank()) {
                val cleanQuery = appNameQuery.trim()
                val installedApps = appInventoryProvider.getVisibleInstalledApplications()
                val match = installedApps.find { it.appName.equals(cleanQuery, ignoreCase = true) }
                    ?: installedApps.find { it.appName.contains(cleanQuery, ignoreCase = true) }
                    ?: installedApps.find { cleanQuery.contains(it.appName, ignoreCase = true) }
                    ?: installedApps.find { it.packageName.contains(cleanQuery, ignoreCase = true) }

                if (match != null) {
                    resolvedPackage = match.packageName
                    resolvedLabel = match.appName
                } else {
                    // Fallback to well-known application aliases
                    val knownAliases = mapOf(
                        "chatgpt" to listOf("com.openai.chatgpt"),
                        "openai" to listOf("com.openai.chatgpt"),
                        "gemini" to listOf("com.google.android.apps.bard", "com.google.android.googlequicksearchbox"),
                        "bard" to listOf("com.google.android.apps.bard"),
                        "settings" to listOf("com.android.settings"),
                        "chrome" to listOf("com.android.chrome"),
                        "youtube" to listOf("com.google.android.youtube"),
                        "maps" to listOf("com.google.android.apps.maps"),
                        "play store" to listOf("com.android.vending"),
                        "playstore" to listOf("com.android.vending"),
                        "camera" to listOf("com.android.camera", "com.oneplus.camera", "com.google.android.GoogleCamera"),
                        "photos" to listOf("com.google.android.apps.photos", "com.oneplus.gallery", "com.android.gallery3d"),
                        "gallery" to listOf("com.oneplus.gallery", "com.google.android.apps.photos", "com.android.gallery3d"),
                        "calculator" to listOf("com.google.android.calculator", "com.oneplus.calculator", "com.android.calculator2"),
                        "clock" to listOf("com.google.android.deskclock", "com.oneplus.deskclock", "com.android.deskclock"),
                        "messages" to listOf("com.google.android.apps.messaging", "com.android.mms"),
                        "phone" to listOf("com.google.android.dialer", "com.android.dialer", "com.oneplus.dialer"),
                        "files" to listOf("com.google.android.documentsui", "com.google.android.apps.nbu.files", "com.oneplus.filemanager")
                    )

                    val queryLower = cleanQuery.lowercase()
                    val candidatePackages = knownAliases[queryLower]
                        ?: knownAliases.entries.firstOrNull { queryLower.contains(it.key) || it.key.contains(queryLower) }?.value
                        ?: emptyList()

                    for (candidate in candidatePackages) {
                        if (packageManager.getLaunchIntentForPackage(candidate) != null) {
                            resolvedPackage = candidate
                            resolvedLabel = try {
                                val appInfo = packageManager.getApplicationInfo(candidate, 0)
                                packageManager.getApplicationLabel(appInfo).toString()
                            } catch (_: Exception) { candidate }
                            break
                        }
                    }

                    // Fallback to direct launcher activity intent resolution
                    if (resolvedPackage == null) {
                        try {
                            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_LAUNCHER)
                            }
                            val activities = packageManager.queryIntentActivities(launcherIntent, 0)
                            val matchedActivity = activities.find {
                                val label = try { it.loadLabel(packageManager).toString() } catch (_: Exception) { "" }
                                label.contains(cleanQuery, ignoreCase = true) || it.activityInfo.packageName.contains(cleanQuery, ignoreCase = true)
                            }
                            if (matchedActivity != null) {
                                resolvedPackage = matchedActivity.activityInfo.packageName
                                resolvedLabel = matchedActivity.loadLabel(packageManager).toString()
                            }
                        } catch (e: Exception) {
                            OmniLogger.w(tag, "Direct launcher resolution error: ${e.message}")
                        }
                    }
                }
            }

            if (resolvedPackage == null) {
                val rawQuery = appNameQuery ?: packageQuery ?: ""
                val isInstalledNoLauncher = try {
                    if (!packageQuery.isNullOrBlank()) {
                        packageManager.getPackageInfo(packageQuery, 0)
                        true
                    } else false
                } catch (_: Exception) { false }

                val errorMessage = if (isInstalledNoLauncher) {
                    "Application '$rawQuery' is installed on device but exposes no launchable activity."
                } else {
                    "Application '$rawQuery' is not installed on this device."
                }

                return ToolResult(
                    success = false,
                    tool = definition.name,
                    summaryText = errorMessage,
                    error = if (isInstalledNoLauncher) "NO_LAUNCHER_ACTIVITY" else "APP_NOT_INSTALLED"
                )
            }

            val launchIntent = packageManager.getLaunchIntentForPackage(resolvedPackage)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            if (launchIntent == null) {
                return ToolResult(
                    success = false,
                    tool = definition.name,
                    summaryText = "Unable to create launch intent for '$resolvedPackage'.",
                    error = "NO_LAUNCH_INTENT"
                )
            }

            context.startActivity(launchIntent)

            ToolResult(
                success = true,
                tool = definition.name,
                data = mapOf(
                    "packageName" to resolvedPackage,
                    "appName" to (resolvedLabel ?: resolvedPackage)
                ),
                summaryText = "Successfully launched application '${resolvedLabel ?: resolvedPackage}' ($resolvedPackage)."
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                tool = definition.name,
                summaryText = "Failed to launch application: ${e.message}",
                error = e.localizedMessage ?: "Unknown error"
            )
        }
    }
}

class TapElementTool : OmniTool {
    override val definition = ToolDefinition(
        name = "tap_element",
        description = "Taps an interactive UI element identified by its snapshot element ID (e.g. 'e1', 'e2') from a previous get_ui_snapshot.",
        riskLevel = RiskLevel.MEDIUM,
        parameters = listOf(
            ToolParameter(
                name = "snapshotId",
                type = "string",
                description = "The snapshotId returned by get_ui_snapshot",
                isRequired = true
            ),
            ToolParameter(
                name = "elementId",
                type = "string",
                description = "The element ID to tap (e.g. 'e1', 'e5')",
                isRequired = true
            )
        )
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            val snapshotId = parameters["snapshotId"]?.toString()?.trim()
            val elementId = parameters["elementId"]?.toString()?.trim()

            if (snapshotId.isNullOrBlank() || elementId.isNullOrBlank()) {
                return ToolResult(
                    success = false,
                    tool = definition.name,
                    summaryText = "Missing required parameters: snapshotId and elementId are required.",
                    error = "INVALID_ARGUMENTS"
                )
            }

            val actionResult = AccessibilityConnectionManager.performTap(snapshotId, elementId)

            val data = buildMap<String, Any?> {
                put("action", actionResult.action)
                put("success", actionResult.success)
                put("message", actionResult.message)
                if (actionResult.error != null) put("error", actionResult.error)
                if (actionResult.freshSnapshot != null) {
                    put("freshSnapshot", actionResult.freshSnapshot.toCompactJsonMap())
                }
            }

            ToolResult(
                success = actionResult.success,
                tool = definition.name,
                data = data,
                summaryText = actionResult.message,
                error = actionResult.error
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                tool = definition.name,
                summaryText = "Failed to tap element: ${e.message}",
                error = e.localizedMessage ?: "Unknown error"
            )
        }
    }
}

class EnterTextTool : OmniTool {
    override val definition = ToolDefinition(
        name = "enter_text",
        description = "Enters non-sensitive text into an editable UI field on the screen. Password and credential inputs are strictly rejected.",
        riskLevel = RiskLevel.MEDIUM,
        parameters = listOf(
            ToolParameter(
                name = "snapshotId",
                type = "string",
                description = "The snapshotId returned by get_ui_snapshot",
                isRequired = true
            ),
            ToolParameter(
                name = "elementId",
                type = "string",
                description = "The editable element ID (e.g. 'e3')",
                isRequired = true
            ),
            ToolParameter(
                name = "text",
                type = "string",
                description = "The text to input",
                isRequired = true
            )
        )
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            val snapshotId = parameters["snapshotId"]?.toString()?.trim()
            val elementId = parameters["elementId"]?.toString()?.trim()
            val text = parameters["text"]?.toString()

            if (snapshotId.isNullOrBlank() || elementId.isNullOrBlank() || text == null) {
                return ToolResult(
                    success = false,
                    tool = definition.name,
                    summaryText = "Missing required parameters: snapshotId, elementId, and text are required.",
                    error = "INVALID_ARGUMENTS"
                )
            }

            val actionResult = AccessibilityConnectionManager.performEnterText(snapshotId, elementId, text)

            val data = buildMap<String, Any?> {
                put("action", actionResult.action)
                put("success", actionResult.success)
                put("message", actionResult.message)
                if (actionResult.error != null) put("error", actionResult.error)
                if (actionResult.freshSnapshot != null) {
                    put("freshSnapshot", actionResult.freshSnapshot.toCompactJsonMap())
                }
            }

            ToolResult(
                success = actionResult.success,
                tool = definition.name,
                data = data,
                summaryText = actionResult.message,
                error = actionResult.error
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                tool = definition.name,
                summaryText = "Failed to enter text: ${e.message}",
                error = e.localizedMessage ?: "Unknown error"
            )
        }
    }
}

class ScrollUiTool : OmniTool {
    override val definition = ToolDefinition(
        name = "scroll_ui",
        description = "Scrolls the active screen or a specific scrollable container element forward/down or backward/up.",
        riskLevel = RiskLevel.LOW,
        parameters = listOf(
            ToolParameter(
                name = "snapshotId",
                type = "string",
                description = "The snapshotId (optional)",
                isRequired = false
            ),
            ToolParameter(
                name = "elementId",
                type = "string",
                description = "Target scrollable container element ID (optional)",
                isRequired = false
            ),
            ToolParameter(
                name = "direction",
                type = "string",
                description = "Scroll direction: 'DOWN' (forward) or 'UP' (backward). Default is 'DOWN'.",
                isRequired = false,
                enumValues = listOf("DOWN", "UP", "FORWARD", "BACKWARD")
            )
        )
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            val snapshotId = parameters["snapshotId"]?.toString()?.trim() ?: ""
            val elementId = parameters["elementId"]?.toString()?.trim()
            val direction = parameters["direction"]?.toString()?.trim() ?: "DOWN"

            val actionResult = AccessibilityConnectionManager.performScroll(snapshotId, elementId, direction)

            val data = buildMap<String, Any?> {
                put("action", actionResult.action)
                put("success", actionResult.success)
                put("message", actionResult.message)
                if (actionResult.error != null) put("error", actionResult.error)
                if (actionResult.freshSnapshot != null) {
                    put("freshSnapshot", actionResult.freshSnapshot.toCompactJsonMap())
                }
            }

            ToolResult(
                success = actionResult.success,
                tool = definition.name,
                data = data,
                summaryText = actionResult.message,
                error = actionResult.error
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                tool = definition.name,
                summaryText = "Failed to scroll screen: ${e.message}",
                error = e.localizedMessage ?: "Unknown error"
            )
        }
    }
}

class NavigateBackTool : OmniTool {
    override val definition = ToolDefinition(
        name = "navigate_back",
        description = "Simulates the Android global Back button navigation.",
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            val actionResult = AccessibilityConnectionManager.performNavigateBack()

            val data = buildMap<String, Any?> {
                put("action", actionResult.action)
                put("success", actionResult.success)
                put("message", actionResult.message)
                if (actionResult.error != null) put("error", actionResult.error)
                if (actionResult.freshSnapshot != null) {
                    put("freshSnapshot", actionResult.freshSnapshot.toCompactJsonMap())
                }
            }

            ToolResult(
                success = actionResult.success,
                tool = definition.name,
                data = data,
                summaryText = actionResult.message,
                error = actionResult.error
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                tool = definition.name,
                summaryText = "Failed to navigate back: ${e.message}",
                error = e.localizedMessage ?: "Unknown error"
            )
        }
    }
}
