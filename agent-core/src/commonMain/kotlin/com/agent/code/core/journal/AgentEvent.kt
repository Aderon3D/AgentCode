package com.agent.code.core.journal

import com.agent.code.core.fsm.ToolCall
import com.agent.code.core.path.VirtualPath
import com.agent.code.core.tools.ToolResult
import kotlinx.serialization.Serializable

@Serializable
sealed interface AgentEvent {
    val eventId: Long
    val taskId: String
    val timestampMs: Long

    @Serializable
    data class TaskStarted(
        override val eventId: Long,
        override val taskId: String,
        override val timestampMs: Long,
        val goal: String
    ) : AgentEvent

    @Serializable
    data class TokenChunkReceived(
        override val eventId: Long,
        override val taskId: String,
        override val timestampMs: Long,
        val delta: String
    ) : AgentEvent

    @Serializable
    data class ToolExecutionRequested(
        override val eventId: Long,
        override val taskId: String,
        override val timestampMs: Long,
        val toolCall: ToolCall
    ) : AgentEvent

    @Serializable
    data class ToolExecutionFinished(
        override val eventId: Long,
        override val taskId: String,
        override val timestampMs: Long,
        val result: ToolResult
    ) : AgentEvent

    @Serializable
    data class FilePatchApplied(
        override val eventId: Long,
        override val taskId: String,
        override val timestampMs: Long,
        val path: VirtualPath,
        val diff: String
    ) : AgentEvent

    @Serializable
    data class TaskSucceeded(
        override val eventId: Long,
        override val taskId: String,
        override val timestampMs: Long,
        val summary: String
    ) : AgentEvent
}
