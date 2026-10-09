package com.omni.assistant.device

import android.os.Build
import com.omni.assistant.core.logging.OmniLogger
import com.omni.assistant.domain.model.RootStatus
import java.io.File

object RootDetector {
    private const val TAG = "RootDetector"

    private val KNOWN_ROOT_PATHS = listOf(
        "/system/app/Superuser.apk",
        "/sbin/su",
        "/system/bin/su",
        "/system/xbin/su",
        "/data/local/xbin/su",
        "/data/local/bin/su",
        "/system/sd/xbin/su",
        "/system/bin/failsafe/su",
        "/data/local/su"
    )

    /**
     * Checks if root binaries or test-keys exist on the device.
     * Note: We do NOT execute privileged shell commands (e.g. su) to check root.
     * Only passive, safe file and system property inspections are performed.
     */
    fun detectRootStatus(): RootStatus {
        return try {
            val buildTags = Build.TAGS
            val hasTestKeys = buildTags != null && buildTags.contains("test-keys")

            val hasBinary = KNOWN_ROOT_PATHS.any { path ->
                try {
                    File(path).exists()
                } catch (e: SecurityException) {
                    OmniLogger.d(TAG, "SecurityException checking path $path: ${e.message}")
                    false
                }
            }

            if (hasBinary || hasTestKeys) {
                OmniLogger.i(TAG, "Root indicators detected (Passive check)")
                RootStatus.AVAILABLE
            } else {
                RootStatus.NOT_AVAILABLE
            }
        } catch (t: Throwable) {
            OmniLogger.w(TAG, "Unable to determine root status safely", t)
            RootStatus.UNKNOWN
        }
    }
}
