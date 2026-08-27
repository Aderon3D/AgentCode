package com.agent.code.core.journal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface LogEntry {
    val timestampMs: Long
    data class AgentThought(override val timestampMs: Long, val markdown: String) : LogEntry
    data class ToolCallStarted(override val timestampMs: Long, val toolName: String, val args: String) : LogEntry
    data class TerminalStream(override val timestampMs: Long, val line: String, val isError: Boolean) : LogEntry
    data class SystemWarning(override val timestampMs: Long, val message: String) : LogEntry
}

class TelemetryEngine(
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val frameMs: Long = 50L
) {
    private val _frames = MutableSharedFlow<List<LogEntry>>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val frames: Flow<List<LogEntry>> = _frames.asSharedFlow()

    private val pending = mutableListOf<LogEntry>()
    private var scheduled = false
    private val lock = Mutex()

    // Retained state: last emitted frame survives collection lifecycle
    // Volatile for lock-free reads from composition (single reference read is atomic on JVM).
    @Volatile
    private var _lastFrame: List<LogEntry> = emptyList()
    val lastFrame: List<LogEntry> get() = _lastFrame

    suspend fun emit(entry: LogEntry) {
        var wasScheduled = false
        lock.withLock {
            pending.add(entry)
            wasScheduled = scheduled
            scheduled = true
        }
        if (!wasScheduled) {
            scope.launch {
                delay(frameMs)
                flush()
            }
        }
    }

    suspend fun flush() {
        lock.withLock {
            if (pending.isEmpty()) {
                scheduled = false
                return
            }
            val snapshot = pending.toList()
            pending.clear()
            scheduled = false
            _lastFrame = snapshot
            _frames.tryEmit(snapshot)
        }
    }

    suspend fun pendingCount(): Int = lock.withLock { pending.size }

    suspend fun drainPending(): List<LogEntry> = lock.withLock {
        val snapshot = pending.toList()
        pending.clear()
        scheduled = false
        snapshot
    }
}
