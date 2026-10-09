package com.omni.assistant.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.assistant.domain.model.DeviceStatus
import com.omni.assistant.domain.repository.AppThemeMode
import com.omni.assistant.domain.repository.SettingsRepository
import com.omni.assistant.domain.usecase.GetDeviceInfoUseCase
import com.omni.assistant.execution.ToolDefinition
import com.omni.assistant.execution.ToolRegistry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val getDeviceInfoUseCase: GetDeviceInfoUseCase,
    private val toolRegistry: ToolRegistry
) : ViewModel() {

    val themeMode: StateFlow<AppThemeMode> = settingsRepository.getThemeMode()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppThemeMode.DARK
        )

    val deviceStatus: DeviceStatus = getDeviceInfoUseCase()

    val registeredTools: List<ToolDefinition> = toolRegistry.getAllDefinitions()

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }
}
