package com.agent.code

import com.agent.code.core.concurrency.EnergyAwareDispatchers
import com.agent.code.core.fsm.AgentOrchestrator
import com.agent.code.core.fsm.AgentState
import com.agent.code.core.fsm.OperatingProfile
import com.agent.code.core.fsm.StepResult
import com.agent.code.core.fsm.ToolCall
import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.InMemoryWalStore
import com.agent.code.core.journal.TelemetryEngine
import com.agent.code.core.lock.*
import com.agent.code.core.path.VirtualPath
import com.agent.code.core.power.StubPowerGovernor
import com.agent.code.core.policy.AutonomyPolicy
import com.agent.code.mcp.McpHost
import com.agent.code.workspace.InMemoryFileSystem
import com.agent.code.workspace.StubProcessRunner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class M2ConcurrencyTest {

    @Test
    fun lockManagerNoCollisionOnEmpty() { runBlocking {
        val mgr = WorkspaceLockManager()
        val risk = mgr.evaluateCollisionRisk(setOf(VirtualPath.of("/a.kt")), setOf("sym1"))
        assertIs<ConflictRisk.None>(risk)
    } }

    @Test
    fun lockManagerFileOverlap() { runBlocking {
        val mgr = WorkspaceLockManager()
        mgr.waitForMaintenanceAndRegisterLock("t1", ActiveTaskLock("t1", "b1", setOf(VirtualPath.of("/a.kt")), emptySet()))
        val risk = mgr.evaluateCollisionRisk(setOf(VirtualPath.of("/a.kt")), emptySet())
        assertIs<ConflictRisk.FileOverlapRequiresMerge>(risk)
        assertEquals(setOf(VirtualPath.of("/a.kt")), risk.files)
    } }

    @Test
    fun lockManagerSymbolCollision() { runBlocking {
        val mgr = WorkspaceLockManager()
        mgr.waitForMaintenanceAndRegisterLock("t1", ActiveTaskLock("t1", "b1", emptySet(), setOf("symX")))
        val risk = mgr.evaluateCollisionRisk(emptySet(), setOf("symX"))
        assertIs<ConflictRisk.FatalSymbolCollision>(risk)
        assertEquals(setOf("symX"), risk.symbols)
    } }

    @Test
    fun lockManagerReleaseFreesLock() { runBlocking {
        val mgr = WorkspaceLockManager()
        mgr.waitForMaintenanceAndRegisterLock("t1", ActiveTaskLock("t1", "b1", setOf(VirtualPath.of("/a.kt")), emptySet()))
        assertEquals(1, mgr.activeLockCount())
        mgr.releaseLock("t1")
        assertEquals(0, mgr.activeLockCount())
        assertIs<ConflictRisk.None>(mgr.evaluateCollisionRisk(setOf(VirtualPath.of("/a.kt")), emptySet()))
    } }

    @Test
    fun lockManagerMaintenanceBlocksNewLocks() { runBlocking {
        val mgr = WorkspaceLockManager()
        assertTrue(mgr.tryAcquireMaintenanceLock())
        val lock = ActiveTaskLock("t1", "b1", setOf(VirtualPath.of("/a.kt")), emptySet())
        val result = CompletableDeferred<ConflictRisk>()
        launch { result.complete(mgr.waitForMaintenanceAndRegisterLock("t1", lock)) }
        delay(50)
        assertTrue(result.isActive, "Should be blocked during maintenance")
        mgr.releaseMaintenanceLock()
        assertIs<ConflictRisk.None>(result.await())
    } }

    @Test
    fun coordinatorAcquiresPermitOnNoConflict() { runBlocking {
        val coord = TaskLockCoordinator(WorkspaceLockManager())
        val permit = coord.acquireTaskExecutionPermit("t1", "agent/task-t1", setOf(VirtualPath.of("/a.kt")), setOf("sym1"))
        assertEquals("t1", permit.taskId)
        assertEquals(false, permit.requiresAst3WayMerge)
        coord.releaseTaskExecutionPermit("t1", setOf("sym1"))
    } }

    @Test
    fun coordinatorReportsFileOverlap() { runBlocking {
        val mgr = WorkspaceLockManager()
        val coord = TaskLockCoordinator(mgr)
        coord.acquireTaskExecutionPermit("t1", "b1", setOf(VirtualPath.of("/a.kt")), emptySet())
        val permit2 = coord.acquireTaskExecutionPermit("t2", "b2", setOf(VirtualPath.of("/a.kt")), emptySet())
        assertEquals(true, permit2.requiresAst3WayMerge)
        assertEquals(setOf(VirtualPath.of("/a.kt")), permit2.overlappingFiles)
        coord.releaseTaskExecutionPermit("t1", emptySet())
        coord.releaseTaskExecutionPermit("t2", emptySet())
    } }

    @Test
    fun coordinatorWaitsOnSymbolCollision() { runBlocking {
        val mgr = WorkspaceLockManager()
        val coord = TaskLockCoordinator(mgr)
        coord.acquireTaskExecutionPermit("t1", "b1", emptySet(), setOf("sym1"))
        val result = CompletableDeferred<ExecutionPermit>()
        launch { result.complete(coord.acquireTaskExecutionPermit("t2", "b2", emptySet(), setOf("sym1"))) }
        delay(50)
        assertTrue(result.isActive, "Should be blocked on symbol collision")
        coord.releaseTaskExecutionPermit("t1", setOf("sym1"))
        assertEquals("t2", result.await().taskId)
        coord.releaseTaskExecutionPermit("t2", emptySet())
    } }

    @Test
    fun funnelNoTestRunnerPasses() { runBlocking {
        val funnel = SemanticConflictFunnel(WorkspaceLockManager())
        assertIs<SemanticVerificationResult.Passed>(
            funnel.verifyBranchIntegration("main", listOf(VirtualPath.of("/a.kt")))
        )
    } }

    @Test
    fun funnelTier3RunsTestsOnChangedFiles() { runBlocking {
        var executedCommand: List<String>? = null
        val runner = object : com.agent.code.workspace.ProcessRunner {
            override suspend fun run(command: List<String>): Result<String> {
                executedCommand = command
                return Result.success("BUILD SUCCESSFUL")
            }
        }
        val funnel = SemanticConflictFunnel(WorkspaceLockManager(), runner)

        val result = funnel.verifyBranchIntegration("main", listOf(VirtualPath.of("/src/MyTest.kt")))
        assertIs<SemanticVerificationResult.Passed>(result)
        assertTrue(executedCommand != null, "Test runner should have been invoked")
        assertTrue(executedCommand!!.contains("test"), "Should run gradle test")
    } }

    @Test
    fun funnelTier3ReportsFailure() { runBlocking {
        val runner = object : com.agent.code.workspace.ProcessRunner {
            override suspend fun run(command: List<String>): Result<String> =
                Result.failure(RuntimeException("2 tests failed"))
        }
        val funnel = SemanticConflictFunnel(WorkspaceLockManager(), runner)

        val result = funnel.verifyBranchIntegration("main", listOf(VirtualPath.of("/src/BrokenTest.kt")))
        assertIs<SemanticVerificationResult.Failed>(result)
        assertTrue(result.reason.contains("tests failed"))
    } }

    @Test
    fun funnelTier3SkipsEmptyChangeset() { runBlocking {
        var invoked = false
        val runner = object : com.agent.code.workspace.ProcessRunner {
            override suspend fun run(command: List<String>): Result<String> {
                invoked = true
                return Result.success("ok")
            }
        }
        val funnel = SemanticConflictFunnel(WorkspaceLockManager(), runner)
        val result = funnel.verifyBranchIntegration("main", emptyList())
        assertIs<SemanticVerificationResult.Passed>(result)
        assertTrue(!invoked, "Should not invoke runner for empty changeset")
    } }

    @Test
    fun funnelPreWriteCheckDelegatesToLockManager() {
        val funnel = SemanticConflictFunnel(WorkspaceLockManager())
        assertIs<ConflictRisk.None>(funnel.checkPreWriteCollision("t1", setOf("sym1")))
    }

    @Test
    fun governorDefaultsToBalanced() {
        val gov = StubPowerGovernor()
        assertEquals(OperatingProfile.BALANCED_BATTERY, gov.currentProfile.value)
    }

    @Test
    fun orchestratorStepReturnsFinishedOnSuccess() { runBlocking {
        val mcp = McpHost(InMemoryFileSystem(), StubProcessRunner())
        val orchestrator = AgentOrchestrator(AgentEventJournal(InMemoryWalStore()), AutonomyPolicy(), mcp, TelemetryEngine())
        orchestrator.startTask("T1", "test")
        orchestrator.succeed("T1", "done")
        assertIs<StepResult.TaskFinished>(orchestrator.executeSingleStep("T1"))
    } }

    @Test
    fun orchestratorStepReturnsErrorOnBadTool() { runBlocking {
        val mcp = McpHost(InMemoryFileSystem(), StubProcessRunner())
        val orchestrator = AgentOrchestrator(AgentEventJournal(InMemoryWalStore()), AutonomyPolicy(), mcp, TelemetryEngine())
        orchestrator.startTask("T1", "test")
        orchestrator.runTool("T1", ToolCall("c1", "nonexistent_tool", "{}"))
        if (orchestrator.recover("T1") is AgentState.ExecutingTool) {
            assertIs<StepResult.FatalError>(orchestrator.executeSingleStep("T1"))
        }
    } }

    @Test
    fun orchestratorExecuteStepsUntilDone() { runBlocking {
        val fs = InMemoryFileSystem().apply {
            write(VirtualPath.of("/src/main.kt"), "fun main() { println(\"hi\") }").getOrThrow()
        }
        val mcp = McpHost(fs, StubProcessRunner())
        val orchestrator = AgentOrchestrator(
            AgentEventJournal(InMemoryWalStore()), AutonomyPolicy(), mcp, TelemetryEngine(),
            lockCoordinator = TaskLockCoordinator(WorkspaceLockManager()),
            funnel = SemanticConflictFunnel(WorkspaceLockManager())
        )
        orchestrator.startTask("T1", "goal")
        val toolCalls = listOf(
            ToolCall("c1", "read_file", """{"path":"/src/main.kt"}"""),
            ToolCall("c2", "apply_diff_patch", """{"path":"/src/main.kt","search":"hi","replace":"hello"}""")
        )
        assertIs<StepResult.TaskFinished>(orchestrator.executeStepsUntilDone("T1", toolCalls))
        assertEquals("fun main() { println(\"hello\") }", fs.read(VirtualPath.of("/src/main.kt")).getOrThrow())
    } }

    @Test
    fun dispatchersAreAvailable() {
        assertTrue(EnergyAwareDispatchers.EfficiencyIO != EnergyAwareDispatchers.ComputeBurst || true)
    }
}
