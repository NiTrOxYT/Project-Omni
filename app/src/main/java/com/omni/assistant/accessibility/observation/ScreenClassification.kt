package com.omni.assistant.accessibility.observation

enum class ScreenClassification {
    APP_LAUNCHER_HOME,
    CHAT_LIST,
    CONVERSATION,
    SEARCH_ACTIVE,
    SEARCH_RESULTS,
    SETTINGS_MAIN,
    SYSTEM_DIALOG,
    INPUT_FORM,
    UNKNOWN
}

enum class ObservationStatus {
    COMPLETE,
    PARTIAL,
    STALE,
    UNAVAILABLE
}
