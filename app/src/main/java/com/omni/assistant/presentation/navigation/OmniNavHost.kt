package com.omni.assistant.presentation.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.omni.assistant.OmniAppContainer
import com.omni.assistant.presentation.chat.ChatScreen
import com.omni.assistant.presentation.chat.ChatViewModel
import com.omni.assistant.presentation.home.HomeScreen
import com.omni.assistant.presentation.home.HomeViewModel
import com.omni.assistant.presentation.settings.SettingsScreen
import com.omni.assistant.presentation.settings.SettingsViewModel
import com.omni.assistant.presentation.tasks.TasksScreen
import com.omni.assistant.presentation.tasks.TasksViewModel
import com.omni.assistant.presentation.theme.AccentCyan
import com.omni.assistant.presentation.theme.BorderDark
import com.omni.assistant.presentation.theme.SurfaceDark
import com.omni.assistant.presentation.theme.TextSecondaryDark

@Composable
fun OmniApp(
    container: OmniAppContainer,
    navController: NavHostController = rememberNavController()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = SurfaceDark,
                contentColor = TextSecondaryDark
            ) {
                Screen.bottomNavItems.forEach { screen ->
                    val isSelected = currentRoute == screen.route
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            if (currentRoute != screen.route) {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.title
                            )
                        },
                        label = {
                            Text(
                                text = screen.title,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AccentCyan,
                            selectedTextColor = AccentCyan,
                            indicatorColor = AccentCyan.copy(alpha = 0.15f),
                            unselectedIconColor = TextSecondaryDark,
                            unselectedTextColor = TextSecondaryDark
                        )
                    )
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                val homeViewModel = rememberHomeViewModel(container)
                HomeScreen(
                    viewModel = homeViewModel,
                    onNavigateToTasks = {
                        navController.navigate(Screen.Tasks.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToChat = {
                        navController.navigate(Screen.Chat.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }

            composable(Screen.Chat.route) {
                val chatViewModel = rememberChatViewModel(container)
                ChatScreen(viewModel = chatViewModel)
            }

            composable(Screen.Tasks.route) {
                val tasksViewModel = rememberTasksViewModel(container)
                TasksScreen(viewModel = tasksViewModel)
            }

            composable(Screen.Settings.route) {
                val settingsViewModel = rememberSettingsViewModel(container)
                SettingsScreen(viewModel = settingsViewModel)
            }
        }
    }
}

@Composable
fun rememberHomeViewModel(container: OmniAppContainer): HomeViewModel {
    return androidx.lifecycle.viewmodel.compose.viewModel {
        HomeViewModel(
            getDeviceInfoUseCase = container.getDeviceInfoUseCase,
            getStorageInfoUseCase = container.getStorageInfoUseCase,
            getBatteryInfoUseCase = container.getBatteryInfoUseCase,
            getTasksUseCase = container.getTasksUseCase,
            processCommandUseCase = container.processCommandUseCase
        )
    }
}

@Composable
fun rememberChatViewModel(container: OmniAppContainer): ChatViewModel {
    return androidx.lifecycle.viewmodel.compose.viewModel {
        ChatViewModel(
            getChatMessagesUseCase = container.getChatMessagesUseCase,
            processCommandUseCase = container.processCommandUseCase,
            chatRepository = container.chatRepository
        )
    }
}

@Composable
fun rememberTasksViewModel(container: OmniAppContainer): TasksViewModel {
    return androidx.lifecycle.viewmodel.compose.viewModel {
        TasksViewModel(
            getTasksUseCase = container.getTasksUseCase,
            taskRepository = container.taskRepository
        )
    }
}

@Composable
fun rememberSettingsViewModel(container: OmniAppContainer): SettingsViewModel {
    return androidx.lifecycle.viewmodel.compose.viewModel {
        SettingsViewModel(
            settingsRepository = container.settingsRepository,
            aiConfigRepository = container.aiConfigRepository,
            getDeviceInfoUseCase = container.getDeviceInfoUseCase,
            toolRegistry = container.toolRegistry,
            aiAppBridge = container.aiAppBridge
        )
    }
}
