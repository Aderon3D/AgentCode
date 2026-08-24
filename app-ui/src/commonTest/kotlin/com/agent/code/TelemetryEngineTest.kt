package com.agent.code

import com.agent.code.core.journal.LogEntry
import com.agent.code.core.journal.TelemetryEngine
import kotlin.test.Test
import kotlin.test.assertEquals

class TelemetryEngineTest {

    @Test
    fun batchesRapidEmitsIntoSingleDrainedFrame() {
        val engine = TelemetryEngine()
        engine.emit(LogEntry.AgentThought(1, "a"))
        engine.emit(LogEntry.AgentThought(2, "b"))
        engine.emit(LogEntry.ToolCallStarted(3, "read_file", "{}"))

        assertEquals(3, engine.pendingCount)
        val frame = engine.drainPending()
        assertEquals(3, frame.size)
        assertEquals(0, engine.pendingCount)
    }
}
