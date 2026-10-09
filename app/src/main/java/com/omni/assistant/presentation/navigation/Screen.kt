package com.omni.assistant.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector
) {
    data object Home : Screen("home", "Dashboard", Icons.Outlined.Dashboard)
    data object Chat : Screen("chat", "Chat", Icons.Outlined.ChatBubbleOutline)
    data object Tasks : Screen("tasks", "Tasks", Icons.Outlined.CheckCircleOutline)
    data object Settings : Screen("settings", "Settings", Icons.Outlined.Settings)

    companion object {
        val bottomNavItems = listOf(Home, Chat, Tasks, Settings)
    }
}
