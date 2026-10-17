package com.agent.code.ui

import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.InMemoryWalStore
import com.agent.code.core.journal.TelemetryEngine
import com.agent.code.core.path.VirtualPath
import com.agent.code.opencode.AgentBrain
import com.agent.code.opencode.BrainEvent
import com.agent.code.opencode.OpenCodeApi
import com.agent.code.opencode.OpenCodeClient
import com.agent.code.opencode.OpenCodeConfig
import com.agent.code.opencode.OpenCodeManager
import com.agent.code.opencode.OpenCodeState
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AgentUiState(
    val processState: OpenCodeState = OpenCodeState.NotInstalled,
    val isRunning: Boolean = false,
    val currentGoal: String = "",
    val events: List<UiEvent> = emptyList(),
    val error: String? = null,
    val logDump: String? = null
)

sealed interface UiEvent {
    data class Thinking(val text: String) : UiEvent
    data class ToolStarted(val name: String, val args: String) : UiEvent
    data class ToolFinished(val name: String, val success: Boolean) : UiEvent
    data class Iteration(val number: Int) : UiEvent
    data class Complete(val summary: String) : UiEvent
    data class Failed(val reason: String) : UiEvent
}

class AgentViewModel(
    private val scope: CoroutineScope,
    private val openCodeManager: OpenCodeManager,
    private val openCodeClient: OpenCodeApi,
    private val brain: AgentBrain,
    private val journal: AgentEventJournal,
    private val telemetry: TelemetryEngine,
    private val workspaceRoot: VirtualPath,
    private val httpClient: HttpClient
) {
    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var brainJob: Job? = null
    @Volatile
    private var statePollJob: Job? = null

    // destroy() runs as the composition scope is being torn down, so stop()
    // must not be launched on it — the launch would be cancelled before it ran
    // and the native child would leak. This scope outlives composition for the
    // one final teardown call.
    private val teardownScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun startOpenCode() {
        // Cancel-and-relaunch (was: early-return if alive). A live poll can be
        // a stale watcher for a previous child, and after the poll survives
        // past Stopped we must be able to replace it on the next Start.
        statePollJob?.cancel()
        // Dispatchers.IO: currentState() -> nativeCheck does /proc + log file
        // I/O every heartbeat; keep it off the Main dispatcher.
        val poll = scope.launch(Dispatchers.IO) {
            while (true) {
                val s = openCodeManager.currentState()
                // update{} not copy: the 500ms poll races the brain's per-event
                // writes (read-modify-write lost events from the feed).
                _state.update { it.copy(processState = s) }
                // Error is the only terminal state for this poll. Running must
                // KEEP polling (currentState() checks child liveness there —
                // cancelling on Running froze the UI at "Running" after the
                // loader killed the child, r14), and Stopped is transient
                // during startup (ensureInstalled sets it before start()).
                if (s is OpenCodeState.Error) break
                delay(500)
            }
        }
        statePollJob = poll
        // Clear the slot only if it still points at THIS poll — a newer
        // startOpenCode() may have already installed its own watcher.
        poll.invokeOnCompletion { if (statePollJob === poll) statePollJob = null }
        scope.launch {
            try {
                openCodeManager.ensureInstalled()
                openCodeManager.start(projectDir = workspaceRoot)
            } catch (e: CancellationException) {
                // Teardown cancelled start(); propagate instead of turning it
                // into a spurious Error on the way out.
                throw e
            } catch (e: Exception) {
                // Never cancel a newer start's watcher.
                if (statePollJob === poll) {
                    poll.cancel()
                    statePollJob = null
                }
                _state.update {
                    it.copy(processState = OpenCodeState.Error(e.message ?: "start failed"))
                }
            }
        }
    }

    // Pull the loader/server log on demand, healthy or not. File IO off the
    // UI thread; the UI copies the result to the clipboard.
    fun fetchLog() {
        scope.launch {
            val dump = withContext(Dispatchers.IO) { openCodeManager.logDump() }
            val s = _state.value
            // The feed lives only in Compose state — fold its tail into the
            // dump so one Copy always contains the pipeline stage (where the
            // prompt stalled) plus loader/server logs.
            val feed = s.events.takeLast(15).joinToString("\n") { e ->
                val (marker, text) = when (e) {
                    is UiEvent.Thinking -> "*" to e.text
                    is UiEvent.ToolStarted -> ">" to "${e.name}(${e.args.take(80)})"
                    is UiEvent.ToolFinished -> "<" to "${e.name} ${if (e.success) "ok" else "err"}"
                    is UiEvent.Iteration -> "*" to "iteration ${e.number} complete"
                    is UiEvent.Complete -> "+" to "done: ${e.summary}"
                    is UiEvent.Failed -> "!" to "failed: ${e.reason}"
                }
                "$marker $text"
            }
            _state.update {
                it.copy(
                    logDump = "app=${OpenCodeClient.APP_BUILD} goal=${s.currentGoal} taskRunning=${s.isRunning} uiError=${s.error}\n" +
                        "--- agent feed (last ${s.events.size.coerceAtMost(15)}) ---\n" +
                        (if (feed.isEmpty()) "(empty)" else feed) +
                        "\n--- loader + server ---\n$dump"
                )
            }
        }
    }

    fun clearLogDump() {
        _state.update { it.copy(logDump = null) }
    }

    fun stopOpenCode() {
        statePollJob?.cancel()
        statePollJob = null
        scope.launch {
            openCodeManager.stop()
            _state.update { it.copy(processState = OpenCodeState.Stopped) }
        }
    }

    fun executeTask(goal: String) {
        if (_state.value.isRunning) return
        brainJob?.cancel()

        _state.update {
            it.copy(
                isRunning = true,
                currentGoal = goal,
                // First visible mark: if the feed stays empty AFTER this line the
                // break is downstream (session/prompt/SSE), before it = submit never ran.
                events = listOf(UiEvent.Thinking("→ goal: $goal")),
                error = null
            )
        }

        brainJob = scope.launch {
            try {
                val taskId = "T-${System.currentTimeMillis()}"
                val events = mutableListOf<UiEvent>()

                brain.executeTask(taskId, goal, workspaceRoot).collect { event ->
                    val uiEvent = when (event) {
                        is BrainEvent.Thinking -> UiEvent.Thinking(event.text)
                        is BrainEvent.ToolCallStarted -> UiEvent.ToolStarted(event.toolName, event.args)
                        is BrainEvent.ToolCallFinished -> UiEvent.ToolFinished(event.toolName, event.success)
                        is BrainEvent.IterationComplete -> UiEvent.Iteration(event.iteration)
                        is BrainEvent.TaskComplete -> UiEvent.Complete(event.summary)
                        is BrainEvent.TaskFailed -> UiEvent.Failed(event.reason)
                    }
                    events.add(uiEvent)
                    val snapshot = events.toList()
                    _state.update { it.copy(events = snapshot) }

                    if (event is BrainEvent.TaskComplete || event is BrainEvent.TaskFailed) {
                        _state.update { it.copy(isRunning = false) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isRunning = false,
                        error = "${e::class.simpleName}: ${e.message}"
                    )
                }
            }
        }
    }

    fun cancelTask() {
        brainJob?.cancel()
        brainJob = null
        _state.update { it.copy(isRunning = false) }
    }

    fun destroy() {
        cancelTask()
        statePollJob?.cancel()
        statePollJob = null
        // Cancel the scope once stop() returns: it exists only to outlive
        // composition for this one teardown, not to leak a live coroutine.
        teardownScope.launch { try { openCodeManager.stop() } finally { teardownScope.cancel() } }
        httpClient.close()
    }

    companion object {
        fun create(
            scope: CoroutineScope,
            fileSystem: FileSystemProvider,
            processRunner: ProcessRunner,
            workspaceRoot: VirtualPath,
            nativeLibraryDir: String = "/data/app/~~placeholder~~/com.agent.code/lib/arm64"
        ): AgentViewModel {
            val config = OpenCodeConfig(nativeLibraryDir = nativeLibraryDir)
            val manager = OpenCodeManager(fileSystem, processRunner, config)
            // No timeout = silent zombie: createSession can hang forever with
            // an empty feed (Stop highlighted, nothing else, r15). SSE
            // overrides to infinite per-request in OpenCodeClient.
            val httpClient = HttpClient {
                install(HttpTimeout) {
                    requestTimeoutMillis = 30_000
                    connectTimeoutMillis = 5_000
                    socketTimeoutMillis = 30_000
                }
            }
            val client = OpenCodeClient(httpClient, manager)
            val journal = AgentEventJournal(InMemoryWalStore())
            val telemetry = TelemetryEngine(scope)
            val brain = AgentBrain(manager, journal, telemetry)
            return AgentViewModel(scope, manager, client, brain, journal, telemetry, workspaceRoot, httpClient)
        }
    }
}
