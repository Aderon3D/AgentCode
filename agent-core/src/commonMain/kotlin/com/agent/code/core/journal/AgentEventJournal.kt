package com.agent.code.core.journal

import com.agent.code.core.fsm.AgentState
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.serializer

val eventJson = Json {
    ignoreUnknownKeys = true
    serializersModule = SerializersModule {
        polymorphic(AgentEvent::class) {
            subclass(AgentEvent.TaskStarted::class, AgentEvent.TaskStarted.serializer())
            subclass(AgentEvent.ToolExecutionRequested::class, AgentEvent.ToolExecutionRequested.serializer())
            subclass(AgentEvent.ToolExecutionFinished::class, AgentEvent.ToolExecutionFinished.serializer())
            subclass(AgentEvent.FilePatchApplied::class, AgentEvent.FilePatchApplied.serializer())
            subclass(AgentEvent.TaskSucceeded::class, AgentEvent.TaskSucceeded.serializer())
        }
    }
}

object FsmStateReconstructor {
    fun replay(events: List<AgentEvent>): AgentState {
        if (events.isEmpty()) return AgentState.Idle

        var lastTaskId = events.last().taskId
        var successSummary: String? = null
        var errorTrace: String? = null
        var lastResult: com.agent.code.core.tools.ToolResult? = null
        val patchedFiles = mutableListOf<com.agent.code.core.path.VirtualPath>()
        // ponytail: outstanding call id -> call; cleared when its finish event lands
        val outstanding = linkedMapOf<String, com.agent.code.core.fsm.ToolCall>()

        for (e in events) {
            when (e) {
                is AgentEvent.TaskStarted -> lastTaskId = e.taskId
                is AgentEvent.ToolExecutionRequested -> outstanding[e.toolCall.id] = e.toolCall
                is AgentEvent.ToolExecutionFinished -> {
                    lastResult = e.result
                    outstanding.remove(e.result.toolCallId)
                }
                is AgentEvent.FilePatchApplied -> patchedFiles.add(e.path)
                is AgentEvent.TaskSucceeded -> successSummary = e.summary
            }
        }

        return when {
            successSummary != null ->
                AgentState.Success(lastTaskId, successSummary, patchedFiles)
            lastResult != null && !lastResult.isSuccess ->
                AgentState.Error(lastTaskId, errorTrace ?: lastResult.output)
            patchedFiles.isNotEmpty() ->
                AgentState.Verifying(lastTaskId, "build/lint")
            outstanding.isNotEmpty() ->
                AgentState.ExecutingTool(lastTaskId, outstanding.values.last(), isStreamingOutput = false)
            else -> AgentState.Planning(lastTaskId, "reconstructed")
        }
    }
}

class AgentEventJournal(private val store: WalStore) {
    private val json = eventJson

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
