package com.agent.code.tools

import com.agent.code.core.tools.RiskLevel
import com.agent.code.core.tools.ToolResult
import com.agent.code.mcp.AgentTool
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URL

class FetchDocTool : AgentTool {
    override val name = "fetch_documentation"
    override val description =
        "Fetches latest library docs or README markdown from GitHub/web to verify API signatures."
    override val riskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(
        argumentsJson: String,
        fileSystem: FileSystemProvider,
        processRunner: ProcessRunner
    ): ToolResult {
        val url = try {
            val obj = Json.parseToJsonElement(argumentsJson).jsonObject
            obj["url"]?.jsonPrimitive?.content
        } catch (_: Exception) { null }
            ?: return ToolResult("fetch_doc", false, "Missing required argument: url", 0L)

        return try {
            val connection = URL(url).openConnection()
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "AgentCode/1.0")
            val body = connection.getInputStream().bufferedReader().readText()
            val truncated = if (body.length > 50_000) body.take(50_000) + "\n... (truncated)" else body
            ToolResult("fetch_doc", true, truncated, body.length.toLong())
        } catch (e: Exception) {
            ToolResult("fetch_doc", false, "Fetch failed: ${e.message}", 0L)
        }
    }
}
