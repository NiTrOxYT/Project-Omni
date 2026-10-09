package com.omni.assistant.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.omni.assistant.core.logging.OmniLogger
import com.omni.assistant.domain.model.BatteryStatus
import com.omni.assistant.domain.model.PowerSource

class BatteryInfoProvider(private val context: Context) {
    private val tag = "BatteryInfoProvider"

    fun getBatteryInfo(): BatteryStatus {
        return try {
            val intentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val batteryIntent: Intent? = context.registerReceiver(null, intentFilter)

            if (batteryIntent != null) {
                val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                val healthRaw = batteryIntent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
                val tempRaw = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)

                val percentage = if (level >= 0 && scale > 0) {
                    ((level.toFloat() / scale.toFloat()) * 100).toInt().coerceIn(0, 100)
                } else {
                    getPercentageFromManager()
                }

                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                val powerSource = when (plugged) {
                    BatteryManager.BATTERY_PLUGGED_AC -> PowerSource.AC
                    BatteryManager.BATTERY_PLUGGED_USB -> PowerSource.USB
                    BatteryManager.BATTERY_PLUGGED_WIRELESS -> PowerSource.WIRELESS
                    4 -> PowerSource.DOCK // BATTERY_PLUGGED_DOCK (API 33+)
                    0 -> PowerSource.BATTERY
                    else -> PowerSource.UNKNOWN
                }

                val health = when (healthRaw) {
                    BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                    BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
                    BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
                    BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
                    BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Unspecified Failure"
                    BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
                    else -> "Unknown"
                }

                val tempCelsius = if (tempRaw > 0) tempRaw / 10f else null

                BatteryStatus(
                    percentage = percentage,
                    isCharging = isCharging,
                    powerSource = powerSource,
                    health = health,
                    temperatureCelsius = tempCelsius
                )
            } else {
                val fallbackPercentage = getPercentageFromManager()
                BatteryStatus(
                    percentage = fallbackPercentage,
                    isCharging = false,
                    powerSource = PowerSource.BATTERY,
                    health = "Unknown",
                    temperatureCelsius = null
                )
            }
        } catch (e: Exception) {
            OmniLogger.w(tag, "Failed to retrieve battery status", e)
            BatteryStatus(
                percentage = null,
                isCharging = false,
                powerSource = PowerSource.UNKNOWN,
                health = "Unknown",
                temperatureCelsius = null
            )
        }
    }

    private fun getPercentageFromManager(): Int? {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val capacity = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (capacity != null && capacity in 0..100) {
                capacity
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
