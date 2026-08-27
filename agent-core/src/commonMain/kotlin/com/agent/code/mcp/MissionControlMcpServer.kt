package com.agent.code.mcp

import com.agent.code.core.tools.RiskLevel
import com.agent.code.core.tools.ToolResult
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MissionControlMcpServer(
    private val mcpHost: McpHost,
    private val fileSystem: FileSystemProvider,
    private val processRunner: ProcessRunner
) {
    private val uiTools: Map<String, AgentTool> = mapOf(
        InspectUiTool().let { it.name to it },
        InteractUiTool().let { it.name to it }
    )

    private val allTools: Map<String, AgentTool> = mcpHost.listTools().associateWith { name ->
        object : AgentTool {
            override val name = name
            override val description = "Delegated to McpHost"
            override val riskLevel = RiskLevel.READ_ONLY
            override suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult =
                mcpHost.dispatch(com.agent.code.core.fsm.ToolCall("mcp-$name", name, argumentsJson))
        }
    } + uiTools

    suspend fun dispatch(toolCall: com.agent.code.core.fsm.ToolCall): ToolResult {
        val tool = allTools[toolCall.toolName]
            ?: return ToolResult(toolCall.id, false, "unknown tool: ${toolCall.toolName}", 0L)
        return tool.execute(toolCall.argumentsJson, fileSystem, processRunner)
    }

    fun listTools(): List<McpToolDefinition> = allTools.values.map {
        McpToolDefinition(it.name, it.description)
    }
}

data class McpToolDefinition(val name: String, val description: String)

private class InspectUiTool : AgentTool {
    override val name = "inspect_ui_state"
    override val description = "Dumps Android/Windows semantic layout tree"
    override val riskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult {
        return ToolResult("inspect_ui", true, "UI inspection not yet wired (requires AccessibilityEngine)", 0L)
    }
}

private class InteractUiTool : AgentTool {
    override val name = "interact_ui_element"
    override val description = "Performs native click, text input, or swipe gestures"
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult {
        return ToolResult("interact_ui", true, "UI interaction not yet wired (requires AccessibilityEngine)", 0L)
    }
}
