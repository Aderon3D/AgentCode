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

class ReadFileTool(private val fileSystem: FileSystemProvider) : AgentTool {
    override val name = "read_file"
    override val description = "Reads file contents safely"
    override val riskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult {
        val obj = try {
            Json.parseToJsonElement(argumentsJson).jsonObject
        } catch (_: Exception) {
            return ToolResult(name, false, "invalid arguments json", 0L)
        }
        val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(name, false, "missing path", 0L)
        return fileSystem.read(VirtualPath.of(path)).fold(
            onSuccess = { ToolResult(name, true, it, 0L) },
            onFailure = { ToolResult(name, false, it.message ?: "read failed", 0L) }
        )
    }
}

class ApplyPatchTool(private val fileSystem: FileSystemProvider) : AgentTool {
    override val name = "apply_diff_patch"
    override val description = "Applies search-and-replace atomic patch"
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult {
        val obj = try {
            Json.parseToJsonElement(argumentsJson).jsonObject
        } catch (_: Exception) {
            return ToolResult(name, false, "invalid arguments json", 0L)
        }
        val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(name, false, "missing path", 0L)
        val search = obj["search"]?.jsonPrimitive?.contentOrNull ?: return ToolResult(name, false, "missing search", 0L)
        val replace = obj["replace"]?.jsonPrimitive?.contentOrNull ?: ""
        val vp = VirtualPath.of(path)
        val original = fileSystem.read(vp).getOrElse {
            return ToolResult(name, false, it.message ?: "read failed", 0L)
        }
        if (!original.contains(search)) return ToolResult(name, false, "search not found in $path", 0L)
        fileSystem.write(vp, original.replaceFirst(search, replace)).onFailure {
            return ToolResult(name, false, it.message ?: "write failed", 0L)
        }
        return ToolResult(name, true, "patched $path", 0L)
    }
}
