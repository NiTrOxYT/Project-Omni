package com.omni.assistant.domain.repository

import kotlinx.coroutines.flow.Flow

enum class AppThemeMode {
    DARK,
    LIGHT,
    SYSTEM
}

interface SettingsRepository {
    fun getThemeMode(): Flow<AppThemeMode>
    suspend fun setThemeMode(mode: AppThemeMode)
}
