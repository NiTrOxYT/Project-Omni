package com.omni.assistant.domain.model

data class BatteryStatus(
    val percentage: Int?,
    val isCharging: Boolean,
    val powerSource: PowerSource,
    val health: String,
    val temperatureCelsius: Float?
) {
    val percentageText: String
        get() = percentage?.let { "$it%" } ?: "Unavailable"

    val statusSummary: String
        get() = buildString {
            if (percentage != null) append("$percentage% ")
            if (isCharging) append("(Charging via ${powerSource.name.lowercase().replaceFirstChar { it.uppercase() }})")
            else append("(Discharging)")
        }
}

enum class PowerSource {
    AC,
    USB,
    WIRELESS,
    DOCK,
    BATTERY,
    UNKNOWN
}
