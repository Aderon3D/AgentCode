package com.agent.code.mcp

import com.agent.code.core.path.VirtualPath
import com.agent.code.core.tools.RiskLevel
import com.agent.code.core.tools.ToolResult
import com.agent.code.workspace.FileNode
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class ListDirectoryTool : AgentTool {
    override val name = "list_directory"
    override val description = "List files and subdirectories in a directory"
    override val riskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult {
        val obj = try {
            Json.parseToJsonElement(argumentsJson).jsonObject
        } catch (_: Exception) {
            return ToolResult(name, false, "invalid arguments json", 0L)
        }
        val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: "."
        val vp = VirtualPath.of(path)

        return try {
            val result = fileSystem.walkTree(vp, maxDepth = 1)
            result.fold(
                onSuccess = { dir ->
                    val listing = dir.children.joinToString("\n") { node ->
                        val prefix = when (node) {
                            is FileNode.Directory -> "d "
                            is FileNode.File -> "f "
                        }
                        "$prefix${node.name}"
                    }
                    ToolResult(name, true, listing.ifBlank { "(empty directory)" }, 0L)
                },
                onFailure = { e ->
                    ToolResult(name, false, "list failed: ${e.message}", 0L)
                }
            )
        } catch (e: Exception) {
            ToolResult(name, false, "error: ${e.message}", 0L)
        }
    }
}
