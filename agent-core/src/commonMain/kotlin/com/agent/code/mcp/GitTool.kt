package com.agent.code.mcp

import com.agent.code.core.path.VirtualPath
import com.agent.code.core.tools.RiskLevel
import com.agent.code.core.tools.ToolResult
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessConfiguration
import com.agent.code.workspace.ProcessRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class GitTool : AgentTool {
    override val name = "git"
    override val description = "Run git commands: diff, status, log, branch, commit, checkout"
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult {
        val obj = try {
            Json.parseToJsonElement(argumentsJson).jsonObject
        } catch (_: Exception) {
            return ToolResult(name, false, "invalid arguments json", 0L)
        }
        val subcommand = obj["subcommand"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(name, false, "missing 'subcommand' (diff|status|log|branch|commit|checkout)", 0L)
        val args = obj["args"]?.jsonPrimitive?.contentOrNull ?: ""
        val workingDir = obj["working_dir"]?.jsonPrimitive?.contentOrNull
        val message = obj["message"]?.jsonPrimitive?.contentOrNull

        val dir = if (workingDir != null) VirtualPath.of(workingDir) else VirtualPath.of(".")
        val cmd = buildList {
            add("git")
            add(subcommand)
            if (message != null && subcommand == "commit") {
                add("-m")
                add(message)
            }
            if (args.isNotBlank()) {
                addAll(args.split("\\s+".toRegex()).filter { it.isNotBlank() })
            }
        }

        val config = ProcessConfiguration(
            command = cmd.first(),
            args = cmd.drop(1),
            workingDir = dir,
            timeoutMs = 30_000
        )
        val start = System.currentTimeMillis()
        return try {
            val result = processRunner.execute(config)
            result.fold(
                onSuccess = { output ->
                    val elapsed = System.currentTimeMillis() - start
                    val combined = buildString {
                        if (output.stdout.isNotBlank()) appendLine(output.stdout)
                        if (output.stderr.isNotBlank()) appendLine("STDERR:\n${output.stderr}")
                    }.trim()
                    ToolResult(name, output.exitCode == 0, combined.ifBlank { "exit ${output.exitCode}" }, elapsed)
                },
                onFailure = { e ->
                    ToolResult(name, false, "git failed: ${e.message}", System.currentTimeMillis() - start)
                }
            )
        } catch (e: Exception) {
            ToolResult(name, false, "error: ${e.message}", System.currentTimeMillis() - start)
        }
    }
}
