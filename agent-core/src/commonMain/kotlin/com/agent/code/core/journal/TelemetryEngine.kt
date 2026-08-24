package com.agent.code.core.journal

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    fun emit(entry: LogEntry) {
        pending.add(entry)
        if (!scheduled) {
            scheduled = true
            scope.launch {
                delay(frameMs)
                flush()
            }
        }
    }

    fun flush() {
        if (pending.isEmpty()) {
            scheduled = false
            return
        }
        _frames.tryEmit(pending.toList())
        pending.clear()
        scheduled = false
    }

    val pendingCount: Int get() = pending.size

    fun drainPending(): List<LogEntry> {
        val snapped = pending.toList()
        pending.clear()
        scheduled = false
        return snapped
    }
}
