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
    private val slotsMutex = Mutex()
    private val _slots = MutableStateFlow<Map<String, AgentSlot>>(emptyMap())
    val slots: StateFlow<Map<String, AgentSlot>> = _slots.asStateFlow()

    private val managers = mutableMapOf<String, OpenCodeManager>()
    private val jobs = mutableMapOf<String, Job>()

    private fun nextPort(base: Int, index: Int): Int = base + index

    suspend fun submitTask(taskId: String, goal: String): AgentSlot = slotsMutex.withLock {
        if (managers.size >= maxConcurrent) {
            throw IllegalStateException("Max concurrent agents ($maxConcurrent) reached")
        }
        val port = nextPort(4096, managers.size)
        val config = OpenCodeConfig(defaultPort = port)
        val manager = OpenCodeManager(fileSystem, processRunner, config)
        managers[taskId] = manager

        val slot = AgentSlot(taskId, goal, AgentSlotState.Idle, port)
        _slots.value = _slots.value + (taskId to slot)
        slot
    }

    suspend fun startTask(taskId: String) {
        val manager = managers[taskId]
            ?: throw IllegalStateException("No slot for task $taskId")

        slotsMutex.withLock {
            _slots.value = _slots.value + (taskId to _slots.value[taskId]!!.copy(state = AgentSlotState.Starting))
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
                    val slot = _slots.value[taskId]!!

                    slotsMutex.withLock {
                        val port = manager.currentState().let { if (it is OpenCodeState.Running) it.port else null }
                        _slots.value = _slots.value + (taskId to slot.copy(state = AgentSlotState.Running(0), port = port))
                    }

                    brain.executeTask(taskId, slot.goal, workspaceRoot).collect { event ->
                        when (event) {
                            is BrainEvent.IterationComplete -> {
                                slotsMutex.withLock {
                                    _slots.value = _slots.value + (taskId to _slots.value[taskId]!!.copy(
                                        state = AgentSlotState.Running(event.iteration)
                                    ))
                                }
                            }
                            is BrainEvent.TaskComplete -> {
                                slotsMutex.withLock {
                                    _slots.value = _slots.value + (taskId to _slots.value[taskId]!!.copy(
                                        state = AgentSlotState.Done(event.summary)
                                    ))
                                }
                            }
                            is BrainEvent.TaskFailed -> {
                                slotsMutex.withLock {
                                    _slots.value = _slots.value + (taskId to _slots.value[taskId]!!.copy(
                                        state = AgentSlotState.Failed(event.reason)
                                    ))
                                }
                            }
                            else -> {}
                        }
                    }
                } finally {
                    httpClient.close()
                }
            } catch (e: Exception) {
                slotsMutex.withLock {
                    _slots.value = _slots.value + (taskId to _slots.value[taskId]!!.copy(
                        state = AgentSlotState.Failed(e.message ?: "unknown error")
                    ))
                }
            }
        }
        jobs[taskId] = job
    }

    suspend fun cancelTask(taskId: String) {
        jobs[taskId]?.cancel()
        jobs.remove(taskId)
        managers[taskId]?.stop()
        managers.remove(taskId)
        slotsMutex.withLock {
            _slots.value = _slots.value - taskId
        }
    }

    suspend fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        managers.values.forEach { it.stop() }
        managers.clear()
        slotsMutex.withLock {
            _slots.value = emptyMap()
        }
    }
}
