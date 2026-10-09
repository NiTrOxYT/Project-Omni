package com.omni.assistant.domain.repository

import com.omni.assistant.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow

interface ChatRepository {
    fun getMessages(): Flow<List<ChatMessage>>
    suspend fun insertMessage(message: ChatMessage)
    suspend fun clearHistory()
}
