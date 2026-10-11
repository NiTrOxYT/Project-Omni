package com.omni.assistant.presentation.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.omni.assistant.domain.model.TaskItem
import com.omni.assistant.domain.model.TaskStatus
import com.omni.assistant.presentation.theme.AccentCyan
import com.omni.assistant.presentation.theme.AccentIndigo
import com.omni.assistant.presentation.theme.BorderDark
import com.omni.assistant.presentation.theme.BorderHighlightDark
import com.omni.assistant.presentation.theme.StatusError
import com.omni.assistant.presentation.theme.StatusErrorContainer
import com.omni.assistant.presentation.theme.StatusSuccess
import com.omni.assistant.presentation.theme.StatusSuccessContainer
import com.omni.assistant.presentation.theme.StatusWarning
import com.omni.assistant.presentation.theme.StatusWarningContainer
import com.omni.assistant.presentation.theme.SurfaceDark
import com.omni.assistant.presentation.theme.SurfaceVariantDark
import com.omni.assistant.presentation.theme.TextPrimaryDark
import com.omni.assistant.presentation.theme.TextSecondaryDark
import com.omni.assistant.presentation.theme.TextTertiaryDark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    viewModel: TasksViewModel
) {
    val tasks by viewModel.filteredTasks.collectAsState()
    val selectedFilter by viewModel.selectedFilter.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Task Operations",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimaryDark
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentCyan.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${tasks.size}",
                                style = MaterialTheme.typography.labelSmall,
                                color = AccentCyan,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                actions = {
                    if (tasks.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearTasks() }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear History",
                                tint = TextSecondaryDark
                            )
                        }
                    }
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
        ) {
            // Filter Row
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(TaskFilter.entries.toTypedArray()) { filter ->
                    val isSelected = filter == selectedFilter
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.setFilter(filter) },
                        label = {
                            Text(
                                text = filter.name.lowercase().replaceFirstChar { it.uppercase() },
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = SurfaceDark,
                            labelColor = TextSecondaryDark,
                            selectedContainerColor = AccentCyan.copy(alpha = 0.2f),
                            selectedLabelColor = AccentCyan
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            borderColor = if (isSelected) AccentCyan else BorderDark,
                            enabled = true,
                            selected = isSelected
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            if (tasks.isEmpty()) {
                EmptyTasksView()
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskDetailCard(task = task)
                    }
                }
            }
        }
    }
}

@Composable
fun TaskDetailCard(task: TaskItem) {
    val statusColors = when (task.status) {
        TaskStatus.SUCCEEDED -> Pair(StatusSuccess, StatusSuccessContainer)
        TaskStatus.RUNNING -> Pair(AccentCyan, AccentCyan.copy(alpha = 0.2f))
        TaskStatus.AWAITING_CONFIRMATION -> Pair(StatusWarning, StatusWarningContainer)
        TaskStatus.PENDING -> Pair(StatusWarning, StatusWarningContainer)
        TaskStatus.FAILED -> Pair(StatusError, StatusErrorContainer)
        TaskStatus.CANCELLED -> Pair(TextTertiaryDark, SurfaceVariantDark)
    }

    val statusIcon = when (task.status) {
        TaskStatus.SUCCEEDED -> Icons.Default.CheckCircle
        TaskStatus.RUNNING -> Icons.Default.Sync
        TaskStatus.AWAITING_CONFIRMATION -> Icons.Default.HourglassTop
        TaskStatus.PENDING -> Icons.Default.HourglassTop
        TaskStatus.FAILED, TaskStatus.CANCELLED -> Icons.Default.ErrorOutline
    }

    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val formattedCreatedAt = remember(task.createdAt) { timeFormat.format(Date(task.createdAt)) }
    val formattedCompletedAt = remember(task.completedAt) {
        task.completedAt?.let { timeFormat.format(Date(it)) }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceDark),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header Row: Status Badge & Short ID
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = statusColors.second,
                    border = androidx.compose.foundation.BorderStroke(1.dp, statusColors.first.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = statusColors.first,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = task.status.name,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColors.first
                        )
                    }
                }

                Text(
                    text = "ID: ${task.id.take(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = TextTertiaryDark
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Instruction / Request Text
            Text(
                text = task.request,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimaryDark
            )

            // Result summary
            if (!task.resultSummary.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = SurfaceVariantDark,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderDark)
                ) {
                    Text(
                        text = task.resultSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondaryDark,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // Error details if any
            if (!task.errorDetails.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    color = StatusErrorContainer.copy(alpha = 0.4f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, StatusError.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "Error: ${task.errorDetails}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = StatusError,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Timestamps & Duration footer
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.AccessTime,
                        contentDescription = null,
                        tint = TextTertiaryDark,
                        modifier = Modifier.size(12.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = formattedCreatedAt,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextTertiaryDark
                    )
                }

                if (task.durationMillis != null) {
                    Text(
                        text = "${task.durationMillis}ms",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = AccentIndigo
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyTasksView() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ListAlt,
            contentDescription = null,
            tint = TextTertiaryDark,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No tasks recorded",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextPrimaryDark
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Commands issued in Chat or Dashboard will be logged here with complete execution telemetry.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondaryDark,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
