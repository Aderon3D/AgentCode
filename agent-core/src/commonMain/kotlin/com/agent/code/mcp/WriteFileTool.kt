package com.agent.code.mcp

import com.agent.code.core.path.VirtualPath
import com.agent.code.core.tools.RiskLevel
import com.agent.code.core.tools.ToolResult
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WriteFileTool : AgentTool {
    override val name = "write_file"
    override val description = "Create or overwrite a file with given content"
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult {
        val obj = try {
            Json.parseToJsonElement(argumentsJson).jsonObject
        } catch (_: Exception) {
            return ToolResult(name, false, "invalid arguments json", 0L)
        }
        val path = obj["path"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(name, false, "missing 'path'", 0L)
        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""

        val vp = VirtualPath.of(path)
        return try {
            fileSystem.write(vp, content).fold(
                onSuccess = { ToolResult(name, true, "wrote ${content.length} bytes to $path", 0L) },
                onFailure = { e -> ToolResult(name, false, "write failed: ${e.message}", 0L) }
            )
        } catch (e: Exception) {
            ToolResult(name, false, "error: ${e.message}", 0L)
        }
    }
}
