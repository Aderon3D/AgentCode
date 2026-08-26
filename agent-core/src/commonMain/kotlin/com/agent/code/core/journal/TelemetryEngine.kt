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
    private var _lastFrame: List<LogEntry> = emptyList()
    val lastFrame: List<LogEntry>
        get() {
            while (!lock.tryLock()) { /* spin until available */ }
            try {
                return _lastFrame
            } finally {
                lock.unlock()
            }
        }

    fun emit(entry: LogEntry) {
        var wasScheduled = false
        while (!lock.tryLock()) { /* spin until available */ }
        try {
            pending.add(entry)
            wasScheduled = scheduled
            scheduled = true
        } finally {
            lock.unlock()
        }
        if (!wasScheduled) {
            scope.launch {
                delay(frameMs)
                flush()
            }
        }
    }

    fun flush() {
        while (!lock.tryLock()) { /* spin until available */ }
        try {
            if (pending.isEmpty()) {
                scheduled = false
                return
            }
            val snapshot = pending.toList()
            pending.clear()
            scheduled = false
            _lastFrame = snapshot
            _frames.tryEmit(snapshot)
        } finally {
            lock.unlock()
        }
    }

    val pendingCount: Int
        get() {
            while (!lock.tryLock()) { /* spin until available */ }
            try {
                return pending.size
            } finally {
                lock.unlock()
            }
        }

    fun drainPending(): List<LogEntry> {
        while (!lock.tryLock()) { /* spin until available */ }
        try {
            val snapshot = pending.toList()
            pending.clear()
            scheduled = false
            return snapshot
        } finally {
            lock.unlock()
        }
    }
}
