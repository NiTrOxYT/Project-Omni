package com.omni.assistant.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.omni.assistant.domain.repository.AppThemeMode
import com.omni.assistant.domain.repository.SettingsRepository
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class SettingsRepositoryImpl(
    private val context: Context
) : SettingsRepository {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREFS_NAME = "omni_settings"
        private const val KEY_THEME = "key_app_theme"
        private const val KEY_PREFERRED_AI = "key_preferred_ai_app"
    }

    override fun getThemeMode(): Flow<AppThemeMode> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_THEME) {
                trySend(readCurrentTheme())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(readCurrentTheme())

        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()

    override suspend fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    override fun getPreferredAiApp(): Flow<com.omni.assistant.ai.bridge.SupportedAiApp> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_PREFERRED_AI) {
                trySend(readCurrentPreferredAi())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(readCurrentPreferredAi())

        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()

    override suspend fun setPreferredAiApp(app: com.omni.assistant.ai.bridge.SupportedAiApp) {
        prefs.edit().putString(KEY_PREFERRED_AI, app.name).apply()
    }

    private fun readCurrentTheme(): AppThemeMode {
        val raw = prefs.getString(KEY_THEME, AppThemeMode.DARK.name)
        return try {
            AppThemeMode.valueOf(raw ?: AppThemeMode.DARK.name)
        } catch (e: Exception) {
            AppThemeMode.DARK
        }
    }

    private fun readCurrentPreferredAi(): com.omni.assistant.ai.bridge.SupportedAiApp {
        val raw = prefs.getString(KEY_PREFERRED_AI, com.omni.assistant.ai.bridge.SupportedAiApp.AUTO.name)
        return try {
            com.omni.assistant.ai.bridge.SupportedAiApp.valueOf(raw ?: com.omni.assistant.ai.bridge.SupportedAiApp.AUTO.name)
        } catch (e: Exception) {
            com.omni.assistant.ai.bridge.SupportedAiApp.AUTO
        }
    }
}
