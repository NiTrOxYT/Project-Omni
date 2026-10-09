package com.omni.assistant.presentation.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.assistant.domain.model.TaskItem
import com.omni.assistant.domain.model.TaskStatus
import com.omni.assistant.domain.repository.TaskRepository
import com.omni.assistant.domain.usecase.GetTasksUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class TaskFilter {
    ALL,
    SUCCEEDED,
    RUNNING,
    FAILED
}

class TasksViewModel(
    private val getTasksUseCase: GetTasksUseCase,
    private val taskRepository: TaskRepository
) : ViewModel() {

    private val _selectedFilter = MutableStateFlow(TaskFilter.ALL)
    val selectedFilter: StateFlow<TaskFilter> = _selectedFilter.asStateFlow()

    val filteredTasks: StateFlow<List<TaskItem>> = combine(
        getTasksUseCase(),
        _selectedFilter
    ) { tasks, filter ->
        when (filter) {
            TaskFilter.ALL -> tasks
            TaskFilter.SUCCEEDED -> tasks.filter { it.status == TaskStatus.SUCCEEDED }
            TaskFilter.RUNNING -> tasks.filter { it.status == TaskStatus.RUNNING || it.status == TaskStatus.PENDING }
            TaskFilter.FAILED -> tasks.filter { it.status == TaskStatus.FAILED || it.status == TaskStatus.CANCELLED }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    fun setFilter(filter: TaskFilter) {
        _selectedFilter.value = filter
    }

    fun clearTasks() {
        viewModelScope.launch {
            taskRepository.clearTasks()
        }
    }
}
