package com.agent.code.opencode

import com.agent.code.core.conversation.ConversationStore
import com.agent.code.core.conversation.Turn
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
    val models: List<String> = listOf("mimo-v2.6-flash-free", "deepseek-v4.1-flash-free")
)

sealed interface BrainEvent {
    data class Thinking(val text: String) : BrainEvent
    /** The assistant's actual reply text (markdown), streamed per step. */
    data class Answer(val text: String) : BrainEvent
    data class Reasoning(val text: String) : BrainEvent
    data class ToolCallStarted(val toolName: String, val args: String) : BrainEvent
    data class ToolCallFinished(val toolName: String, val success: Boolean, val output: String = "") : BrainEvent
    data class IterationComplete(val iteration: Int) : BrainEvent
    data class TaskComplete(val summary: String) : BrainEvent
    data class TaskFailed(val reason: String) : BrainEvent
}

/** One stored turn replayed into UI events, ready for the transcript. */
data class RestoredTurn(val goal: String, val events: List<BrainEvent>)

class AgentBrain(
    private val manager: OpenCodeManager,
    private val journal: AgentEventJournal,
    private val telemetry: TelemetryEngine,
    private val config: AgentConfig = AgentConfig(),
    private val store: ConversationStore? = null
) {
    private var seq = 0L
    private val seqMutex = Mutex()
    private suspend fun nextId(): Long = seqMutex.withLock { ++seq }
    private fun now(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

    // Conversation continuity: every run event carries its opencode session id,
    // and `run -s <id>` continues that conversation instead of starting empty.
    // Committed only after a successful run, so a failed model retry or a
    // cancelled task never leaves the next prompt duplicated inside a
    // half-finished session. Persisted through [store] when one is wired;
    // [restore] reloads it on app start.
    private var sessionId: String? = null

    /**
     * Reload the conversation saved by [store]. Also adopts its session id so
     * the next prompt continues the same opencode session.
     */
    suspend fun restore(): List<RestoredTurn> {
        val saved = store?.load() ?: return emptyList()
        // Never clobber a session this process just started (restore races the
        // first prompt after a cold start).
        if (sessionId == null) sessionId = saved.sessionId
        return saved.turns.map { turn ->
            RestoredTurn(goal = turn.goal, events = replayRun(turn.lines, config.models.first()))
        }
    }

    /** Drop the conversation: the next task starts a new opencode session. */
    suspend fun newSession() {
        sessionId = null
        store?.clear()
    }

@Suppress("TooGenericExceptionCaught") // boundary: any runPrompt failure falls through to the next model
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
                val run = RunAccumulator()
                var runSessionId: String? = null
                // Kept raw so a restart can replay them through the same parser.
                val rawLines = mutableListOf<String>()

                // runPrompt spawns the subprocess and yields raw JSON lines;
                // parseRunEvent maps each to SessionEvent(s). A completed tool
                // yields ToolCalled+ToolFinished in one line; a "tool-calls"
                // step_finish means the agent is still working.
                manager.runPrompt(
                    taskId = taskId,
                    providerID = config.providerID,
                    model = model,
                    prompt = "$goal\n\n(workspace: ${workspaceRoot.rawPath})",
                    workspace = workspaceRoot,
                    sessionId = sessionId
                ).collect { line ->
                    rawLines.add(line)
                    if (runSessionId == null) runSessionId = sessionIdOf(line)
                    for (ev in parseRunEvent(line)) {
                        run.onEvent(ev, model)?.let { send(it) }
                    }
                }

                if (run.finished && run.failure == null) {
                    runSessionId?.let { sessionId = it }
                    val summary = run.transcript.toString().trim().ifBlank { "done (no text output)" }
                    store?.append(Turn(goal, rawLines), sessionId)
                    send(BrainEvent.TaskComplete(summary))
                    val completeId = nextId()
                    journal.append(AgentEvent.TaskSucceeded(completeId, taskId, now(), summary))
                    return@channelFlow
                }
                lastError = run.failure ?: "run ended before a final step"
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

// Replay stored run lines through the live event path (one accumulator per
// turn, shared across lines), so a restored transcript is exactly what the live
// run produced — same parser, same accumulator, no second model of history.
internal fun replayRun(lines: List<String>, model: String): List<BrainEvent> {
    val run = RunAccumulator()
    return lines.flatMap { line -> parseRunEvent(line).mapNotNull { run.onEvent(it, model) } }
}

// Mutable per-model run state for one `run` invocation. Maps each streamed
// SessionEvent to the BrainEvent executeTask should emit (or null for events
// with no visible effect), keeping the streaming loop small.
internal class RunAccumulator {
    val transcript = StringBuilder()
    var currentTool = ""
    var steps = 0
    var failure: String? = null
    var finished = false

    fun onEvent(ev: SessionEvent, model: String): BrainEvent? = when (ev) {
        is SessionEvent.StepStarted -> {
            steps++
            BrainEvent.Thinking("step $steps · $model")
        }
        is SessionEvent.ToolCalled -> {
            currentTool = ev.tool
            BrainEvent.ToolCallStarted(ev.tool, ev.input)
        }
        is SessionEvent.ToolFinished -> BrainEvent.ToolCallFinished(currentTool, ev.ok, ev.output)
        is SessionEvent.Reasoning -> BrainEvent.Reasoning(ev.text)
        // Legacy SSE shape: args already arrive on ToolCalled in run mode.
        is SessionEvent.ToolInput -> null
        is SessionEvent.TextEnded -> {
            val t = ev.text.trim()
            if (t.isEmpty()) {
                null
            } else {
                transcript.append(t).append("\n\n")
                BrainEvent.Answer(t)
            }
        }
        is SessionEvent.StepEnded -> {
            when (ev.finish) {
                // still working
                "tool-calls" -> {}
                // only "stop" is a successful terminal step
                "stop" -> finished = true
                // "length"/truncated, "content-filter", "error": NOT a
                // completed run (else a truncated step records a false success
                // and the model loop stops on a bad model).
                else -> failure = "run ended: ${ev.finish}"
            }
            BrainEvent.IterationComplete(steps)
        }
        is SessionEvent.Failed -> {
            failure = ev.message
            null
        }
        // run mode auto-approves (--auto) and has no SSE, so permission/question
        // events never arrive; stream info / deltas / admits are not emitted by
        // `run`.
        else -> null
    }
}

// opencode stamps every run JSON event with "sessionID"; the first one on the
// stream names the session this run used (a fresh one unless -s was passed).
// Cheap regex instead of a second JSON parse: each event is parsed for its own
// shape right after, and loader forensics lines ("elfloader: ...") simply miss.
private val SESSION_ID_RE = Regex("\"sessionID\"\\s*:\\s*\"([^\"]+)\"")

internal fun sessionIdOf(line: String): String? =
    SESSION_ID_RE.find(line)?.groupValues?.get(1)
