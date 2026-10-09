package com.omni.assistant.device

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.omni.assistant.core.logging.OmniLogger
import com.omni.assistant.domain.model.AppInfo

open class AppInventoryProvider(private val context: Context? = null) {
    private val tag = "AppInventoryProvider"

    /**
     * Retrieves visible applications within standard Android package visibility constraints.
     * Combines launcher intent activity queries with installed application queries.
     * Safe against security exceptions and restricted visibility.
     */
    open fun getVisibleInstalledApplications(): List<AppInfo> {
        val ctx = context ?: return emptyList()
        return try {
            val pm = ctx.packageManager
            val appMap = mutableMapOf<String, AppInfo>()

            // 1. Query all launcher activities (standard way launchers discover apps)
            try {
                val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
                for (ri in resolveInfos) {
                    val pkg = ri.activityInfo?.packageName ?: continue
                    val label = try {
                        ri.loadLabel(pm).toString()
                    } catch (_: Exception) {
                        pkg
                    }
                    val isSystem = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val versionName = try {
                        pm.getPackageInfo(pkg, 0).versionName ?: "Unknown"
                    } catch (_: Exception) {
                        "Unknown"
                    }
                    appMap[pkg] = AppInfo(
                        packageName = pkg,
                        appName = label,
                        versionName = versionName,
                        isSystemApp = isSystem
                    )
                }
            } catch (e: Exception) {
                OmniLogger.w(tag, "Launcher activity query error: ${e.message}")
            }

            // 2. Query getInstalledApplications to pick up any remaining apps
            try {
                val installed = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                for (appInfo in installed) {
                    val pkg = appInfo.packageName
                    if (!appMap.containsKey(pkg)) {
                        val label = try {
                            pm.getApplicationLabel(appInfo).toString()
                        } catch (_: Exception) {
                            pkg
                        }
                        val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                        val versionName = try {
                            pm.getPackageInfo(pkg, 0).versionName ?: "Unknown"
                        } catch (_: Exception) {
                            "Unknown"
                        }
                        appMap[pkg] = AppInfo(
                            packageName = pkg,
                            appName = label,
                            versionName = versionName,
                            isSystemApp = isSystem
                        )
                    }
                }
            } catch (e: Exception) {
                OmniLogger.w(tag, "Installed applications query error: ${e.message}")
            }

            appMap.values.sortedBy { it.appName.lowercase() }
        } catch (e: SecurityException) {
            OmniLogger.w(tag, "Package visibility restricted: ${e.message}")
            emptyList()
        } catch (e: Exception) {
            OmniLogger.e(tag, "Error accessing application inventory", e)
            emptyList()
        }
    }
}
