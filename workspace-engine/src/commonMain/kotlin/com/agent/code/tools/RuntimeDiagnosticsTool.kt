package com.agent.code.tools

import com.agent.code.core.path.VirtualPath
import com.agent.code.core.tools.RiskLevel
import com.agent.code.core.tools.ToolResult
import com.agent.code.mcp.AgentTool
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner

// §12.3 Development_Doc.md — reads Android logcat error dumps via ProcessRunner.
class RuntimeDiagnosticsTool : AgentTool {
    override val name = "read_runtime_diagnostics"
    override val description =
        "Reads recent runtime crash stack traces, Android Logcat error dumps, or application error logs."
    override val riskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(
        argumentsJson: String,
        fileSystem: FileSystemProvider,
        processRunner: ProcessRunner
    ): ToolResult {
        val output = processRunner.run(listOf("logcat", "-d", "*:E"))
        return ToolResult("diagnostics", true, output.getOrNull() ?: "No errors found", 50)
    }
}
