package com.omni.assistant.accessibility.lifecycle

import android.content.Context
import com.omni.assistant.core.logging.OmniLogger
import java.io.File

open class SnapshotLifecycleManager(
    private val context: Context? = null,
    baseDirectory: File? = null
) {
    private val tag = "SnapshotLifecycle"

    val tasksBaseDir: File by lazy {
        baseDirectory ?: File(context?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "."), "agent_tasks").apply {
            if (!exists()) mkdirs()
        }
    }

    fun getTaskWorkspace(taskId: String): File {
        val sanitizedId = sanitizeFilename(taskId)
        val dir = File(tasksBaseDir, sanitizedId)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun saveTemporaryArtifact(taskId: String, filename: String, data: ByteArray): File? {
        return try {
            val workspace = getTaskWorkspace(taskId)
            val file = File(workspace, sanitizeFilename(filename))
            file.writeBytes(data)
            OmniLogger.d(tag, "Saved temporary artifact: ${file.name} (${data.size} bytes) in workspace $taskId")
            file
        } catch (e: Exception) {
            OmniLogger.e(tag, "Failed to save temporary artifact: ${e.message}")
            null
        }
    }

    fun cleanupTaskWorkspace(taskId: String): Boolean {
        return try {
            val sanitizedId = sanitizeFilename(taskId)
            val dir = File(tasksBaseDir, sanitizedId)
            if (dir.exists() && isChildOf(dir, tasksBaseDir)) {
                val deleted = dir.deleteRecursively()
                OmniLogger.i(tag, "Cleaned up task workspace $taskId: success=$deleted")
                deleted
            } else {
                true
            }
        } catch (e: Exception) {
            OmniLogger.e(tag, "Error cleaning workspace $taskId: ${e.message}")
            false
        }
    }

    fun cleanupStaleWorkspaces(): Int {
        var count = 0
        try {
            if (tasksBaseDir.exists()) {
                tasksBaseDir.listFiles()?.forEach { taskDir ->
                    if (taskDir.isDirectory && isChildOf(taskDir, tasksBaseDir)) {
                        if (taskDir.deleteRecursively()) {
                            count++
                        }
                    }
                }
            }
            if (count > 0) {
                OmniLogger.i(tag, "Cleaned up $count stale task workspaces from previous session.")
            }
        } catch (e: Exception) {
            OmniLogger.e(tag, "Error cleaning stale workspaces: ${e.message}")
        }
        return count
    }

    private fun sanitizeFilename(name: String): String {
        return name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun isChildOf(child: File, parent: File): Boolean {
        val canonicalChild = child.canonicalPath
        val canonicalParent = parent.canonicalPath
        return canonicalChild.startsWith(canonicalParent)
    }
}
