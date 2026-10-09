package com.omni.assistant.presentation.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.assistant.domain.model.RootStatus
import com.omni.assistant.domain.model.TaskItem
import com.omni.assistant.domain.model.TaskStatus
import com.omni.assistant.presentation.theme.AccentCyan
import com.omni.assistant.presentation.theme.AccentIndigo
import com.omni.assistant.presentation.theme.BorderDark
import com.omni.assistant.presentation.theme.StatusError
import com.omni.assistant.presentation.theme.StatusSuccess
import com.omni.assistant.presentation.theme.StatusWarning
import com.omni.assistant.presentation.theme.SurfaceDark
import com.omni.assistant.presentation.theme.SurfaceVariantDark
import com.omni.assistant.presentation.theme.TextPrimaryDark
import com.omni.assistant.presentation.theme.TextSecondaryDark
import com.omni.assistant.presentation.theme.TextTertiaryDark
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToTasks: () -> Unit,
    onNavigateToChat: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val recentTasks by viewModel.recentTasks.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collectLatest { event ->
            when (event) {
                is HomeEvent.ShowSnackbar -> snackbarHostState.showSnackbar(event.message)
                is HomeEvent.CommandCompleted -> snackbarHostState.showSnackbar(event.summary)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Header Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "OMNI",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = AccentCyan,
                            letterSpacing = 2.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentCyan.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "PHASE 1",
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Text(
                        text = "Personal AI Agent Foundation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondaryDark
                    )
                }

                IconButton(
                    onClick = { viewModel.refreshStats() },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(SurfaceVariantDark)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh device telemetry",
                        tint = AccentCyan
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Assistant Status Banner
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
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(StatusSuccess)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Core Engine Active",
                            style = MaterialTheme.typography.titleMedium,
                            color = TextPrimaryDark,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Local deterministic execution mode",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondaryDark
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Telemetry Grid
            Text(
                text = "Device Telemetry",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimaryDark,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Row 1: Model & OS
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TelemetryCard(
                    modifier = Modifier.weight(1f),
                    title = "Hardware Model",
                    primaryValue = uiState.deviceStatus.displayTitle,
                    secondaryValue = uiState.deviceStatus.osTitle,
                    icon = Icons.Default.PhoneAndroid,
                    accentColor = AccentCyan
                )

                val rootColor = when (uiState.deviceStatus.rootStatus) {
                    RootStatus.AVAILABLE -> StatusSuccess
                    RootStatus.NOT_AVAILABLE -> TextSecondaryDark
                    RootStatus.UNKNOWN -> StatusWarning
                }

                TelemetryCard(
                    modifier = Modifier.weight(1f),
                    title = "Root Status",
                    primaryValue = uiState.deviceStatus.rootStatus.name,
                    secondaryValue = "Passive inspection",
                    icon = Icons.Default.Security,
                    accentColor = rootColor
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Row 2: Battery & Storage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                val battery = uiState.batteryStatus
                TelemetryCard(
                    modifier = Modifier.weight(1f),
                    title = "Battery",
                    primaryValue = battery.percentageText,
                    secondaryValue = if (battery.isCharging) "Charging (${battery.powerSource.name})" else "Discharging",
                    icon = if (battery.isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryStd,
                    accentColor = if ((battery.percentage ?: 100) > 20) StatusSuccess else StatusError
                )

                val storage = uiState.storageStatus
                val animatedProgress by animateFloatAsState(
                    targetValue = storage.usedPercentage / 100f,
                    label = "storageProgress"
                )

                Card(
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDark),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Internal Storage",
                                style = MaterialTheme.typography.labelMedium,
                                color = TextSecondaryDark
                            )
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = AccentIndigo,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${storage.usedFormatted} used",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Text(
                            text = "${storage.availableFormatted} free of ${storage.totalFormatted}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondaryDark
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = AccentIndigo,
                            trackColor = SurfaceVariantDark,
                            strokeCap = StrokeCap.Round
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Command Center / Quick Execution
            Text(
                text = "Command Interface",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimaryDark,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Quick Prompt Chips
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                QuickActionChip(label = "Show device information") {
                    viewModel.executeCommand("Show device information.")
                }
                QuickActionChip(label = "Check my storage") {
                    viewModel.executeCommand("Check my storage.")
                }
                QuickActionChip(label = "Check my battery") {
                    viewModel.executeCommand("Check my battery.")
                }
                QuickActionChip(label = "What version of Omni am I using?") {
                    viewModel.executeCommand("What version of Omni am I using?")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Command Input Box
            OutlinedTextField(
                value = uiState.commandText,
                onValueChange = { viewModel.onCommandTextChanged(it) },
                placeholder = {
                    Text(
                        text = "Enter a command (e.g. Check my battery)...",
                        color = TextTertiaryDark,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SurfaceDark,
                    unfocusedContainerColor = SurfaceDark,
                    focusedBorderColor = AccentCyan,
                    unfocusedBorderColor = BorderDark,
                    focusedTextColor = TextPrimaryDark,
                    unfocusedTextColor = TextPrimaryDark
                ),
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                viewModel.executeCommand(uiState.commandText)
                            },
                            enabled = uiState.commandText.isNotBlank() && !uiState.isExecutingCommand
                        ) {
                            if (uiState.isExecutingCommand) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = AccentCyan
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.Send,
                                    contentDescription = "Execute Command",
                                    tint = if (uiState.commandText.isNotBlank()) AccentCyan else TextTertiaryDark
                                )
                            }
                        }

                        // Microphone placeholder
                        IconButton(
                            onClick = {
                                // Informative interaction
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Mic,
                                contentDescription = "Voice input (Coming soon)",
                                tint = TextSecondaryDark
                            )
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = { viewModel.executeCommand(uiState.commandText) }
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Recent Tasks Section
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Recent Operations",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextPrimaryDark,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "View All",
                    style = MaterialTheme.typography.labelLarge,
                    color = AccentCyan,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onNavigateToTasks() }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (recentTasks.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = SurfaceDark,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Terminal,
                            contentDescription = null,
                            tint = TextTertiaryDark,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "No operations executed yet",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondaryDark
                        )
                        Text(
                            text = "Try running a quick telemetry command above",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextTertiaryDark
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recentTasks.forEach { task ->
                        RecentTaskItem(task = task)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun TelemetryCard(
    modifier: Modifier = Modifier,
    title: String,
    primaryValue: String,
    secondaryValue: String,
    icon: ImageVector,
    accentColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondaryDark
                )
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = primaryValue,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = TextPrimaryDark,
                maxLines = 1
            )
            Text(
                text = secondaryValue,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondaryDark,
                maxLines = 1
            )
        }
    }
}

@Composable
fun QuickActionChip(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        color = SurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = AccentCyan,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun RecentTaskItem(task: TaskItem) {
    val statusColor = when (task.status) {
        TaskStatus.SUCCEEDED -> StatusSuccess
        TaskStatus.RUNNING -> AccentCyan
        TaskStatus.PENDING -> StatusWarning
        TaskStatus.FAILED -> StatusError
        TaskStatus.CANCELLED -> TextTertiaryDark
    }

    val timeFormatted = remember(task.createdAt) {
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(task.createdAt))
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = SurfaceDark,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.request,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimaryDark,
                    maxLines = 1
                )
                Text(
                    text = task.resultSummary ?: "Executing...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondaryDark,
                    maxLines = 1
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = timeFormatted,
                style = MaterialTheme.typography.labelSmall,
                color = TextTertiaryDark
            )
        }
    }
}
