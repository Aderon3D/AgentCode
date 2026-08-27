package com.agent.code.bootstrap

import com.agent.code.core.fsm.AgentOrchestrator
import com.agent.code.core.fsm.AgentState
import com.agent.code.core.fsm.ToolCall
import com.agent.code.core.journal.AgentEvent
import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.InMemoryWalStore
import com.agent.code.core.journal.LogEntry
import com.agent.code.core.journal.TelemetryEngine
import com.agent.code.core.journal.WalStore
import com.agent.code.core.policy.AutonomyPolicy
import com.agent.code.mcp.McpHost
import com.agent.code.workspace.InMemoryFileSystem
import com.agent.code.workspace.StubProcessRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class Timeline(
    val events: List<AgentEvent>,
    val telemetryFrames: List<List<LogEntry>>,
    val finalState: AgentState,
    val recoveredState: AgentState
)

object MissionControlBootstrap {

    suspend fun runDemo(): Timeline {
        val fs = InMemoryFileSystem()
        fs.write(com.agent.code.core.path.VirtualPath.of("/src/main.kt"), "fun main() { println(\"hi\") }").getOrThrow()
        val mcp = McpHost(fs, StubProcessRunner())
        val wal = InMemoryWalStore()
        val journal = AgentEventJournal(wal)
        val telemetry = TelemetryEngine(CoroutineScope(Dispatchers.Unconfined))
        val orchestrator = AgentOrchestrator(journal, AutonomyPolicy(), mcp, telemetry)

        val framesCollected = mutableListOf<List<LogEntry>>()
        val collectJob = CoroutineScope(Dispatchers.Unconfined).launch {
            telemetry.frames.collect { framesCollected.add(it) }
        }

        orchestrator.startTask("T1", "Add a greeting")
        telemetry.flush()
        orchestrator.runTool(
            "T1",
            ToolCall("c1", "read_file", """{"path":"/src/main.kt"}""")
        )
        orchestrator.runTool(
            "T1",
            ToolCall("c2", "apply_diff_patch", """{"path":"/src/main.kt","search":"hi","replace":"hello world"}""")
        )
        orchestrator.succeed("T1", "patched greeting")
        telemetry.flush()

        collectJob.cancel()

        val finalState = orchestrator.recover("T1")

        val recoveredStore: WalStore = InMemoryWalStore().apply {
            wal.replay().forEach { append(it) }
        }
        val recoveredState = AgentEventJournal(recoveredStore).recoverState("T1")

        return Timeline(
            events = journal.allEvents(),
            telemetryFrames = framesCollected,
            finalState = finalState,
            recoveredState = recoveredState
        )
    }
}
