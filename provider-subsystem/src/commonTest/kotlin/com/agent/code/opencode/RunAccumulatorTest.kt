package com.agent.code.opencode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The run's success classification — a truncated step must never look done. */
class RunAccumulatorTest {

    private fun acc() = RunAccumulator()

    @Test
    fun stopFinishesButTruncationFails() {
        val stop = acc()
        stop.onEvent(SessionEvent.StepEnded("stop"), "m")
        assertTrue(stop.finished)
        assertNull(stop.failure)

        // "length" = the model hit max tokens: a half answer, not a success.
        val cut = acc()
        cut.onEvent(SessionEvent.StepEnded("length"), "m")
        assertTrue(!cut.finished)
        assertTrue(cut.failure != null)
    }

    @Test
    fun toolCallsStepKeepsWorking() {
        val a = acc()
        a.onEvent(SessionEvent.StepEnded("tool-calls"), "m")
        assertTrue(!a.finished)
        assertNull(a.failure)
    }

    @Test
    fun answerTextIsTranscriptAndNotProtocolNoise() {
        val a = acc()
        val ev = a.onEvent(SessionEvent.TextEnded("  hello  "), "m")
        assertIs<BrainEvent.Answer>(ev)
        assertEquals("hello", (ev as BrainEvent.Answer).text)
        assertEquals("hello\n\n", a.transcript.toString())

        assertNull(a.onEvent(SessionEvent.TextEnded("   "), "m"))
    }

    @Test
    fun toolEventsCarryInputAndOutput() {
        val a = acc()
        val started = a.onEvent(SessionEvent.ToolCalled("bash", """{"command":"ls"}"""), "m")
        assertIs<BrainEvent.ToolCallStarted>(started)
        assertEquals("""{"command":"ls"}""", (started as BrainEvent.ToolCallStarted).args)

        val done = a.onEvent(SessionEvent.ToolFinished("c1", true, "a.txt"), "m")
        assertIs<BrainEvent.ToolCallFinished>(done)
        assertEquals("bash", (done as BrainEvent.ToolCallFinished).toolName)
        assertEquals("a.txt", done.output)
    }

    @Test
    fun failedRunMessageIsKept() {
        val a = acc()
        assertNull(a.onEvent(SessionEvent.Failed("model gone"), "m"))
        assertEquals("model gone", a.failure)
    }
}
