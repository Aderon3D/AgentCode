package com.agent.code.opencode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One real `opencode run --format json` capture (bash tool + answer), fed
 * through the parser and the accumulator. Guards the field shapes the binary
 * actually emits, which hand-written fixtures drift from.
 */
class RunSampleTest {

    private val sample = listOf(
        """{"type":"step_start","timestamp":1,"sessionID":"ses_x","part":{"id":"p1","messageID":"m1","sessionID":"ses_x","type":"step-start"}}""",
        """{"type":"tool_use","timestamp":2,"sessionID":"ses_x","part":{"type":"tool","tool":"bash","callID":"call_1","state":{"status":"completed","input":{"command":"echo hello-tool-test"},"output":"hello-tool-test\n","metadata":{"output":"hello-tool-test\n","exit":0,"truncated":false},"title":"echo hello-tool-test","time":{"start":1,"end":2}},"id":"p2","sessionID":"ses_x","messageID":"m1"}}""",
        """{"type":"step_finish","timestamp":3,"sessionID":"ses_x","part":{"id":"p3","reason":"tool-calls","messageID":"m1","sessionID":"ses_x","type":"step-finish","tokens":{"total":7713,"input":64,"output":22,"reasoning":11,"cache":{"write":0,"read":7616}},"cost":0}}""",
        """{"type":"step_start","timestamp":4,"sessionID":"ses_x","part":{"id":"p4","messageID":"m2","sessionID":"ses_x","type":"step-start"}}""",
        """{"type":"text","timestamp":5,"sessionID":"ses_x","part":{"id":"p5","messageID":"m2","sessionID":"ses_x","type":"text","text":"hello-tool-test","time":{"start":4,"end":5}}}""",
        """{"type":"step_finish","timestamp":6,"sessionID":"ses_x","part":{"id":"p6","reason":"stop","messageID":"m2","sessionID":"ses_x","type":"step-finish","tokens":{"total":7741,"input":108,"output":5,"reasoning":12,"cache":{"write":0,"read":7616}},"cost":0}}""",
    )

    @Test
    fun capturedRunStreamsToolThenAnswerAndSucceeds() {
        val events = sample.flatMap { parseRunEvent(it) }

        val tools = events.filterIsInstance<SessionEvent.ToolCalled>()
        assertEquals(1, tools.size)
        assertEquals("bash", tools[0].tool)
        assertEquals("""{"command":"echo hello-tool-test"}""", tools[0].input)

        val finished = events.filterIsInstance<SessionEvent.ToolFinished>()
        assertEquals(1, finished.size)
        assertTrue(finished[0].ok)
        assertEquals("hello-tool-test\n", finished[0].output)

        val answers = events.filterIsInstance<SessionEvent.TextEnded>()
        assertEquals(listOf("hello-tool-test"), answers.map { it.text })
        assertEquals(listOf("stop"), events.filterIsInstance<SessionEvent.StepEnded>().map { it.finish }.takeLast(1))
    }

    @Test
    fun accumulatorClassifiesTheCapturedRunAsDone() {
        val acc = RunAccumulator()
        val brain = sample.flatMap { parseRunEvent(it) }.mapNotNull { acc.onEvent(it, "mimo") }

        assertTrue(acc.finished)
        assertNull(acc.failure)
        assertEquals(2, acc.steps)
        assertTrue(brain.any { it is BrainEvent.ToolCallStarted && it.toolName == "bash" })
        assertTrue(brain.any { it is BrainEvent.Answer && it.text == "hello-tool-test" })
        assertEquals("hello-tool-test\n\n", acc.transcript.toString())
    }

    @Test
    fun sessionIdIsReadFromTheFirstLine() {
        val first = sample.first()
        assertEquals("ses_x", sessionIdOf(first))
        assertNull(sessionIdOf("not json"))
    }
}
