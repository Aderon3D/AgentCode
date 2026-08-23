package com.agent.code.core.fsm

import com.agent.code.core.journal.AgentEvent
import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.LogEntry
import com.agent.code.core.journal.TelemetryEngine
import com.agent.code.core.policy.AutonomyPolicy
import com.agent.code.core.tools.ToolResult
import com.agent.code.mcp.McpHost
import kotlinx.datetime.Clock

class AgentOrchestrator(
    private val journal: AgentEventJournal,
    private val policy: AutonomyPolicy,
    private val mcp: McpHost,
    private val telemetry: TelemetryEngine
) {
    private var seq = 0L
    private fun nextId(): Long = ++seq
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    fun startTask(taskId: String, goal: String) {
        journal.append(AgentEvent.TaskStarted(nextId(), taskId, now(), goal))
        telemetry.emit(LogEntry.AgentThought(now(), "Planning: $goal"))
    }

    fun runTool(taskId: String, toolCall: ToolCall): ToolResult {
        journal.append(AgentEvent.ToolExecutionRequested(nextId(), taskId, now(), toolCall))
        val result = mcp.dispatch(toolCall)
        journal.append(AgentEvent.ToolExecutionFinished(nextId(), taskId, now(), result))
        if (result.isSuccess && toolCall.toolName == "apply_diff_patch") {
            journal.append(AgentEvent.FilePatchApplied(nextId(), taskId, now(), com.agent.code.core.path.VirtualPath.of("/src/main.kt"), toolCall.argumentsJson))
        }
        telemetry.emit(LogEntry.ToolCallStarted(now(), toolCall.toolName, toolCall.argumentsJson))
        return result
    }

    fun succeed(taskId: String, summary: String) {
        journal.append(AgentEvent.TaskSucceeded(nextId(), taskId, now(), summary))
        telemetry.emit(LogEntry.AgentThought(now(), "Success: $summary"))
    }

    fun recover(taskId: String): AgentState = journal.recoverState(taskId)
}
