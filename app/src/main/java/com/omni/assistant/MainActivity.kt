package com.omni.assistant

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.omni.assistant.core.logging.OmniLogger
import com.omni.assistant.domain.repository.AppThemeMode
import com.omni.assistant.presentation.navigation.OmniApp
import com.omni.assistant.presentation.theme.OmniTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as OmniApplication
        val container = app.container

        setContent {
            val themeMode by container.settingsRepository.getThemeMode().collectAsState(initial = AppThemeMode.DARK)
            OmniTheme(themeMode = themeMode) {
                OmniApp(container = container)
            }
        }

        handleCommandIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCommandIntent(intent)
    }

    private fun handleCommandIntent(intent: Intent?) {
        val command = intent?.getStringExtra("command")?.trim() ?: return
        if (command.isNotBlank()) {
            OmniLogger.i("MainActivity", "Received external command: '$command'")
            val app = application as OmniApplication
            lifecycleScope.launch {
                app.container.agentOrchestrator.execute(command)
            }
        }
    }
}
