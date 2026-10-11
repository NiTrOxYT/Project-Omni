package com.omni.assistant.domain.repository

import com.omni.assistant.ai.bridge.SupportedAiApp
import kotlinx.coroutines.flow.Flow

enum class AppThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

interface SettingsRepository {
    fun getThemeMode(): Flow<AppThemeMode>
    suspend fun setThemeMode(mode: AppThemeMode)

    fun getPreferredAiApp(): Flow<SupportedAiApp>
    suspend fun setPreferredAiApp(app: SupportedAiApp)

    fun isMessagingAutoSendEnabled(): Flow<Boolean>
    suspend fun setMessagingAutoSendEnabled(enabled: Boolean)
}
