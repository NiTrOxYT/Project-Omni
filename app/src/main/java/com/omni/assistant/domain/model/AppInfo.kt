package com.omni.assistant.domain.model

data class AppInfo(
    val packageName: String,
    val appName: String,
    val versionName: String,
    val isSystemApp: Boolean
)
