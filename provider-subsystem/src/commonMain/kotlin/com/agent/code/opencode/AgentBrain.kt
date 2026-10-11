package com.agent.code.opencode

import com.agent.code.core.journal.AgentEvent
import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.LogEntry
import com.agent.code.core.journal.TelemetryEngine
import com.agent.code.core.path.VirtualPath
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AgentConfig(
    val providerID: String = "opencode",
    // Tried in order; the first model that completes a run wins.
    val models: List<String> = listOf("mimo-v2.6-flash-free", "space-bunny-free")
)

sealed interface BrainEvent {
    data class Thinking(val text: String) : BrainEvent
    data class ToolCallStarted(val toolName: String, val args: String) : BrainEvent
    data class ToolCallFinished(val toolName: String, val success: Boolean) : BrainEvent
    data class IterationComplete(val iteration: Int) : BrainEvent
    data class TaskComplete(val summary: String) : BrainEvent
    data class TaskFailed(val reason: String) : BrainEvent
}

class AgentBrain(
    private val client: OpenCodeClient,
    private val journal: AgentEventJournal,
    private val telemetry: TelemetryEngine,
    private val config: AgentConfig = AgentConfig()
) {
    private var seq = 0L
    private val seqMutex = Mutex()
    private suspend fun nextId(): Long = seqMutex.withLock { ++seq }
    private fun now(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

fun executeTask(
        taskId: String,
        goal: String,
        workspaceRoot: VirtualPath
    ) = channelFlow {
        val startId = nextId()
        journal.append(AgentEvent.TaskStarted(startId, taskId, now(), goal))
        telemetry.emit(LogEntry.AgentThought(now(), "Starting: $goal"))

        // opencode runs its own agent loop (tools + retries) behind the
        // session API, so we drive exactly one prompt per model and stream
        // what it does. First model to finish wins; a failed model falls
        // through to the next.
        var lastError = "no response"
        for (model in config.models) {
            try {
                val sessionId = client.createSession(config.providerID, model)
                send(BrainEvent.Thinking("session $sessionId · model $model"))
                client.sendPrompt(sessionId, "$goal\n\n(workspace: ${workspaceRoot.rawPath})")

                val transcript = StringBuilder()
                var currentTool = ""
                var steps = 0
                var failure: String? = null
                var finished = false

                client.sessionEvents(sessionId)
                    // stop at the first failure or at the final step; a
                    // "tool-calls" finish means the agent is still working.
                    .map { ev ->
                        when {
                            ev is SessionEvent.Failed -> {
                                failure = ev.message; null
                            }
                            ev is SessionEvent.StepEnded && ev.finish != "tool-calls" -> {
                                finished = true; null
                            }
                            else -> ev
                        }
                    }
                    .takeWhile { it != null }
                    .collect { ev ->
                        when (ev) {
                            is SessionEvent.StepStarted -> {
                                steps++
                                send(BrainEvent.Thinking("step ${steps} · ${ev.model}"))
                            }
                            is SessionEvent.ToolCalled -> {
                                currentTool = ev.tool
                                send(BrainEvent.ToolCallStarted(ev.tool, ""))
                            }
                            is SessionEvent.ToolInput ->
                                if (ev.detail.isNotBlank()) send(BrainEvent.Thinking("run: ${ev.detail.take(200)}"))
                            is SessionEvent.ToolFinished ->
                                send(BrainEvent.ToolCallFinished(currentTool, ev.ok))
                            is SessionEvent.TextEnded -> {
                                val t = ev.text.trim()
                                if (t.isNotEmpty()) {
                                    transcript.append(t).append("\n\n")
                                    send(BrainEvent.Thinking(t))
                                }
                            }
                            is SessionEvent.StepEnded -> send(BrainEvent.IterationComplete(steps))
                            null, is SessionEvent.TextDelta, is SessionEvent.PromptAdmitted,
                            is SessionEvent.Failed -> {}
                        }
                    }

                if (finished && failure == null) {
                    val summary = transcript.toString().trim().ifBlank { "done (no text output)" }
                    send(BrainEvent.TaskComplete(summary))
                    val completeId = nextId()
                    journal.append(AgentEvent.TaskSucceeded(completeId, taskId, now(), summary))
                    return@channelFlow
                }
                lastError = failure ?: "stream ended before a final step"
            } catch (e: Exception) {
                lastError = "${e::class.simpleName}: ${e.message}"
            }
            telemetry.emit(LogEntry.AgentThought(now(), "model $model failed: $lastError"))
        }

        val failId = nextId()
        journal.append(AgentEvent.TaskSucceeded(failId, taskId, now(), lastError))
        send(BrainEvent.TaskFailed(lastError))
    }
}
