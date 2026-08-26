package com.agent.code.ui

import com.agent.code.core.tools.RiskLevel
import com.agent.code.core.tools.ToolResult
import com.agent.code.mcp.AgentTool
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class InspectUiTool(
    private val accessibilityEngine: AccessibilityEngine
) : AgentTool {
    override val name = "inspect_ui_state"
    override val description = "Returns a text-based XML layout tree of the running app screen."
    override val riskLevel = RiskLevel.READ_ONLY

    override suspend fun execute(
        argumentsJson: String,
        fileSystem: FileSystemProvider,
        processRunner: ProcessRunner
    ): ToolResult {
        val xml = accessibilityEngine.dumpSemanticTreeXml()
        return ToolResult(name, true, xml, 0L)
    }
}

class InteractUiTool(
    private val accessibilityEngine: AccessibilityEngine
) : AgentTool {
    override val name = "interact_ui_element"
    override val description = "Performs clicks, text typing, or gestures on UI elements via resource id or text."
    override val riskLevel = RiskLevel.WRITE

    override suspend fun execute(
        argumentsJson: String,
        fileSystem: FileSystemProvider,
        processRunner: ProcessRunner
    ): ToolResult {
        val obj = try {
            Json.parseToJsonElement(argumentsJson).jsonObject
        } catch (_: Exception) {
            return ToolResult(name, false, "invalid arguments json", 0L)
        }
        val action = obj["action"]?.jsonPrimitive?.contentOrNull ?: "click"
        val resourceId = obj["resourceId"]?.jsonPrimitive?.contentOrNull
        val text = obj["text"]?.jsonPrimitive?.contentOrNull
        val selector = UiElementSelector(resourceId, text, null)

        val result = when (action) {
            "type" -> {
                val input = text ?: return ToolResult(name, false, "type action requires text", 0L)
                accessibilityEngine.performInputText(selector, input)
            }
            "swipe" -> {
                val x1 = obj["x1"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                val y1 = obj["y1"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                val x2 = obj["x2"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                val y2 = obj["y2"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
                val duration = obj["durationMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                accessibilityEngine.performSwipe(x1, y1, x2, y2, duration)
            }
            else -> accessibilityEngine.performClick(selector)
        }
        return result.fold(
            onSuccess = { ToolResult(name, true, "action '$action' dispatched", 0L) },
            onFailure = { ToolResult(name, false, it.message ?: "interaction failed", 0L) }
        )
    }
}
