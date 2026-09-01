package com.agent.code.ui

import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.InMemoryWalStore
import com.agent.code.core.journal.TelemetryEngine
import com.agent.code.core.path.VirtualPath
import com.agent.code.mcp.McpHost
import com.agent.code.opencode.AgentBrain
import com.agent.code.opencode.BrainEvent
import com.agent.code.opencode.OpenCodeClient
import com.agent.code.opencode.OpenCodeManager
import com.agent.code.opencode.OpenCodeState
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class AgentUiState(
    val processState: OpenCodeState = OpenCodeState.NotInstalled,
    val isRunning: Boolean = false,
    val currentGoal: String = "",
    val events: List<UiEvent> = emptyList(),
    val error: String? = null
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
    private val openCodeClient: OpenCodeClient,
    private val brain: AgentBrain,
    private val journal: AgentEventJournal,
    private val telemetry: TelemetryEngine,
    private val workspaceRoot: VirtualPath,
    private val httpClient: HttpClient
) {
    private val _state = MutableStateFlow(AgentUiState())
    val state: StateFlow<AgentUiState> = _state.asStateFlow()

    private var brainJob: Job? = null
    private var statePollJob: Job? = null

    fun startOpenCode() {
        if (statePollJob != null) return
        statePollJob = scope.launch {
            while (true) {
                val s = openCodeManager.currentState()
                if (s !is OpenCodeState.Error) {
                    _state.value = _state.value.copy(processState = s)
                }
                delay(500)
            }
        }
        scope.launch {
            try {
                openCodeManager.ensureInstalled()
                openCodeManager.start(projectDir = workspaceRoot)
            } catch (e: Exception) {
                statePollJob?.cancel()
                statePollJob = null
                _state.value = _state.value.copy(
                    processState = OpenCodeState.Error(e.message ?: "start failed")
                )
            }
        }
    }

    fun stopOpenCode() {
        statePollJob?.cancel()
        statePollJob = null
        scope.launch {
            openCodeManager.stop()
            _state.value = _state.value.copy(processState = OpenCodeState.Stopped)
        }
    }

    fun executeTask(goal: String) {
        if (_state.value.isRunning) return
        brainJob?.cancel()

        _state.value = _state.value.copy(
            isRunning = true,
            currentGoal = goal,
            events = emptyList(),
            error = null
        )

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
                    _state.value = _state.value.copy(events = events.toList())

                    if (event is BrainEvent.TaskComplete || event is BrainEvent.TaskFailed) {
                        _state.value = _state.value.copy(isRunning = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isRunning = false,
                    error = "${e::class.simpleName}: ${e.message}"
                )
            }
        }
    }

    fun cancelTask() {
        brainJob?.cancel()
        brainJob = null
        _state.value = _state.value.copy(isRunning = false)
    }

    fun destroy() {
        cancelTask()
        statePollJob?.cancel()
        scope.launch { openCodeManager.stop() }
        httpClient.close()
    }

    companion object {
        fun create(
            scope: CoroutineScope,
            fileSystem: FileSystemProvider,
            processRunner: ProcessRunner,
            workspaceRoot: VirtualPath
        ): AgentViewModel {
            val manager = OpenCodeManager(fileSystem, processRunner)
            val httpClient = HttpClient()
            val client = OpenCodeClient(httpClient, manager)
            val journal = AgentEventJournal(InMemoryWalStore())
            val telemetry = TelemetryEngine(scope)
            val mcp = McpHost(fileSystem, processRunner)
            val brain = AgentBrain(client, mcp, journal, telemetry)
            return AgentViewModel(scope, manager, client, brain, journal, telemetry, workspaceRoot, httpClient)
        }
    }
}
