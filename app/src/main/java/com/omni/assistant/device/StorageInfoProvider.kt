package com.omni.assistant.device

import android.os.Environment
import android.os.StatFs
import com.omni.assistant.core.logging.OmniLogger
import com.omni.assistant.domain.model.StorageStatus

class StorageInfoProvider {
    private val tag = "StorageInfoProvider"

    /**
     * Inspects primary internal data volume.
     * Accurately labels the volume as "Internal User Storage (/data)".
     */
    fun getPrimaryStorageInfo(): StorageStatus {
        return try {
            val path = Environment.getDataDirectory()
            val stat = StatFs(path.path)

            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong
            val freeBlocks = stat.freeBlocksLong

            val totalBytes = totalBlocks * blockSize
            val availableBytes = availableBlocks * blockSize
            val freeBytes = freeBlocks * blockSize

            StorageStatus(
                volumeName = "Internal Storage (/data)",
                totalBytes = totalBytes,
                availableBytes = availableBytes,
                freeBytes = freeBytes
            )
        } catch (e: Exception) {
            OmniLogger.e(tag, "Failed to inspect primary storage", e)
            StorageStatus(
                volumeName = "Internal Storage (Unresolved)",
                totalBytes = 0L,
                availableBytes = 0L,
                freeBytes = 0L
            )
        }
    }
}
