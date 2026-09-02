package com.agent.code.opencode

import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.InMemoryWalStore
import com.agent.code.core.journal.TelemetryEngine
import com.agent.code.core.path.VirtualPath
import com.agent.code.mcp.McpHost
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class AgentSlot(
    val id: String,
    val goal: String,
    val state: AgentSlotState,
    val port: Int? = null
)

sealed interface AgentSlotState {
    data object Idle : AgentSlotState
    data object Starting : AgentSlotState
    data class Running(val iteration: Int) : AgentSlotState
    data class Done(val summary: String) : AgentSlotState
    data class Failed(val reason: String) : AgentSlotState
}

class MultiAgentOrchestrator(
    private val fileSystem: FileSystemProvider,
    private val processRunner: ProcessRunner,
    private val workspaceRoot: VirtualPath,
    private val maxConcurrent: Int = 3
) {
    private val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
    private val mutex = Mutex()
    private val _slots = MutableStateFlow<Map<String, AgentSlot>>(emptyMap())
    val slots: StateFlow<Map<String, AgentSlot>> = _slots.asStateFlow()

    private val managers = mutableMapOf<String, OpenCodeManager>()
    private val jobs = mutableMapOf<String, Job>()

    suspend fun submitTask(taskId: String, goal: String): AgentSlot = mutex.withLock {
        if (managers.size >= maxConcurrent) {
            throw IllegalStateException("Max concurrent agents ($maxConcurrent) reached")
        }
        val port = (4096..65535).first { p -> managers.values.none { it.currentState().let { s -> s is OpenCodeState.Running && s.port == p } } }
        val config = OpenCodeConfig(defaultPort = port)
        val manager = OpenCodeManager(fileSystem, processRunner, config)
        managers[taskId] = manager

        val slot = AgentSlot(taskId, goal, AgentSlotState.Idle, port)
        _slots.value = _slots.value + (taskId to slot)
        slot
    }

    suspend fun startTask(taskId: String) {
        val manager = mutex.withLock { managers[taskId] }
            ?: throw IllegalStateException("No slot for task $taskId")

        mutex.withLock {
            val slot = _slots.value[taskId] ?: return@withLock
            _slots.value = _slots.value + (taskId to slot.copy(state = AgentSlotState.Starting))
        }

        val job = scope.launch {
            try {
                manager.ensureInstalled()
                manager.start(projectDir = workspaceRoot)

                val httpClient = io.ktor.client.HttpClient()
                try {
                    val client = OpenCodeClient(httpClient, manager)
                    val journal = AgentEventJournal(InMemoryWalStore())
                    val telemetry = TelemetryEngine(scope)
                    val mcp = McpHost(fileSystem, processRunner)
                    val brain = AgentBrain(client, mcp, journal, telemetry)

                    mutex.withLock {
                        val slot = _slots.value[taskId] ?: return@withLock
                        val port = manager.currentState().let { if (it is OpenCodeState.Running) it.port else null }
                        _slots.value = _slots.value + (taskId to slot.copy(state = AgentSlotState.Running(0), port = port))
                    }

                    val goal = mutex.withLock { _slots.value[taskId]?.goal } ?: return@launch
                    brain.executeTask(taskId, goal, workspaceRoot).collect { event ->
                        mutex.withLock {
                            val current = _slots.value[taskId] ?: return@withLock
                            val updated = when (event) {
                                is BrainEvent.IterationComplete -> current.copy(state = AgentSlotState.Running(event.iteration))
                                is BrainEvent.TaskComplete -> current.copy(state = AgentSlotState.Done(event.summary))
                                is BrainEvent.TaskFailed -> current.copy(state = AgentSlotState.Failed(event.reason))
                                else -> null
                            }
                            if (updated != null) {
                                _slots.value = _slots.value + (taskId to updated)
                            }
                        }
                    }
                } finally {
                    try { httpClient.close() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                mutex.withLock {
                    val current = _slots.value[taskId] ?: return@withLock
                    _slots.value = _slots.value + (taskId to current.copy(
                        state = AgentSlotState.Failed(e.message ?: "unknown error")
                    ))
                }
            }
        }
        mutex.withLock { jobs[taskId] = job }
    }

    suspend fun cancelTask(taskId: String) {
        val job: Job?
        val manager: OpenCodeManager?
        mutex.withLock {
            job = jobs.remove(taskId)
            manager = managers.remove(taskId)
            _slots.value = _slots.value - taskId
        }
        job?.cancelAndJoin()
        manager?.stop()
    }

    suspend fun stopAll() {
        val snapshot: Pair<List<Job>, List<OpenCodeManager>>
        mutex.withLock {
            snapshot = jobs.values.toList() to managers.values.toList()
            jobs.clear()
            managers.clear()
            _slots.value = emptyMap()
        }
        snapshot.first.forEach { it.cancelAndJoin() }
        snapshot.second.forEach { it.stop() }
    }
}
