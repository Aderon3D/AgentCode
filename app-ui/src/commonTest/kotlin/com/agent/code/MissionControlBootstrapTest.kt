package com.agent.code

import com.agent.code.bootstrap.MissionControlBootstrap
import com.agent.code.core.fsm.AgentState
import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.InMemoryWalStore
import com.agent.code.core.journal.LogEntry
import com.agent.code.core.journal.WalStore
import com.agent.code.core.tools.CircuitBreaker
import com.agent.code.kanban.KanbanBoard
import com.agent.code.kanban.KanbanColumn
import com.agent.code.kanban.TaskCard
import com.agent.code.provider.HierarchicalModelRouter
import com.agent.code.provider.ProviderRegistry
import com.agent.code.provider.StreamingJsonStateMachine
import com.agent.code.provider.TaskComplexity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class MissionControlBootstrapTest {

    @Test
    fun demoRecoversIdenticalStateAfterSimulatedCrash() = runBlocking {
        val timeline = MissionControlBootstrap.runDemo()

        assertTrue(timeline.finalState is AgentState.Success, "expected Success, got ${timeline.finalState}")
        assertEquals(timeline.finalState, timeline.recoveredState, "WAL replay must reconstruct identical state")
        val telemetry = timeline.telemetryFrames.flatten()
        assertTrue(telemetry.any { it is LogEntry.AgentThought && "Planning" in it.markdown }, "planning thought captured")
        assertTrue(telemetry.any { it is LogEntry.AgentThought && "Success" in it.markdown }, "success thought captured")
    }

    @Test
    fun walReplayIsDeterministic() {
        val store: WalStore = InMemoryWalStore()
        val journal = AgentEventJournal(store)
        journal.append(
            com.agent.code.core.journal.AgentEvent.TaskStarted(1, "T9", 0, "goal")
        )
        journal.append(
            com.agent.code.core.journal.AgentEvent.TaskSucceeded(2, "T9", 1, "done")
        )

        val recovered = AgentEventJournal(
            InMemoryWalStore().apply { store.replay().forEach { append(it) } }
        ).recoverState("T9")

        assertTrue(recovered is AgentState.Success)
        assertEquals(recovered, journal.recoverState("T9"))
    }
}

class HierarchicalModelRouterTest {

    private class FakeProvider(override val providerId: String, override val displayName: String) :
        com.agent.code.provider.LlmProvider {
        override fun streamCompletion(request: com.agent.code.provider.LlmRequest) =
            kotlinx.coroutines.flow.flow<com.agent.code.provider.LlmEvent> {}
        override suspend fun healthCheck() = Result.success(listOf("ok"))
    }

    @Test
    fun selectsCheapestAvailableProvider() {
        val registry = ProviderRegistry().apply {
            register(FakeProvider("deepseek-coder", "DeepSeek"))
            register(FakeProvider("claude-3-5-haiku", "Haiku"))
        }
        val router = HierarchicalModelRouter(registry, CircuitBreaker())
        assertEquals("deepseek-coder", router.selectModel(TaskComplexity.LOW_LINT_FORMAT).providerId)
    }

    @Test
    fun skipsOpenCircuitAndFallsBack() {
        val registry = ProviderRegistry().apply {
            register(FakeProvider("deepseek-coder", "DeepSeek"))
            register(FakeProvider("claude-3-5-haiku", "Haiku"))
        }
        val router = HierarchicalModelRouter(registry, CircuitBreaker(openFor = setOf("deepseek-coder")))
        assertEquals("claude-3-5-haiku", router.selectModel(TaskComplexity.LOW_LINT_FORMAT).providerId)
    }
}

class StreamingJsonStateMachineTest {

    @Test
    fun completesAfterChunkedDelivery() {
        val machine = StreamingJsonStateMachine()
        val payload = """{"tool":"read_file","args":{"path":"/x"}}"""
        payload.chunked(5).forEach { machine.feed(it) }
        assertTrue(machine.isComplete, "machine should report a complete top-level JSON object")
    }
}

class KanbanBoardTest {

    @Test
    fun legalForwardTransitionSucceeds() {
        val board = KanbanBoard()
        board.add(TaskCard("K1", "task", KanbanColumn.BACKLOG))
        assertEquals(KanbanColumn.PLANNING, board.move("K1", KanbanColumn.PLANNING).column)
        assertEquals(KanbanColumn.IN_PROGRESS, board.move("K1", KanbanColumn.IN_PROGRESS).column)
    }

    @Test
    fun illegalTransitionIsRejected() {
        val board = KanbanBoard()
        board.add(TaskCard("K2", "task", KanbanColumn.BACKLOG))
        assertFailsWith<IllegalArgumentException> { board.move("K2", KanbanColumn.DONE) }
    }
}
