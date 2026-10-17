package com.agent.code.opencode

import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.InMemoryWalStore
import com.agent.code.core.journal.TelemetryEngine
import com.agent.code.core.path.VirtualPath
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
    // Pinned to 1: the native loader keys child state process-globally
    // (elfloader g_child_pid/g_child_exit), so a 2nd concurrent spawn clobbers
    // the 1st's pid/exit/log — and every manager would share one installDir,
    // truncating one opencode.log. Raise only once #200 (per-pid slots + single
    // drain/dispatcher) and per-slot log/install isolation land.
    private val maxConcurrent: Int = 1
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

        // LAZY: jobs[taskId] must be set before the body can reach its finally,
        // else a fast-finishing job sees no map entry and skips all cleanup.
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            // Check slot still exists before expensive work (handles cancelTask during launch window)
            val slotExists = mutex.withLock { _slots.value.containsKey(taskId) }
            if (!slotExists) return@launch

            try {
                manager.ensureInstalled()
                // Re-check after suspending call: cancelTask may have removed the slot
                val stillRunning = mutex.withLock { _slots.value.containsKey(taskId) }
                if (!stillRunning) return@launch

                // r24 pivot: the brain drives `opencode run --format json`
                // (one-shot subprocess per task) — no persistent `serve` to
                // start, no port, no SSE. ensureInstalled above stages the
                // binary; runPrompt re-checks it before each spawn.
                val journal = AgentEventJournal(InMemoryWalStore())
                val telemetry = TelemetryEngine(scope)
                val brain = AgentBrain(manager, journal, telemetry)

                mutex.withLock {
                    val slot = _slots.value[taskId] ?: return@withLock
                    _slots.value = _slots.value + (taskId to slot.copy(state = AgentSlotState.Running(0)))
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
                var ownedPort: Int? = null
                val owned = mutex.withLock {
                    if (jobs[taskId] === myJob) {
                        ownedPort = _slots.value[taskId]?.port
                        jobs.remove(taskId)
                        managers.remove(taskId)
                        _slots.value = _slots.value - taskId
                        true
                    } else false
                }
                if (owned) {
                    // Stop the child before its port returns to the pool, or a
                    // new task can bind the same port while this server still
                    // listens. NonCancellable: this may run on a cancelled job.
                    try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { manager.stop() }
                    } catch (_: Exception) {}
                    ownedPort?.let { p -> mutex.withLock { allocatedPorts.remove(p) } }
                }
            }
        }
        mutex.withLock { jobs[taskId] = job }
        job.start()
    }

    suspend fun cancelTask(taskId: String) {
        val job: Job?
        val manager: OpenCodeManager?
        val port: Int?
        mutex.withLock {
            job = jobs.remove(taskId)
            manager = managers.remove(taskId)
            port = _slots.value[taskId]?.port
            _slots.value = _slots.value - taskId
        }
        job?.cancelAndJoin()
        // Stop the child before returning its port to the pool, or a new task
        // can bind the same port while this server still listens.
        manager?.stop()
        port?.let { p -> mutex.withLock { allocatedPorts.remove(p) } }
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
