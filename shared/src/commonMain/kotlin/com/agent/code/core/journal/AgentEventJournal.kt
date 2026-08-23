package com.agent.code.core.journal

import com.agent.code.core.fsm.AgentState
import kotlinx.serialization.json.Json

object FsmStateReconstructor {
    fun replay(events: List<AgentEvent>): AgentState {
        if (events.isEmpty()) return AgentState.Idle

        var lastTaskId = events.last().taskId
        var successSummary: String? = null
        var errorTrace: String? = null
        var lastToolCall: com.agent.code.core.fsm.ToolCall? = null
        var lastResult: com.agent.code.core.tools.ToolResult? = null
        var patchedFile: com.agent.code.core.path.VirtualPath? = null

        for (e in events) {
            when (e) {
                is AgentEvent.TaskStarted -> lastTaskId = e.taskId
                is AgentEvent.ToolExecutionRequested -> lastToolCall = e.toolCall
                is AgentEvent.ToolExecutionFinished -> lastResult = e.result
                is AgentEvent.FilePatchApplied -> patchedFile = e.path
                is AgentEvent.TaskSucceeded -> successSummary = e.summary
            }
        }

        return when {
            successSummary != null ->
                AgentState.Success(lastTaskId, successSummary, listOfNotNull(patchedFile))
            lastResult != null && !lastResult.isSuccess ->
                AgentState.Error(lastTaskId, errorTrace ?: lastResult.output)
            patchedFile != null ->
                AgentState.Verifying(lastTaskId, "build/lint")
            lastToolCall != null ->
                AgentState.ExecutingTool(lastTaskId, lastToolCall, isStreamingOutput = false)
            else -> AgentState.Planning(lastTaskId, "reconstructed")
        }
    }
}

class AgentEventJournal(private val store: WalStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun append(event: AgentEvent) {
        store.append(json.encodeToString(event))
    }

    fun recoverState(taskId: String): AgentState {
        val events = store.replay().mapNotNull { runCatching { json.decodeFromString<AgentEvent>(it) }.getOrNull() }
        return FsmStateReconstructor.replay(events.filter { it.taskId == taskId })
    }

    fun allEvents(): List<AgentEvent> =
        store.replay().mapNotNull { runCatching { json.decodeFromString<AgentEvent>(it) }.getOrNull() }
}
