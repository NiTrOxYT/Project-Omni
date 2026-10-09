package com.omni.assistant.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.omni.assistant.domain.model.ChatMessage
import com.omni.assistant.domain.usecase.GetChatMessagesUseCase
import com.omni.assistant.domain.usecase.ProcessCommandResult
import com.omni.assistant.domain.usecase.ProcessCommandUseCase
import com.omni.assistant.domain.repository.ChatRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val inputText: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null
)

class ChatViewModel(
    private val getChatMessagesUseCase: GetChatMessagesUseCase,
    private val processCommandUseCase: ProcessCommandUseCase,
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

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

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                inputText = "",
                isLoading = true,
                errorMessage = null
            )

            try {
                when (processCommandUseCase(textToSend)) {
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
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            chatRepository.clearHistory()
        }
    }
}
