package com.omni.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.omni.assistant.domain.repository.AppThemeMode
import com.omni.assistant.presentation.navigation.OmniApp
import com.omni.assistant.presentation.theme.OmniTheme

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
    }
}
