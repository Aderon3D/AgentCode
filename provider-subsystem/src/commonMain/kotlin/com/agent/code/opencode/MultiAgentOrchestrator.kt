package com.agent.code.opencode

import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.InMemoryWalStore
import com.agent.code.core.journal.TelemetryEngine
import com.agent.code.core.path.VirtualPath
import com.agent.code.mcp.McpHost
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner
import kotlinx.coroutines.CancellationException
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
    private val nativeLibraryDir: String,
    private val maxConcurrent: Int = 3
) {
    private val scope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default)
    private val mutex = Mutex()
    private val _slots = MutableStateFlow<Map<String, AgentSlot>>(emptyMap())
    val slots: StateFlow<Map<String, AgentSlot>> = _slots.asStateFlow()

    private val managers = mutableMapOf<String, OpenCodeManager>()
    private val jobs = mutableMapOf<String, Job>()
    private val allocatedPorts = mutableSetOf<Int>()

    suspend fun submitTask(taskId: String, goal: String): AgentSlot = mutex.withLock {
        if (managers.size >= maxConcurrent) {
            throw IllegalStateException("Max concurrent agents ($maxConcurrent) reached")
        }
        // Reserve on submit, not on Running: the documented flow is
        // submit-all-then-start, so deriving the port from currentState() gave
        // every not-yet-started task the same default port (4096).
        val port = (4096..65535).first { it !in allocatedPorts }
        allocatedPorts += port
        val config = OpenCodeConfig(defaultPort = port, nativeLibraryDir = nativeLibraryDir)
        val manager = OpenCodeManager(fileSystem, processRunner, config)
        managers[taskId] = manager

        val slot = AgentSlot(taskId, goal, AgentSlotState.Idle, port)
        _slots.value = _slots.value + (taskId to slot)
        slot
    }

    suspend fun startTask(taskId: String) {
        val manager = mutex.withLock {
            if (jobs.containsKey(taskId)) throw IllegalStateException("Task $taskId is already running")
            managers[taskId]
        } ?: throw IllegalStateException("No slot for task $taskId")

        mutex.withLock {
            val slot = _slots.value[taskId] ?: return@withLock
            _slots.value = _slots.value + (taskId to slot.copy(state = AgentSlotState.Starting))
        }

        val job = scope.launch {
            // Check slot still exists before expensive work (handles cancelTask during launch window)
            val slotExists = mutex.withLock { _slots.value.containsKey(taskId) }
            if (!slotExists) return@launch

            try {
                manager.ensureInstalled()
                // Re-check after suspending call: cancelTask may have removed the slot
                val stillRunning = mutex.withLock { _slots.value.containsKey(taskId) }
                if (!stillRunning) return@launch

                manager.start(projectDir = workspaceRoot).let { s ->
                    if (s is OpenCodeState.Error) throw IllegalStateException(s.message)
                }
                // Re-check after suspending call
                val stillRunning2 = mutex.withLock { _slots.value.containsKey(taskId) }
                if (!stillRunning2) return@launch

                val httpClient = io.ktor.client.HttpClient {
                    // Must match AgentViewModel.create: the SSE per-request
                    // INFINITE override is only honored when HttpTimeout is
                    // installed, else the default 10s read timeout kills a
                    // healthy run that idles between steps.
                    install(io.ktor.client.plugins.HttpTimeout) {
                        requestTimeoutMillis = 30_000
                        connectTimeoutMillis = 5_000
                        socketTimeoutMillis = 30_000
                    }
                }
                try {
                    val client = OpenCodeClient(httpClient, manager)
                    val journal = AgentEventJournal(InMemoryWalStore())
                    val telemetry = TelemetryEngine(scope)
                    val brain = AgentBrain(client, journal, telemetry)

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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutex.withLock {
                    val current = _slots.value[taskId] ?: return@withLock
                    _slots.value = _slots.value + (taskId to current.copy(
                        state = AgentSlotState.Failed(e.message ?: "unknown error")
                    ))
                }
            } finally {
                // Only clean up if this is still the current job (guards against taskId reuse)
                val myJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                mutex.withLock {
                    if (jobs[taskId] === myJob) {
                        jobs.remove(taskId)
                        managers.remove(taskId)
                        _slots.value[taskId]?.port?.let { allocatedPorts.remove(it) }
                        _slots.value = _slots.value - taskId
                    }
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
            _slots.value[taskId]?.port?.let { allocatedPorts.remove(it) }
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
            allocatedPorts.clear()
            _slots.value = emptyMap()
        }
        snapshot.first.forEach { it.cancelAndJoin() }
        snapshot.second.forEach { it.stop() }
    }
}
