package com.omni.assistant.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.assistant.domain.repository.AppThemeMode
import com.omni.assistant.execution.ToolDefinition
import com.omni.assistant.presentation.theme.AccentCyan
import com.omni.assistant.presentation.theme.AccentIndigo
import com.omni.assistant.presentation.theme.BorderDark
import com.omni.assistant.presentation.theme.StatusSuccess
import com.omni.assistant.presentation.theme.StatusWarning
import com.omni.assistant.presentation.theme.SurfaceDark
import com.omni.assistant.presentation.theme.SurfaceVariantDark
import com.omni.assistant.presentation.theme.TextPrimaryDark
import com.omni.assistant.presentation.theme.TextSecondaryDark
import com.omni.assistant.presentation.theme.TextTertiaryDark

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel
) {
    val themeMode by viewModel.themeMode.collectAsState()
    val aiConfig by viewModel.aiConfig.collectAsState()
    val deviceStatus = viewModel.deviceStatus
    val tools = viewModel.registeredTools
    val context = androidx.compose.ui.platform.LocalContext.current

    var apiKeyInput by remember(aiConfig.apiKey) { mutableStateOf(aiConfig.apiKey) }
    var isApiKeyVisible by remember { mutableStateOf(false) }
    var isKeySavedBannerVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "System Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimaryDark
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            // App Banner
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = SurfaceDark,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(AccentCyan.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Shield,
                            contentDescription = null,
                            tint = AccentCyan,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = "Omni Assistant",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Text(
                            text = "API-Free Native Automation",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AccentCyan
                        )
                        Text(
                            text = "Zero API Keys • Local Planning • AI App Bridge",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondaryDark
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // AI App Bridge Section (Zero API Key)
            SettingsSectionHeader(title = "On-Device AI App Bridge (Zero API Keys)", icon = Icons.Default.Memory)
            Spacer(modifier = Modifier.height(8.dp))

            val preferredAiApp by viewModel.preferredAiApp.collectAsState()
            val installedAiApps = remember {
                viewModel.aiAppBridge?.discoverInstalledAiApps() ?: emptyList()
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Active AI Reasoning Engine",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimaryDark
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(StatusSuccess.copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "ZERO API KEYS",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = StatusSuccess
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "When complex reasoning is needed, Omni interacts directly with AI applications already installed on your phone via Accessibility. No API tokens or subscriptions needed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryDark,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "Preferred AI Application",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondaryDark,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    listOf(
                        com.omni.assistant.ai.bridge.SupportedAiApp.AUTO to "Auto-Detect Installed AI App",
                        com.omni.assistant.ai.bridge.SupportedAiApp.CHATGPT to "ChatGPT (com.openai.chatgpt)",
                        com.omni.assistant.ai.bridge.SupportedAiApp.GEMINI to "Google Gemini (com.google.android.apps.bard)"
                    ).forEach { (app, title) ->
                        val isInstalled = installedAiApps.find { it.app == app }?.isInstalled
                            ?: (app == com.omni.assistant.ai.bridge.SupportedAiApp.AUTO && installedAiApps.any { it.isInstalled })
                        val isSelected = preferredAiApp == app

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setPreferredAiApp(app) }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { viewModel.setPreferredAiApp(app) },
                                    colors = RadioButtonDefaults.colors(selectedColor = AccentCyan)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (isSelected) AccentCyan else TextPrimaryDark
                                )
                            }

                            if (app != com.omni.assistant.ai.bridge.SupportedAiApp.AUTO) {
                                val statusBadge = if (isInstalled) "INSTALLED" to StatusSuccess else "NOT INSTALLED" to TextTertiaryDark
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(statusBadge.second.copy(alpha = 0.15f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = statusBadge.first,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = statusBadge.second,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    val selectedPkg = viewModel.aiAppBridge?.getPreferredOrAvailablePackage(preferredAiApp)
                    if (selectedPkg != null) {
                        Button(
                            onClick = {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage(selectedPkg)
                                if (launchIntent != null) {
                                    launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(launchIntent)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AccentIndigo,
                                contentColor = TextPrimaryDark
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = "Test Launch AI App",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Developer / Cloud AI Optional Section (Legacy)
            var isCloudSectionExpanded by remember { mutableStateOf(false) }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isCloudSectionExpanded = !isCloudSectionExpanded },
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Developer / Cloud AI Provider (Optional)",
                                style = MaterialTheme.typography.titleSmall,
                                color = TextSecondaryDark,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = if (aiConfig.hasValidApiKey) "Custom Gemini API Key active" else "Disabled (API-free mode active)",
                                style = MaterialTheme.typography.labelSmall,
                                color = TextTertiaryDark
                            )
                        }
                        Text(
                            text = if (isCloudSectionExpanded) "▲ Hide" else "▼ Expand",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentCyan
                        )
                    }

                    if (isCloudSectionExpanded) {
                        Spacer(modifier = Modifier.height(14.dp))

                        // Cloud AI Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Enable Legacy Cloud Gemini",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextPrimaryDark
                                )
                                Text(
                                    text = "Optional network API fallback.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = TextSecondaryDark
                                )
                            }
                            Switch(
                                checked = aiConfig.useCloudAi,
                                onCheckedChange = { viewModel.updateUseCloudAi(it) },
                                colors = SwitchDefaults.colors(checkedThumbColor = AccentCyan)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = {
                                apiKeyInput = it
                                isKeySavedBannerVisible = false
                            },
                            placeholder = {
                                Text(text = "Optional API Key (AIzaSy...)", color = TextTertiaryDark)
                            },
                            visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = SurfaceVariantDark,
                                unfocusedContainerColor = SurfaceVariantDark,
                                focusedBorderColor = AccentCyan,
                                unfocusedBorderColor = BorderDark,
                                focusedTextColor = TextPrimaryDark,
                                unfocusedTextColor = TextPrimaryDark
                            ),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Button(
                            onClick = {
                                viewModel.updateApiKey(apiKeyInput)
                                isKeySavedBannerVisible = true
                            },
                            modifier = Modifier.align(Alignment.End),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = SurfaceDark),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(text = "Save Key", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Accessibility Automation Engine Section
            SettingsSectionHeader(title = "Accessibility Automation Engine", icon = Icons.Default.Build)
            Spacer(modifier = Modifier.height(8.dp))

            val a11yStatus by com.omni.assistant.accessibility.AccessibilityConnectionManager.serviceStatus.collectAsState()

            androidx.compose.runtime.LaunchedEffect(Unit) {
                com.omni.assistant.accessibility.AccessibilityConnectionManager.checkAndRefreshStatus(context)
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Service Connection",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextPrimaryDark
                            )
                            Text(
                                text = when (a11yStatus) {
                                    com.omni.assistant.accessibility.AccessibilityStatus.CONNECTED -> "Active & Ready for UI Automation"
                                    com.omni.assistant.accessibility.AccessibilityStatus.DISCONNECTED -> "Enabled in settings but service disconnected"
                                    com.omni.assistant.accessibility.AccessibilityStatus.NOT_ENABLED -> "Disabled in Android Settings"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondaryDark
                            )
                        }

                        val (statusText, statusBg, statusFg) = when (a11yStatus) {
                            com.omni.assistant.accessibility.AccessibilityStatus.CONNECTED -> Triple("CONNECTED", StatusSuccess.copy(alpha = 0.15f), StatusSuccess)
                            com.omni.assistant.accessibility.AccessibilityStatus.DISCONNECTED -> Triple("DISCONNECTED", StatusWarning.copy(alpha = 0.15f), StatusWarning)
                            com.omni.assistant.accessibility.AccessibilityStatus.NOT_ENABLED -> Triple("DISABLED", BorderDark, TextSecondaryDark)
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(statusBg)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = statusFg
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = "To allow Omni to observe screen elements and perform authorized actions (opening apps, tapping buttons, scrolling), enable Omni in Android Accessibility Settings.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondaryDark,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            val intent = com.omni.assistant.accessibility.AccessibilityConnectionManager.createAccessibilitySettingsIntent()
                            context.startActivity(intent)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (a11yStatus == com.omni.assistant.accessibility.AccessibilityStatus.CONNECTED) AccentIndigo else AccentCyan,
                            contentColor = if (a11yStatus == com.omni.assistant.accessibility.AccessibilityStatus.CONNECTED) TextPrimaryDark else SurfaceDark
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = if (a11yStatus == com.omni.assistant.accessibility.AccessibilityStatus.CONNECTED) "Manage Accessibility Settings" else "Open Accessibility Settings",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "Privacy Note: Omni uses conservative filtering to protect password fields and sensitive information. UI interactions must pass through local ActionPolicy safeguards.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiaryDark,
                        lineHeight = 15.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Appearance / Theme Section
            SettingsSectionHeader(title = "Appearance", icon = Icons.Default.DarkMode)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ThemeOptionRow(
                        title = "Dark Theme (Default)",
                        description = "Obsidian dark theme optimized for OLED displays",
                        selected = themeMode == AppThemeMode.DARK,
                        onSelect = { viewModel.setThemeMode(AppThemeMode.DARK) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ThemeOptionRow(
                        title = "Light Theme",
                        description = "Clean high-contrast daytime interface",
                        selected = themeMode == AppThemeMode.LIGHT,
                        onSelect = { viewModel.setThemeMode(AppThemeMode.LIGHT) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    ThemeOptionRow(
                        title = "System Default",
                        description = "Matches your Android system appearance settings",
                        selected = themeMode == AppThemeMode.SYSTEM,
                        onSelect = { viewModel.setThemeMode(AppThemeMode.SYSTEM) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Device Control Capabilities / Registered Tools
            SettingsSectionHeader(title = "Registered Capabilities", icon = Icons.Default.Build)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    tools.forEach { tool ->
                        ToolItemRow(tool = tool)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Privacy & Security
            SettingsSectionHeader(title = "Privacy & Security Policy", icon = Icons.Default.Lock)
            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrivacyBulletPoint(
                        title = "Secure Local Credential Storage",
                        desc = "API keys are stored exclusively in private on-device SharedPreferences and never embedded, committed, or transmitted."
                    )
                    PrivacyBulletPoint(
                        title = "Scoped Device Tool Invocation",
                        desc = "The AI model cannot execute arbitrary shell commands or code; it can only invoke declared, type-safe local tools through ActionPolicy."
                    )
                    PrivacyBulletPoint(
                        title = "Minimal Context Transmission",
                        desc = "Only recent chat messages and registered tool schemas are transmitted to the AI provider. No contacts, notifications, or background files are shared."
                    )
                    PrivacyBulletPoint(
                        title = "Offline Local Fallback",
                        desc = "Deterministic telemetry commands (battery, storage, device info) continue working locally even without an internet connection or API key."
                    )
                }
            }

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

@Composable
fun SettingsSectionHeader(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = AccentCyan,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = TextPrimaryDark
        )
    }
}

@Composable
fun ThemeOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = AccentCyan,
                unselectedColor = TextTertiaryDark
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = TextPrimaryDark
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondaryDark
            )
        }
    }
}

@Composable
fun ToolItemRow(tool: ToolDefinition) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceVariantDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = AccentCyan
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(AccentIndigo.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "RISK: ${tool.riskLevel.name}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentIndigo
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondaryDark
            )
        }
    }
}

@Composable
fun PrivacyBulletPoint(title: String, desc: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = Icons.Default.Check,
            contentDescription = null,
            tint = StatusSuccess,
            modifier = Modifier
                .size(16.dp)
                .padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimaryDark
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondaryDark
            )
        }
    }
}
