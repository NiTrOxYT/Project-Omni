package com.omni.assistant.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.assistant.domain.model.ChatMessage
import com.omni.assistant.domain.repository.ChatRepository
import com.omni.assistant.domain.usecase.GetChatMessagesUseCase
import com.omni.assistant.domain.usecase.ProcessCommandResult
import com.omni.assistant.domain.usecase.ProcessCommandUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val inputText: String = "",
    val isLoading: Boolean = false,
    val agentStatus: String? = null,
    val errorMessage: String? = null
)

class ChatViewModel(
    private val getChatMessagesUseCase: GetChatMessagesUseCase,
    private val processCommandUseCase: ProcessCommandUseCase,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null

    val messages: StateFlow<List<ChatMessage>> = getChatMessagesUseCase()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun onInputTextChanged(text: String) {
        _uiState.value = _uiState.value.copy(inputText = text)
    }

    fun sendMessage(customPrompt: String? = null) {
        val textToSend = (customPrompt ?: _uiState.value.inputText).trim()
        if (textToSend.isBlank()) return

        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                inputText = "",
                isLoading = true,
                agentStatus = "Analyzing instruction...",
                errorMessage = null
            )

            try {
                when (processCommandUseCase(textToSend) { statusMsg ->
                    _uiState.value = _uiState.value.copy(agentStatus = statusMsg)
                }) {
                    is ProcessCommandResult.Executed -> {
                        // Handled in repository flow
                    }
                    is ProcessCommandResult.Empty -> {
                        // Nothing to do
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    errorMessage = "Error processing command: ${e.message}"
                )
            } finally {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    agentStatus = null
                )
            }
        }
    }

    fun cancelActiveRequest() {
        activeJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            agentStatus = null
        )
    }

    fun clearHistory() {
        viewModelScope.launch {
            chatRepository.clearHistory()
        }
    }
}
