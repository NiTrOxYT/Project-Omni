package com.omni.assistant.ai.config

import android.content.Context
import android.content.SharedPreferences
import com.omni.assistant.core.logging.OmniLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

data class AiConfig(
    val apiKey: String = "",
    val modelName: String = "gemini-flash-latest",
    val useCloudAi: Boolean = true,
    val maxIterations: Int = 5
) {
    val hasValidApiKey: Boolean
        get() = apiKey.isNotBlank() && apiKey.trim().length >= 10
}

interface AiConfigRepository {
    fun getConfig(): Flow<AiConfig>
    fun getCurrentConfig(): AiConfig
    suspend fun updateApiKey(apiKey: String)
    suspend fun updateModel(modelName: String)
    suspend fun updateUseCloudAi(useCloudAi: Boolean)
}

class AiConfigRepositoryImpl(
    private val context: Context
) : AiConfigRepository {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    companion object {
        private const val PREFS_NAME = "omni_ai_config"
        private const val KEY_API_KEY = "gemini_api_key"
        private const val KEY_MODEL_NAME = "gemini_model_name"
        private const val KEY_USE_CLOUD_AI = "use_cloud_ai"
        private const val KEY_MAX_ITERATIONS = "max_iterations"
        const val DEFAULT_MODEL = "gemini-flash-latest"
    }

    override fun getCurrentConfig(): AiConfig {
        val key = prefs.getString(KEY_API_KEY, "") ?: ""
        val model = prefs.getString(KEY_MODEL_NAME, DEFAULT_MODEL) ?: DEFAULT_MODEL
        val useCloud = prefs.getBoolean(KEY_USE_CLOUD_AI, true)
        val maxIter = prefs.getInt(KEY_MAX_ITERATIONS, 5)
        return AiConfig(
            apiKey = key,
            modelName = model,
            useCloudAi = useCloud,
            maxIterations = maxIter
        )
    }

    override fun getConfig(): Flow<AiConfig> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(getCurrentConfig())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getCurrentConfig())

        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()

    override suspend fun updateApiKey(apiKey: String) {
        prefs.edit().putString(KEY_API_KEY, apiKey.trim()).apply()
        OmniLogger.i("AiConfig", "Updated AI API Key configuration (stored safely)")
    }

    override suspend fun updateModel(modelName: String) {
        prefs.edit().putString(KEY_MODEL_NAME, modelName.trim()).apply()
    }

    override suspend fun updateUseCloudAi(useCloudAi: Boolean) {
        prefs.edit().putBoolean(KEY_USE_CLOUD_AI, useCloudAi).apply()
    }
}
