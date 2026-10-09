package com.omni.assistant.domain.model

import java.util.Locale

data class StorageStatus(
    val volumeName: String,
    val totalBytes: Long,
    val availableBytes: Long,
    val freeBytes: Long
) {
    val usedBytes: Long
        get() = (totalBytes - availableBytes).coerceAtLeast(0L)

    val usedPercentage: Float
        get() = if (totalBytes > 0) {
            (usedBytes.toFloat() / totalBytes.toFloat()) * 100f
        } else {
            0f
        }

    val totalFormatted: String
        get() = formatBytes(totalBytes)

    val availableFormatted: String
        get() = formatBytes(availableBytes)

    val usedFormatted: String
        get() = formatBytes(usedBytes)

    companion object {
        fun formatBytes(bytes: Long): String {
            if (bytes <= 0) return "0 B"
            val units = arrayOf("B", "KB", "MB", "GB", "TB")
            val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
            val clampedGroup = digitGroups.coerceIn(0, units.size - 1)
            val value = bytes / Math.pow(1024.0, clampedGroup.toDouble())
            return String.format(Locale.US, "%.1f %s", value, units[clampedGroup])
        }
    }
}
