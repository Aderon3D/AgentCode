package com.agent.code.mcp

import com.agent.code.core.path.VirtualPath
import com.agent.code.core.tools.RiskLevel
import com.agent.code.core.tools.ToolResult
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ReadFileTool(private val fileSystem: FileSystemProvider) : AgentTool {
    override val name = "read_file"
    override val description = "Reads file contents safely"
    override val riskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult {
        val path = Json.parseToJsonElement(argumentsJson).jsonObject["path"]?.toString()?.trim('"') ?: return ToolResult(name, false, "missing path", 0L)
        return try {
            ToolResult(name, true, fileSystem.read(VirtualPath.of(path)), 0L)
        } catch (e: Exception) {
            ToolResult(name, false, e.message ?: "read failed", 0L)
        }
    }
}

class ApplyPatchTool(private val fileSystem: FileSystemProvider) : AgentTool {
    override val name = "apply_diff_patch"
    override val description = "Applies search-and-replace atomic patch"
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult {
        val obj = Json.parseToJsonElement(argumentsJson).jsonObject
        val path = obj["path"]?.toString()?.trim('"') ?: return ToolResult(name, false, "missing path", 0L)
        val search = obj["search"]?.toString()?.trim('"') ?: return ToolResult(name, false, "missing search", 0L)
        val replace = obj["replace"]?.toString()?.trim('"') ?: ""
        val vp = VirtualPath.of(path)
        return try {
            val original = fileSystem.read(vp)
            if (!original.contains(search)) return ToolResult(name, false, "search not found in $path", 0L)
            fileSystem.write(vp, original.replaceFirst(search, replace))
            ToolResult(name, true, "patched $path", 0L)
        } catch (e: Exception) {
            ToolResult(name, false, e.message ?: "patch failed", 0L)
        }
    }
}
