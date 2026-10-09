package com.omni.assistant.execution

import com.omni.assistant.core.logging.OmniLogger
import java.util.concurrent.ConcurrentHashMap

class ToolRegistry {
    private val tag = "ToolRegistry"
    private val tools = ConcurrentHashMap<String, OmniTool>()

    fun register(tool: OmniTool) {
        val name = tool.definition.name.lowercase()
        tools[name] = tool
        OmniLogger.d(tag, "Registered tool: $name")
    }

    fun getTool(name: String): OmniTool? {
        return tools[name.lowercase()]
    }

    fun getAllTools(): List<OmniTool> {
        return tools.values.toList()
    }

    fun getAllDefinitions(): List<ToolDefinition> {
        return tools.values.map { it.definition }
    }
}
