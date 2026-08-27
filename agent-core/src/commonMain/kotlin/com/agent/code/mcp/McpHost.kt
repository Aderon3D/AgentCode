package com.agent.code.mcp

import com.agent.code.core.path.VirtualPath
import com.agent.code.core.tools.RiskLevel
import com.agent.code.core.tools.ToolResult
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner

interface AgentTool {
    val name: String
    val description: String
    val riskLevel: RiskLevel
    suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult
}

class McpHost(
    private val fileSystem: FileSystemProvider,
    private val processRunner: ProcessRunner
) {
    private val tools: Map<String, AgentTool> = mapOf(
        ReadFileTool(fileSystem).let { it.name to it },
        ApplyPatchTool(fileSystem).let { it.name to it }
    ).toMap()

    suspend fun dispatch(toolCall: com.agent.code.core.fsm.ToolCall): ToolResult {
        val tool = tools[toolCall.toolName]
            ?: return ToolResult(toolCall.id, false, "unknown tool: ${toolCall.toolName}", 0L)
        return tool.execute(toolCall.argumentsJson, fileSystem, processRunner)
    }

    fun listTools(): List<String> = tools.keys.toList()
}
