package com.agent.code.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agent.code.core.fsm.AgentOrchestrator
import com.agent.code.core.fsm.AgentState
import com.agent.code.core.fsm.ToolCall
import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.InMemoryWalStore
import com.agent.code.core.journal.TelemetryEngine
import com.agent.code.core.policy.AutonomyPolicy
import com.agent.code.core.power.StubPowerGovernor
import com.agent.code.kanban.KanbanBoard
import com.agent.code.kanban.KanbanColumn
import com.agent.code.kanban.TaskCard
import com.agent.code.mcp.McpHost
import com.agent.code.workspace.InMemoryFileSystem
import com.agent.code.workspace.StubProcessRunner
import kotlinx.coroutines.launch

/**
 * M3 (§0.1) Mission-Control centerpiece: live FSM + Kanban board.
 * Drives the real [AgentOrchestrator] through one task lifecycle and mirrors
 * it onto a [KanbanBoard], proving the orchestrator spine end-to-end in UI.
 * ponytail: one button runs the whole lifecycle; reset not needed for demo.
 */
@Composable
fun MissionControlPanel() {
    val scope = rememberCoroutineScope()
    val board = remember {
        KanbanBoard().apply { add(TaskCard("T1", "Add greeting", KanbanColumn.BACKLOG)) }
    }
    var fsmState by remember { mutableStateOf<AgentState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val orchestrator = remember {
        AgentOrchestrator(
            AgentEventJournal(InMemoryWalStore()),
            AutonomyPolicy(),
            McpHost(InMemoryFileSystem(), StubProcessRunner()),
            TelemetryEngine(scope),
            governor = StubPowerGovernor(),
        )
    }

    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        Text("Mission Control", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))

        // Kanban board
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (col in KanbanColumn.values()) {
                Column(Modifier.width(120.dp).padding(4.dp)) {
                    Text(col.name, fontWeight = FontWeight.Bold)
                    for (card in board.all().filter { it.column == col }) {
                        Text("• ${card.title}")
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            enabled = !busy,
            onClick = {
                busy = true
                error = null
                scope.launch {
                    try {
                        orchestrator.startTask("T1", "Add a greeting")
                        board.move("T1", KanbanColumn.PLANNING)
                        orchestrator.runTool("T1", ToolCall("c1", "read_file", """{"path":"/src/main.kt"}"""))
                        board.move("T1", KanbanColumn.IN_PROGRESS)
                        orchestrator.runTool(
                            "T1",
                            ToolCall("c2", "apply_diff_patch", """{"path":"/src/main.kt","search":"hi","replace":"hello world"}"""),
                        )
                        orchestrator.succeed("T1", "patched greeting")
                        board.move("T1", KanbanColumn.VERIFICATION)
                        board.move("T1", KanbanColumn.DONE)
                        fsmState = orchestrator.recover("T1")
                    } catch (e: Exception) {
                        error = "${e::class.simpleName}: ${e.message}"
                    } finally {
                        busy = false
                    }
                }
            },
        ) { Text("Run agent (T1)") }

        fsmState?.let {
            Spacer(Modifier.height(4.dp))
            Text("FSM state: $it", fontWeight = FontWeight.Bold)
        }
        error?.let {
            Spacer(Modifier.height(4.dp))
            Text("Error: $it", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
    }
}
