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
            subclass(AgentEvent.TokenChunkReceived::class, AgentEvent.TokenChunkReceived.serializer())
            subclass(AgentEvent.ToolExecutionRequested::class, AgentEvent.ToolExecutionRequested.serializer())
            subclass(AgentEvent.ToolExecutionFinished::class, AgentEvent.ToolExecutionFinished.serializer())
            subclass(AgentEvent.FilePatchApplied::class, AgentEvent.FilePatchApplied.serializer())
            subclass(AgentEvent.TaskSucceeded::class, AgentEvent.TaskSucceeded.serializer())
            subclass(AgentEvent.TaskFailed::class, AgentEvent.TaskFailed.serializer())
        }
    }
}

object FsmStateReconstructor {
    fun replay(events: List<AgentEvent>): AgentState {
        if (events.isEmpty()) return AgentState.Idle
        val acc = ReplayAccumulator()
        // Fallback taskId for a log that never journals TaskStarted.
        acc.lastTaskId = events.last().taskId
        for (e in events) acc.apply(e)
        return acc.state()
    }
}

private class ReplayAccumulator {
    var lastTaskId: String = ""
    var successSummary: String? = null
    var errorTrace: String? = null
    var lastResult: com.agent.code.core.tools.ToolResult? = null
    val patchedFiles = mutableListOf<com.agent.code.core.path.VirtualPath>()

    // ponytail: outstanding call id -> call; cleared when its finish event lands
    val outstanding = linkedMapOf<String, com.agent.code.core.fsm.ToolCall>()

    fun apply(e: AgentEvent) {
        when (e) {
            is AgentEvent.TaskStarted -> lastTaskId = e.taskId
            is AgentEvent.TokenChunkReceived -> { /* accumulate for Planning reconstruction */ }
            is AgentEvent.ToolExecutionRequested -> outstanding[e.toolCall.id] = e.toolCall
            is AgentEvent.ToolExecutionFinished -> {
                lastResult = e.result
                outstanding.remove(e.result.toolCallId)
            }
            is AgentEvent.FilePatchApplied -> patchedFiles.add(e.path)
            is AgentEvent.TaskSucceeded -> successSummary = e.summary
            is AgentEvent.TaskFailed -> errorTrace = e.reason
        }
    }

    fun state(): AgentState {
        val summary = successSummary
        val error = errorTrace
        val result = lastResult
        return when {
            summary != null ->
                AgentState.Success(lastTaskId, summary, patchedFiles)
            // A run that only journals TaskStarted + TaskFailed (no tool
            // results) must replay as Error, not fall through to Planning.
            error != null ->
                AgentState.Error(lastTaskId, error)
            result != null && !result.isSuccess ->
                AgentState.Error(lastTaskId, error ?: result.output)
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
        runCatching { store.append(json.encodeToString(event)) }
            .onFailure { println("WAL: failed to append event ${event::class.simpleName}: ${it.message}") }
    }

    fun recoverState(taskId: String): AgentState {
        val events = store.replay().mapNotNull {
            runCatching { json.decodeFromString<AgentEvent>(it) }
                .onFailure { println("WAL: skipping corrupt entry: ${it.message}") }
                .getOrNull()
        }
        return FsmStateReconstructor.replay(events.filter { it.taskId == taskId })
    }

    fun allEvents(): List<AgentEvent> =
        store.replay().mapNotNull {
            runCatching { json.decodeFromString<AgentEvent>(it) }
                .onFailure { println("WAL: skipping corrupt entry: ${it.message}") }
                .getOrNull()
        }
}
