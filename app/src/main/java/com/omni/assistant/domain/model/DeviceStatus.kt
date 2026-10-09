package com.omni.assistant.domain.model

data class DeviceStatus(
    val manufacturer: String,
    val model: String,
    val androidRelease: String,
    val sdkInt: Int,
    val appVersion: String,
    val rootStatus: RootStatus = RootStatus.UNKNOWN
) {
    val displayTitle: String
        get() = "$manufacturer $model"

    val osTitle: String
        get() = "Android $androidRelease (API $sdkInt)"
}
