package com.agent.code.mcp

import com.agent.code.core.path.VirtualPath
import com.agent.code.core.tools.RiskLevel
import com.agent.code.core.tools.ToolResult
import com.agent.code.security.AuditLog
import com.agent.code.security.EmergencyStop
import com.agent.code.security.GitDecision
import com.agent.code.security.GitGuard
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessConfiguration
import com.agent.code.workspace.ProcessOutput
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
        try {
            EmergencyStop.check()
        } catch (e: IllegalStateException) {
            AuditLog.log(name, argumentsJson, false, e.message ?: "stopped", blocked = true, blockReason = "emergency stop")
            return ToolResult(name, false, "emergency stop activated", 0L)
        }
        return executeGitCommand(argumentsJson, processRunner)
    }

    private suspend fun executeGitCommand(argumentsJson: String, processRunner: ProcessRunner): ToolResult {
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

        val decision = GitGuard.isAllowed(subcommand, args)
        if (decision is GitDecision.Blocked) {
            AuditLog.log(name, argumentsJson, false, decision.reason, blocked = true, blockReason = decision.reason)
            return ToolResult(name, false, "blocked: ${decision.reason}", 0L)
        }

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
        return runGit(config, argumentsJson, System.currentTimeMillis(), processRunner)
    }

    // Boundary catch-all: the process runner is external, so map any throw to a ToolResult.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runGit(
        config: ProcessConfiguration,
        argumentsJson: String,
        start: Long,
        processRunner: ProcessRunner
    ): ToolResult {
        return try {
            processRunner.execute(config).fold(
                onSuccess = { output -> onGitSuccess(output, argumentsJson, start) },
                onFailure = { e -> onGitFailure(e, argumentsJson, start) }
            )
        } catch (e: Exception) {
            AuditLog.log(name, argumentsJson, false, e.message ?: "error")
            ToolResult(name, false, "error: ${e.message}", System.currentTimeMillis() - start)
        }
    }

    private fun onGitSuccess(output: ProcessOutput, argumentsJson: String, start: Long): ToolResult {
        val elapsed = System.currentTimeMillis() - start
        val combined = buildString {
            if (output.stdout.isNotBlank()) appendLine(output.stdout)
            if (output.stderr.isNotBlank()) appendLine("STDERR:\n${output.stderr}")
        }.trim()
        val success = output.exitCode == 0
        AuditLog.log(name, argumentsJson, success, combined)
        return ToolResult(name, success, combined.ifBlank { "exit ${output.exitCode}" }, elapsed)
    }

    private fun onGitFailure(e: Throwable, argumentsJson: String, start: Long): ToolResult {
        AuditLog.log(name, argumentsJson, false, e.message ?: "failed")
        return ToolResult(name, false, "git failed: ${e.message}", System.currentTimeMillis() - start)
    }
}
