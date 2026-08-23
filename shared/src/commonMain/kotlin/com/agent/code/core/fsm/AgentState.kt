package com.agent.code.core.fsm

import kotlinx.serialization.Serializable

@Serializable
data class ToolCall(val id: String, val toolName: String, val argumentsJson: String)

sealed interface AgentState {
    object Idle : AgentState
    data class Planning(val taskId: String, val thinkingProcess: String) : AgentState
    data class ExecutingTool(val taskId: String, val toolCall: ToolCall, val isStreamingOutput: Boolean) : AgentState
    data class AwaitingHumanApproval(val taskId: String, val toolCall: ToolCall, val riskLevel: com.agent.code.core.tools.RiskLevel, val justification: String) : AgentState
    data class Verifying(val taskId: String, val command: String) : AgentState
    data class Reflecting(val taskId: String, val attempt: Int, val maxAttempts: Int, val errorTrace: String) : AgentState
    data class Success(val taskId: String, val summary: String, val modifiedFiles: List<com.agent.code.core.path.VirtualPath>) : AgentState
    data class Error(val taskId: String, val fatalCause: String) : AgentState
}
