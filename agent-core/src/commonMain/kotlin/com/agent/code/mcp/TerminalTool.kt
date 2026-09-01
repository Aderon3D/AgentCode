package com.agent.code.mcp

import com.agent.code.core.path.VirtualPath
import com.agent.code.core.tools.RiskLevel
import com.agent.code.core.tools.ToolResult
import com.agent.code.security.AuditLog
import com.agent.code.security.CommandAllowlist
import com.agent.code.security.EmergencyStop
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessConfiguration
import com.agent.code.workspace.ProcessRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class TerminalTool : AgentTool {
    override val name = "run_command"
    override val description = "Execute a shell command and return stdout/stderr"
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(argumentsJson: String, fileSystem: FileSystemProvider, processRunner: ProcessRunner): ToolResult {
        EmergencyStop.check()

        val obj = try {
            Json.parseToJsonElement(argumentsJson).jsonObject
        } catch (_: Exception) {
            return ToolResult(name, false, "invalid arguments json", 0L)
        }
        val command = obj["command"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult(name, false, "missing 'command'", 0L)
        val workingDir = obj["working_dir"]?.jsonPrimitive?.contentOrNull
        val timeout = obj["timeout_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 30_000L

        if (!CommandAllowlist.isAllowed(command)) {
            val reason = CommandAllowlist.reason(command) ?: "command blocked"
            AuditLog.log(name, argumentsJson, false, reason, blocked = true, blockReason = reason)
            return ToolResult(name, false, "blocked: $reason", 0L)
        }

        val dir = if (workingDir != null) VirtualPath.of(workingDir) else VirtualPath.of(".")
        val config = ProcessConfiguration(
            command = "sh",
            args = listOf("-c", command),
            workingDir = dir,
            timeoutMs = timeout
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
                    val success = output.exitCode == 0
                    AuditLog.log(name, argumentsJson, success, combined)
                    ToolResult(name, success, combined.ifBlank { "exit ${output.exitCode}" }, elapsed)
                },
                onFailure = { e ->
                    AuditLog.log(name, argumentsJson, false, e.message ?: "failed")
                    ToolResult(name, false, "command failed: ${e.message}", System.currentTimeMillis() - start)
                }
            )
        } catch (e: Exception) {
            AuditLog.log(name, argumentsJson, false, e.message ?: "error")
            ToolResult(name, false, "error: ${e.message}", System.currentTimeMillis() - start)
        }
    }
}
