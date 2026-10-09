package com.omni.assistant.data.repository

import com.omni.assistant.data.local.dao.ChatMessageDao
import com.omni.assistant.data.local.entity.ChatMessageEntity
import com.omni.assistant.domain.model.ChatMessage
import com.omni.assistant.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ChatRepositoryImpl(
    private val chatMessageDao: ChatMessageDao
) : ChatRepository {

    override fun getMessages(): Flow<List<ChatMessage>> {
        return chatMessageDao.getAllMessages().map { list ->
            list.map { it.toDomain() }
        }
    }

    override suspend fun insertMessage(message: ChatMessage) {
        chatMessageDao.insertMessage(ChatMessageEntity.fromDomain(message))
    }

    override suspend fun clearHistory() {
        chatMessageDao.clearAll()
    }
}
