package com.agent.code.opencode

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OpenCodeClientEventTest {
    @Test
    fun permissionAsked_parsesBothVersions() {
        val v1 = parseSessionEventJson(
            """{"type":"permission.asked","data":{"id":"req_1","permission":"bash"}}"""
        )
        assertIs<SessionEvent.PermissionAsked>(v1)
        assertEquals("req_1", v1.requestID)
        assertEquals("bash", v1.detail)

        val v2 = parseSessionEventJson(
            """{"type":"permission.v2.asked","data":{"requestID":"req_2","title":"Edit"}}"""
        )
        assertIs<SessionEvent.PermissionAsked>(v2)
        assertEquals("req_2", v2.requestID)
        assertEquals("Edit", v2.detail)
    }

    @Test
    fun questionAsked_parsesBothVersions() {
        val v1 = parseSessionEventJson(
            """{"type":"question.asked","data":{"id":"q_1","questions":[]}}"""
        )
        assertIs<SessionEvent.QuestionAsked>(v1)
        assertEquals("q_1", v1.requestID)

        val v2 = parseSessionEventJson(
            """{"type":"question.v2.asked","data":{"requestID":"q_2"}}"""
        )
        assertIs<SessionEvent.QuestionAsked>(v2)
        assertEquals("q_2", v2.requestID)
    }

    @Test
    fun unknownEvent_isIgnored() {
        assertEquals(null, parseSessionEventJson("""{"type":"nope.asked","data":{}}"""))
    }

    @Test
    fun globalEnvelope_wrapsPropertiesAsData() {
        // The global /api/event stream wraps events as
        // {directory, payload:{type, properties}} — must parse the same as
        // the flat per-session {type, data} form.
        val flat = parseSessionEventJson(
            """{"type":"session.next.text.ended","data":{"sessionID":"s1","text":"hi"}}"""
        )
        assertIs<SessionEvent.TextEnded>(flat)
        assertEquals("hi", flat.text)

        val wrapped = parseSessionEventJson(
            """{"directory":"/ws","payload":{"type":"session.next.text.ended","properties":{"sessionID":"s1","text":"hi"}}}"""
        )
        assertIs<SessionEvent.TextEnded>(wrapped)
        assertEquals("hi", wrapped.text)
    }

    @Test
    fun eventBelongsToSession_filtersBySessionID() {
        val ours = """{"directory":"/ws","payload":{"type":"session.next.text.delta","properties":{"sessionID":"s1","text":"x"}}}"""
        val other = """{"directory":"/ws","payload":{"type":"session.next.text.delta","properties":{"sessionID":"s2","text":"x"}}}"""
        val noSession = """{"type":"server.connected","data":{}}"""
        assertEquals(true, eventBelongsToSession(ours, "s1"))
        assertEquals(false, eventBelongsToSession(other, "s1"))
        assertEquals(false, eventBelongsToSession(noSession, "s1"))
    }

    @Test
    fun permissionAsked_parsesFromGlobalEnvelope() {
        val ev = parseSessionEventJson(
            """{"directory":"/ws","payload":{"type":"permission.v2.asked","properties":{"sessionID":"s1","requestID":"req_9","title":"Bash"}}}"""
        )
        assertIs<SessionEvent.PermissionAsked>(ev)
        assertEquals("req_9", ev.requestID)
        assertEquals("Bash", ev.detail)
    }

    // ---- run --format json (r24 pivot) ----
    // Shapes below are copied verbatim from a real 1.18.31 `run --format json`
    // capture (localtest/run_tool_auto.json), so the parser is pinned to the
    // actual schema, not a guess.

    @Test
    fun runEvent_stepStart_parses() {
        val evs = parseRunEvent(
            """{"type":"step_start","timestamp":1791750468331,"sessionID":"ses_x","part":{"id":"prt_1","messageID":"msg_1","sessionID":"ses_x","snapshot":"4b82","type":"step-start"}}"""
        )
        assertEquals(1, evs.size)
        assertIs<SessionEvent.StepStarted>(evs[0])
    }

    @Test
    fun runEvent_text_parsesWholeText() {
        val evs = parseRunEvent(
            """{"type":"text","timestamp":1,"sessionID":"ses_x","part":{"id":"prt_1","messageID":"msg_1","sessionID":"ses_x","type":"text","text":"hello world","time":{"start":1,"end":2}}}"""
        )
        assertEquals(1, evs.size)
        val t = evs[0]
        assertIs<SessionEvent.TextEnded>(t)
        assertEquals("hello world", t.text)
    }

    @Test
    fun runEvent_completedTool_useYieldsStartAndFinish() {
        // A completed tool_use carries BOTH the start and the finish in one line.
        val evs = parseRunEvent(
            """{"type":"tool_use","timestamp":1,"sessionID":"ses_x","part":{"type":"tool","tool":"read","callID":"call_abc","state":{"status":"completed","input":{"filePath":"/x"},"output":"...","title":"file.txt"},"id":"prt_1","sessionID":"ses_x","messageID":"msg_1"}}"""
        )
        assertEquals(2, evs.size)
        assertIs<SessionEvent.ToolCalled>(evs[0])
        assertEquals("read", (evs[0] as SessionEvent.ToolCalled).tool)
        val fin = evs[1]
        assertIs<SessionEvent.ToolFinished>(fin)
        assertEquals("call_abc", fin.callID)
        assertEquals(true, fin.ok)
    }

    @Test
    fun runEvent_runningTool_useYieldsStartOnly() {
        val evs = parseRunEvent(
            """{"type":"tool_use","timestamp":1,"sessionID":"ses_x","part":{"type":"tool","tool":"bash","callID":"call_1","state":{"status":"running","input":{}}}}"""
        )
        assertEquals(1, evs.size)
        assertIs<SessionEvent.ToolCalled>(evs[0])
    }

    @Test
    fun runEvent_stepFinish_reasonStopVsToolCalls() {
        val stop = parseRunEvent(
            """{"type":"step_finish","timestamp":1,"sessionID":"ses_x","part":{"id":"prt_1","reason":"stop","type":"step-finish","tokens":{"total":1},"cost":0}}"""
        )[0]
        assertIs<SessionEvent.StepEnded>(stop)
        assertEquals("stop", stop.finish)

        val toolCalls = parseRunEvent(
            """{"type":"step_finish","timestamp":1,"sessionID":"ses_x","part":{"id":"prt_1","reason":"tool-calls","type":"step-finish"}}"""
        )[0]
        assertIs<SessionEvent.StepEnded>(toolCalls)
        assertEquals("tool-calls", toolCalls.finish)
    }

    @Test
    fun runEvent_error_parsesMessage() {
        val evs = parseRunEvent(
            """{"type":"error","timestamp":1,"sessionID":"ses_x","error":{"name":"AbortError","data":{"message":"model overloaded"}}}"""
        )
        assertEquals(1, evs.size)
        val f = evs[0]
        assertIs<SessionEvent.Failed>(f)
        assertEquals("model overloaded", f.message)
    }

    @Test
    fun runEvent_nonJsonAndUnknown_areSkipped() {
        // elfloader forensics lines (not JSON) and unknown types yield nothing.
        assertEquals(emptyList(), parseRunEvent("elfloader: hb state=R wchan=0"))
        assertEquals(emptyList(), parseRunEvent("""{"type":"file","part":{"type":"file"}}"""))
        assertEquals(emptyList(), parseRunEvent("not json at all"))
    }
}
