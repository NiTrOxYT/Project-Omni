package com.omni.assistant

import com.omni.assistant.domain.model.StorageStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageStatusTest {

    @Test
    fun testStorageCalculations() {
        val total = 256L * 1024 * 1024 * 1024 // 256 GB
        val available = 128L * 1024 * 1024 * 1024 // 128 GB
        val free = 128L * 1024 * 1024 * 1024

        val status = StorageStatus(
            volumeName = "Internal Storage (/data)",
            totalBytes = total,
            availableBytes = available,
            freeBytes = free
        )

        assertEquals(128L * 1024 * 1024 * 1024, status.usedBytes)
        assertEquals(50.0f, status.usedPercentage, 0.1f)
        assertTrue(status.totalFormatted.contains("256"))
        assertTrue(status.availableFormatted.contains("128"))
        assertTrue(status.usedFormatted.contains("128"))
    }

    @Test
    fun testZeroStorageHandling() {
        val status = StorageStatus(
            volumeName = "Unresolved Storage",
            totalBytes = 0L,
            availableBytes = 0L,
            freeBytes = 0L
        )

        assertEquals(0L, status.usedBytes)
        assertEquals(0f, status.usedPercentage, 0.01f)
        assertEquals("0 B", status.totalFormatted)
    }

    @Test
    fun testFormatBytesUtility() {
        assertEquals("0 B", StorageStatus.formatBytes(0))
        assertEquals("1.0 KB", StorageStatus.formatBytes(1024))
        assertEquals("1.0 MB", StorageStatus.formatBytes(1024 * 1024))
        assertEquals("1.5 GB", StorageStatus.formatBytes((1.5 * 1024 * 1024 * 1024).toLong()))
    }
}
