package com.agent.code.opencode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AgentBrainSessionTest {
    @Test
    fun sessionIdExtractedFromRunEvent() {
        val line =
            """{"type":"step_start","timestamp":1,"sessionID":"ses_abc","part":{"type":"step-start"}}"""
        assertEquals("ses_abc", sessionIdOf(line))
    }

    @Test
    fun linesWithoutSessionYieldNull() {
        assertNull(sessionIdOf("elfloader: drain ev tid=1234 st=0x1f7f n=1"))
        assertNull(sessionIdOf(""))
        assertNull(sessionIdOf("""{"type":"text","part":{"text":"no id here"}}"""))
    }
}
