package com.agent.code.core.fsm

import com.agent.code.core.lock.SemanticConflictFunnel
import com.agent.code.core.lock.SemanticVerificationResult
import com.agent.code.core.lock.TaskLockCoordinator
import com.agent.code.core.path.VirtualPath
import com.agent.code.core.journal.AgentEvent
import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.LogEntry
import com.agent.code.core.journal.TelemetryEngine
import com.agent.code.core.policy.AutonomyPolicy
import com.agent.code.core.power.PowerGovernor
import com.agent.code.core.power.StubPowerGovernor
import com.agent.code.core.tools.ToolResult
import com.agent.code.mcp.McpHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

sealed interface StepResult {
    object TaskFinished : StepResult
    object StepCompletedMoreWorkPending : StepResult
    data class BlockedOnApproval(val approvalId: String) : StepResult
    data class FatalError(val reason: String) : StepResult
}

class AgentOrchestrator(
    private val journal: AgentEventJournal,
    private val policy: AutonomyPolicy,
    private val mcp: McpHost,
    private val telemetry: TelemetryEngine,
    private val lockCoordinator: TaskLockCoordinator? = null,
    private val funnel: SemanticConflictFunnel? = null,
    private val governor: PowerGovernor = StubPowerGovernor()
) {
    private var seq = 0L
    private val seqMutex = Mutex()
    private suspend fun nextId(): Long = seqMutex.withLock { ++seq }
    private fun now(): Long = Clock.System.now().toEpochMilliseconds()

    suspend fun startTask(taskId: String, goal: String) {
        journal.append(AgentEvent.TaskStarted(nextId(), taskId, now(), goal))
        telemetry.emit(LogEntry.AgentThought(now(), "Planning: $goal"))
    }

    suspend fun runTool(taskId: String, toolCall: ToolCall): ToolResult {
        journal.append(AgentEvent.ToolExecutionRequested(nextId(), taskId, now(), toolCall))
        val result = mcp.dispatch(toolCall)
        journal.append(AgentEvent.ToolExecutionFinished(nextId(), taskId, now(), result))
        if (result.isSuccess && toolCall.toolName == "apply_diff_patch") {
            val patchedPath = pathFromArgs(toolCall.argumentsJson)
            if (patchedPath != null) {
                journal.append(AgentEvent.FilePatchApplied(nextId(), taskId, now(), patchedPath, toolCall.argumentsJson))
            }
        }
        telemetry.emit(LogEntry.ToolCallStarted(now(), toolCall.toolName, toolCall.argumentsJson))
        return result
    }

    suspend fun succeed(taskId: String, summary: String) {
        journal.append(AgentEvent.TaskSucceeded(nextId(), taskId, now(), summary))
        telemetry.emit(LogEntry.AgentThought(now(), "Success: $summary"))
    }

    fun recover(taskId: String): AgentState = journal.recoverState(taskId)

    // --- Step-based execution API (M2) ---

    suspend fun executeSingleStep(taskId: String): StepResult {
        val currentState = journal.recoverState(taskId)
        return when (currentState) {
            is AgentState.Success -> StepResult.TaskFinished
            is AgentState.Error -> StepResult.FatalError(currentState.fatalCause)
            is AgentState.AwaitingHumanApproval -> StepResult.BlockedOnApproval(currentState.toolCall.id)
            is AgentState.Planning -> processPlanningStep(taskId, currentState)
            is AgentState.ExecutingTool -> processToolExecutionStep(taskId, currentState)
            is AgentState.Verifying -> processVerificationStep(taskId)
            is AgentState.Reflecting -> processReflectionStep(taskId, currentState)
            is AgentState.Idle -> StepResult.TaskFinished
        }
    }

    /**
     * Execute one step with pacing driven by [governor].
     * Reads the current [OperatingProfile] from governor and applies the
     * appropriate delay after the step completes.
     */
    suspend fun executeStepPaced(taskId: String): StepResult {
        val result = executeSingleStep(taskId)
        when (governor.currentProfile.value) {
            OperatingProfile.TURBO_PLUGGED -> { /* no delay */ }
            OperatingProfile.BALANCED_BATTERY -> delay(50L)
            OperatingProfile.ECO_PRESERVATION -> delay(500L)
        }
        return result
    }

    suspend fun executeStepsUntilDone(
        taskId: String,
        toolCalls: List<ToolCall>,
        maxSteps: Int = 50
    ): StepResult {
        var toolIndex = 0
        var steps = 0
        while (steps < maxSteps) {
            val state = recover(taskId)
            val profile = governor.currentProfile.value
            when (state) {
                is AgentState.Success -> return StepResult.TaskFinished
                is AgentState.Error -> return StepResult.FatalError(state.fatalCause)
                is AgentState.Idle -> return StepResult.TaskFinished
                is AgentState.Planning, is AgentState.ExecutingTool -> {
                    if (toolIndex < toolCalls.size) {
                        val targetPaths = pathFromArgs(toolCalls[toolIndex].argumentsJson)
                            ?.let { setOf(it) } ?: emptySet()
                        val permit = lockCoordinator?.acquireTaskExecutionPermit(
                            taskId, "agent/task-$taskId",
                            targetPaths, emptySet()
                        )
                        try {
                            runTool(taskId, toolCalls[toolIndex])
                            toolIndex++
                        } finally {
                            if (permit != null) lockCoordinator.releaseTaskExecutionPermit(taskId, emptySet())
                        }
                    } else if (state is AgentState.ExecutingTool) {
                        return StepResult.FatalError("ExecutingTool persisted with no pending tool calls — unrecoverable state")
                    } else {
                        return StepResult.TaskFinished
                    }
                }
                is AgentState.Verifying -> {
                    val verification = funnel?.verifyBranchIntegration("agent/task-$taskId", emptyList())
                    if (verification is SemanticVerificationResult.Failed) {
                        return StepResult.FatalError(verification.reason)
                    }
                    succeed(taskId, "completed")
                }
                is AgentState.Reflecting -> {
                    if (state.attempt >= state.maxAttempts) {
                        return StepResult.FatalError("Max retry attempts (${state.maxAttempts}) exhausted: ${state.errorTrace}")
                    }
                }
                is AgentState.AwaitingHumanApproval -> {
                    return StepResult.BlockedOnApproval(state.toolCall.id)
                }
            }
            steps++
            when (profile) {
                OperatingProfile.TURBO_PLUGGED -> { /* no delay */ }
                OperatingProfile.BALANCED_BATTERY -> delay(50L)
                OperatingProfile.ECO_PRESERVATION -> delay(500L)
            }
        }
        return StepResult.FatalError("Max steps ($maxSteps) exhausted without completion")
    }

    // --- Private step handlers ---

    private fun processPlanningStep(taskId: String, state: AgentState.Planning): StepResult {
        // DEFERRED: LLM integration will generate tool calls here.
        return StepResult.StepCompletedMoreWorkPending
    }

    private suspend fun processToolExecutionStep(taskId: String, state: AgentState.ExecutingTool): StepResult {
        val result = runTool(taskId, state.toolCall)
        return if (result.isSuccess) {
            StepResult.StepCompletedMoreWorkPending
        } else {
            StepResult.FatalError("Tool ${state.toolCall.toolName} failed: ${result.output}")
        }
    }

    private suspend fun processVerificationStep(taskId: String): StepResult {
        val verification = funnel?.verifyBranchIntegration("agent/task-$taskId", emptyList())
        return when (verification) {
            is SemanticVerificationResult.Failed ->
                StepResult.FatalError("Verification failed: ${verification.reason}")
            is SemanticVerificationResult.EscalationRequired ->
                StepResult.FatalError("Escalation required: ${verification.ambiguousInvariants}")
            else -> StepResult.StepCompletedMoreWorkPending
        }
    }

    private fun processReflectionStep(taskId: String, state: AgentState.Reflecting): StepResult {
        return if (state.attempt >= state.maxAttempts) {
            StepResult.FatalError("Max retry attempts (${state.maxAttempts}) exhausted: ${state.errorTrace}")
        } else {
            StepResult.StepCompletedMoreWorkPending
        }
    }

    private fun pathFromArgs(argsJson: String): VirtualPath? {
        return try {
            val element = Json.parseToJsonElement(argsJson)
            val path = element.jsonObject["path"]?.jsonPrimitive?.contentOrNull ?: return null
            VirtualPath.of(path)
        } catch (_: Exception) {
            null
        }
    }
}
