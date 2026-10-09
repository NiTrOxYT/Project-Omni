package com.omni.assistant.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.assistant.ai.config.AiConfig
import com.omni.assistant.ai.config.AiConfigRepository
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
    private val aiConfigRepository: AiConfigRepository,
    private val getDeviceInfoUseCase: GetDeviceInfoUseCase,
    private val toolRegistry: ToolRegistry
) : ViewModel() {

    val themeMode: StateFlow<AppThemeMode> = settingsRepository.getThemeMode()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = AppThemeMode.DARK
        )

    val aiConfig: StateFlow<AiConfig> = aiConfigRepository.getConfig()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = aiConfigRepository.getCurrentConfig()
        )

    val deviceStatus: DeviceStatus = getDeviceInfoUseCase()

    val registeredTools: List<ToolDefinition> = toolRegistry.getAllDefinitions()

    fun setThemeMode(mode: AppThemeMode) {
        viewModelScope.launch {
            settingsRepository.setThemeMode(mode)
        }
    }

    fun updateApiKey(key: String) {
        viewModelScope.launch {
            aiConfigRepository.updateApiKey(key)
        }
    }

    fun updateModel(model: String) {
        viewModelScope.launch {
            aiConfigRepository.updateModel(model)
        }
    }

    fun updateUseCloudAi(useCloud: Boolean) {
        viewModelScope.launch {
            aiConfigRepository.updateUseCloudAi(useCloud)
        }
    }
}
