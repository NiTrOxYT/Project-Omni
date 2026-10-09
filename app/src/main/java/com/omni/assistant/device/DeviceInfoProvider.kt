package com.omni.assistant.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.omni.assistant.core.logging.OmniLogger
import com.omni.assistant.domain.model.DeviceStatus

class DeviceInfoProvider(private val context: Context) {
    private val tag = "DeviceInfoProvider"

    fun getDeviceInfo(): DeviceStatus {
        val appVersion = getAppVersionName()
        val rootStatus = RootDetector.detectRootStatus()

        return DeviceStatus(
            manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() },
            model = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE ?: "Unknown",
            sdkInt = Build.VERSION.SDK_INT,
            appVersion = appVersion,
            rootStatus = rootStatus
        )
    }

    fun getAppVersionName(): String {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            packageInfo.versionName ?: "1.0.0-phase1"
        } catch (e: Exception) {
            OmniLogger.w(tag, "Failed to resolve app version name", e)
            "1.0.0-phase1"
        }
    }
}
