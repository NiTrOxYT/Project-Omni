package com.omni.assistant.device

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.omni.assistant.core.logging.OmniLogger
import com.omni.assistant.domain.model.AppInfo

class AppInventoryProvider(private val context: Context) {
    private val tag = "AppInventoryProvider"

    /**
     * Retrieves visible applications within standard Android package visibility constraints.
     * Safe against security exceptions and restricted visibility.
     */
    fun getVisibleInstalledApplications(): List<AppInfo> {
        return try {
            val pm = context.packageManager
            val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)

            installed.mapNotNull { appInfo ->
                try {
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val packageInfo = pm.getPackageInfo(appInfo.packageName, 0)
                    val versionName = packageInfo.versionName ?: "Unknown"

                    AppInfo(
                        packageName = appInfo.packageName,
                        appName = label,
                        versionName = versionName,
                        isSystemApp = isSystem
                    )
                } catch (e: Exception) {
                    null
                }
            }.sortedBy { it.appName.lowercase() }
        } catch (e: SecurityException) {
            OmniLogger.w(tag, "Package visibility restricted: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            OmniLogger.e(tag, "Error accessing application inventory", e)
            emptyList()
        }
    }
}
