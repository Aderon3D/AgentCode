package com.agent.code.opencode

import com.agent.code.core.journal.AgentEvent
import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.LogEntry
import com.agent.code.core.journal.TelemetryEngine
import com.agent.code.core.path.VirtualPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.channelFlow
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
    private val manager: OpenCodeManager,
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

        // r24 pivot: drive `opencode run --format json` (one-shot subprocess,
        // events on stdout) instead of the serve+SSE session API. opencode runs
        // its own agent loop (tools + retries) behind `run`, so we drive exactly
        // one prompt per model and stream what it does. First model to finish
        // wins; a failed model falls through to the next.
        var lastError = "no response"
        for (model in config.models) {
            try {
                // Stage markers: each line appears in the Agent feed the moment
                // that hop is entered, so a stall names its own stage without a
                // log dump (empty feed after "goal:" = run never spawned; after
                // "run <model>" = the subprocess is starting up).
                send(BrainEvent.Thinking("run $model ..."))
                val transcript = StringBuilder()
                var currentTool = ""
                var steps = 0
                var failure: String? = null
                var finished = false

                // runPrompt spawns the subprocess and yields raw JSON lines;
                // parseRunEvent maps each to SessionEvent(s). A completed tool
                // yields ToolCalled+ToolFinished in one line; a "tool-calls"
                // step_finish means the agent is still working.
                manager.runPrompt(
                    taskId = taskId,
                    providerID = config.providerID,
                    model = model,
                    prompt = "$goal\n\n(workspace: ${workspaceRoot.rawPath})",
                    workspace = workspaceRoot
                ).collect { line ->
                    for (ev in parseRunEvent(line)) {
                        when (ev) {
                            is SessionEvent.StepStarted -> {
                                steps++
                                send(BrainEvent.Thinking("step $steps · $model"))
                            }
                            is SessionEvent.ToolCalled -> {
                                currentTool = ev.tool
                                send(BrainEvent.ToolCallStarted(ev.tool, ""))
                            }
                            is SessionEvent.ToolFinished ->
                                send(BrainEvent.ToolCallFinished(currentTool, ev.ok))
                            is SessionEvent.ToolInput -> {
                                // Surface the tool args (the feed's whole point:
                                // what the agent ran when a run stalls).
                                if (ev.detail.isNotBlank()) {
                                    send(BrainEvent.Thinking("run: ${ev.detail.take(200)}"))
                                }
                            }
                            is SessionEvent.TextEnded -> {
                                val t = ev.text.trim()
                                if (t.isNotEmpty()) {
                                    transcript.append(t).append("\n\n")
                                    send(BrainEvent.Thinking(t))
                                }
                            }
                            is SessionEvent.StepEnded -> {
                                when (ev.finish) {
                                    // still working
                                    "tool-calls" -> {}
                                    // only "stop" is a successful terminal step
                                    "stop" -> finished = true
                                    // "length"/truncated, "content-filter",
                                    // "error": NOT a completed run (else a
                                    // truncated step records a false success
                                    // and the model loop stops on a bad model).
                                    else -> failure = "run ended: ${ev.finish}"
                                }
                                send(BrainEvent.IterationComplete(steps))
                            }
                            is SessionEvent.Failed -> failure = ev.message
                            // run mode auto-approves (--auto) and has no SSE, so
                            // permission/question events never arrive; stream
                            // info / deltas / admits are not emitted by `run`.
                            else -> {}
                        }
                    }
                }

                if (finished && failure == null) {
                    val summary = transcript.toString().trim().ifBlank { "done (no text output)" }
                    send(BrainEvent.TaskComplete(summary))
                    val completeId = nextId()
                    journal.append(AgentEvent.TaskSucceeded(completeId, taskId, now(), summary))
                    return@channelFlow
                }
                lastError = failure ?: "run ended before a final step"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = "${e::class.simpleName}: ${e.message}"
            }
            telemetry.emit(LogEntry.AgentThought(now(), "model $model failed: $lastError"))
        }

        val failId = nextId()
        // TaskFailed, not TaskSucceeded: a failure is not a success, and a
        // journal reader scanning for failures would never see this line.
        journal.append(AgentEvent.TaskFailed(failId, taskId, now(), lastError))
        send(BrainEvent.TaskFailed(lastError))
    }
}
