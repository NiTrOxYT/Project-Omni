package com.omni.assistant.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.assistant.domain.model.BatteryStatus
import com.omni.assistant.domain.model.DeviceStatus
import com.omni.assistant.domain.model.StorageStatus
import com.omni.assistant.domain.model.TaskItem
import com.omni.assistant.domain.usecase.GetBatteryInfoUseCase
import com.omni.assistant.domain.usecase.GetDeviceInfoUseCase
import com.omni.assistant.domain.usecase.GetStorageInfoUseCase
import com.omni.assistant.domain.usecase.GetTasksUseCase
import com.omni.assistant.domain.usecase.ProcessCommandResult
import com.omni.assistant.domain.usecase.ProcessCommandUseCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class HomeUiState(
    val deviceStatus: DeviceStatus,
    val storageStatus: StorageStatus,
    val batteryStatus: BatteryStatus,
    val isExecutingCommand: Boolean = false,
    val commandText: String = ""
)

sealed interface HomeEvent {
    data class ShowSnackbar(val message: String) : HomeEvent
    data class CommandCompleted(val summary: String, val isSuccess: Boolean) : HomeEvent
}

class HomeViewModel(
    private val getDeviceInfoUseCase: GetDeviceInfoUseCase,
    private val getStorageInfoUseCase: GetStorageInfoUseCase,
    private val getBatteryInfoUseCase: GetBatteryInfoUseCase,
    private val getTasksUseCase: GetTasksUseCase,
    private val processCommandUseCase: ProcessCommandUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        HomeUiState(
            deviceStatus = getDeviceInfoUseCase(),
            storageStatus = getStorageInfoUseCase(),
            batteryStatus = getBatteryInfoUseCase()
        )
    )
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    val recentTasks: StateFlow<List<TaskItem>> = getTasksUseCase.getRecent(limit = 4)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _events = MutableSharedFlow<HomeEvent>()
    val events: SharedFlow<HomeEvent> = _events.asSharedFlow()

    fun refreshStats() {
        _uiState.value = _uiState.value.copy(
            deviceStatus = getDeviceInfoUseCase(),
            storageStatus = getStorageInfoUseCase(),
            batteryStatus = getBatteryInfoUseCase()
        )
    }

    fun onCommandTextChanged(newText: String) {
        _uiState.value = _uiState.value.copy(commandText = newText)
    }

    fun executeCommand(command: String? = null) {
        val promptToRun = (command ?: _uiState.value.commandText).trim()
        if (promptToRun.isBlank()) return

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExecutingCommand = true, commandText = "")
            when (val result = processCommandUseCase(promptToRun)) {
                is ProcessCommandResult.Executed -> {
                    refreshStats()
                    _events.emit(HomeEvent.CommandCompleted(result.assistantMessage.content, result.isSuccess))
                }
                is ProcessCommandResult.Empty -> {
                    // Do nothing
                }
            }
            _uiState.value = _uiState.value.copy(isExecutingCommand = false)
        }
    }
}
