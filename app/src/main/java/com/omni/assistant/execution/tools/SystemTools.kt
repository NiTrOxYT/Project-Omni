package com.omni.assistant.execution.tools

import com.omni.assistant.device.AppInventoryProvider
import com.omni.assistant.device.BatteryInfoProvider
import com.omni.assistant.device.DeviceInfoProvider
import com.omni.assistant.device.StorageInfoProvider
import com.omni.assistant.domain.model.RiskLevel
import com.omni.assistant.execution.OmniTool
import com.omni.assistant.execution.ToolDefinition
import com.omni.assistant.execution.ToolResult

class GetDeviceInfoTool(
    private val deviceInfoProvider: DeviceInfoProvider
) : OmniTool {
    override val definition = ToolDefinition(
        name = "get_device_info",
        description = "Retrieves device hardware, model, manufacturer, and OS version details.",
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            val info = deviceInfoProvider.getDeviceInfo()
            val data = mapOf(
                "manufacturer" to info.manufacturer,
                "model" to info.model,
                "androidRelease" to info.androidRelease,
                "sdkInt" to info.sdkInt,
                "appVersion" to info.appVersion,
                "rootStatus" to info.rootStatus.name
            )
            val summary = "Device: ${info.displayTitle} | ${info.osTitle} | Root: ${info.rootStatus}"
            ToolResult(
                success = true,
                tool = definition.name,
                data = data,
                summaryText = summary
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                tool = definition.name,
                summaryText = "Failed to retrieve device information: ${e.message}",
                error = e.localizedMessage ?: "Unknown error"
            )
        }
    }
}

class GetStorageInfoTool(
    private val storageInfoProvider: StorageInfoProvider
) : OmniTool {
    override val definition = ToolDefinition(
        name = "get_storage_info",
        description = "Retrieves primary user storage volume capacity, available space, and used space.",
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            val storage = storageInfoProvider.getPrimaryStorageInfo()
            val data = mapOf(
                "volumeName" to storage.volumeName,
                "totalBytes" to storage.totalBytes,
                "availableBytes" to storage.availableBytes,
                "freeBytes" to storage.freeBytes,
                "usedBytes" to storage.usedBytes,
                "totalFormatted" to storage.totalFormatted,
                "availableFormatted" to storage.availableFormatted,
                "usedFormatted" to storage.usedFormatted,
                "usedPercentage" to storage.usedPercentage
            )
            val summary = "Storage (${storage.volumeName}): ${storage.usedFormatted} used of ${storage.totalFormatted} (${storage.availableFormatted} free)"
            ToolResult(
                success = true,
                tool = definition.name,
                data = data,
                summaryText = summary
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                tool = definition.name,
                summaryText = "Failed to retrieve storage status: ${e.message}",
                error = e.localizedMessage ?: "Unknown error"
            )
        }
    }
}

class GetBatteryInfoTool(
    private val batteryInfoProvider: BatteryInfoProvider
) : OmniTool {
    override val definition = ToolDefinition(
        name = "get_battery_info",
        description = "Retrieves device battery level percentage, charging status, and power source.",
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            val battery = batteryInfoProvider.getBatteryInfo()
            val data = mapOf(
                "percentage" to battery.percentage,
                "isCharging" to battery.isCharging,
                "powerSource" to battery.powerSource.name,
                "health" to battery.health,
                "temperatureCelsius" to battery.temperatureCelsius
            )
            val summary = "Battery: ${battery.statusSummary} | Health: ${battery.health}"
            ToolResult(
                success = true,
                tool = definition.name,
                data = data,
                summaryText = summary
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                tool = definition.name,
                summaryText = "Failed to retrieve battery status: ${e.message}",
                error = e.localizedMessage ?: "Unknown error"
            )
        }
    }
}

class GetAppVersionTool(
    private val deviceInfoProvider: DeviceInfoProvider
) : OmniTool {
    override val definition = ToolDefinition(
        name = "get_app_version",
        description = "Retrieves the installed version and build identity of the Omni assistant app.",
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            val version = deviceInfoProvider.getAppVersionName()
            val data = mapOf(
                "appName" to "Omni",
                "version" to version,
                "phase" to "Phase 1 - Android Foundation"
            )
            val summary = "Omni Assistant version: $version (Phase 1 Foundation)"
            ToolResult(
                success = true,
                tool = definition.name,
                data = data,
                summaryText = summary
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                tool = definition.name,
                summaryText = "Failed to retrieve app version: ${e.message}",
                error = e.localizedMessage ?: "Unknown error"
            )
        }
    }
}

class GetInstalledAppsTool(
    private val appInventoryProvider: AppInventoryProvider
) : OmniTool {
    override val definition = ToolDefinition(
        name = "get_installed_apps",
        description = "Retrieves the inventory of visible and installed applications on the device.",
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            val apps = appInventoryProvider.getVisibleInstalledApplications()
            val appNames = apps.map { it.appName }
            val count = apps.size
            val preview = appNames.take(15).joinToString(", ")
            val summary = if (count > 15) {
                "Found $count installed applications: $preview, and ${count - 15} more."
            } else {
                "Found $count installed applications: $preview."
            }
            ToolResult(
                success = true,
                tool = definition.name,
                data = mapOf(
                    "count" to count,
                    "apps" to appNames
                ),
                summaryText = summary
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                tool = definition.name,
                summaryText = "Failed to retrieve installed applications: ${e.message}",
                error = e.localizedMessage ?: "Unknown error"
            )
        }
    }
}

class GetConnectivityStatusTool(
    private val context: android.content.Context
) : OmniTool {
    override val definition = ToolDefinition(
        name = "get_connectivity_status",
        description = "Checks network connectivity (Wi-Fi, cellular, internet access) and Bluetooth adapter status.",
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val activeNetwork = cm?.activeNetwork
            val caps = cm?.getNetworkCapabilities(activeNetwork)

            val isWifi = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            val isCellular = caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val hasInternet = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true

            val btManager = context.getSystemService(android.content.Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val isBluetoothOn = try {
                btManager?.adapter?.isEnabled
            } catch (_: SecurityException) {
                try {
                    android.provider.Settings.Global.getInt(context.contentResolver, "bluetooth_on", 0) != 0
                } catch (_: Exception) { null }
            }

            val wifiStatus = if (isWifi) "Connected" else "Disconnected"
            val cellularStatus = if (isCellular) "Connected" else "Disconnected"
            val internetStatus = if (hasInternet) "Available" else "Not available"
            val btStatus = when (isBluetoothOn) {
                true -> "Enabled"
                false -> "Disabled"
                null -> "Unknown (Permission restricted)"
            }

            val summary = "Wi-Fi: $wifiStatus | Cellular: $cellularStatus | Internet: $internetStatus | Bluetooth: $btStatus"

            ToolResult(
                success = true,
                tool = definition.name,
                data = mapOf(
                    "isWifiConnected" to isWifi,
                    "isCellularConnected" to isCellular,
                    "hasInternet" to hasInternet,
                    "isBluetoothEnabled" to (isBluetoothOn == true),
                    "bluetoothStatus" to btStatus
                ),
                summaryText = summary
            )
        } catch (e: Exception) {
            ToolResult(
                success = false,
                tool = definition.name,
                summaryText = "Failed to retrieve connectivity status: ${e.message}",
                error = e.localizedMessage ?: "Unknown error"
            )
        }
    }
}
