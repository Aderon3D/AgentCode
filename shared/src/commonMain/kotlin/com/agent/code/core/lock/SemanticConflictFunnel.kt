package com.agent.code.core.lock

import com.agent.code.core.path.VirtualPath

sealed interface SemanticVerificationResult {
    object Passed : SemanticVerificationResult
    data class Failed(val reason: String) : SemanticVerificationResult
    data class EscalationRequired(val ambiguousInvariants: List<String>) : SemanticVerificationResult
}

/**
 * 4-Tier Semantic Conflict Funnel (§4 Development_Doc.md).
 *
 * Tier 1 (implemented): Pre-write collision check via WorkspaceLockManager.
 * Tier 2 (deferred): Backward AST Slicing — requires TreeSitter CInterop.
 * Tier 3 (deferred): Bounded Mutation Testing on impact set — requires TreeSitter + test runner.
 * Tier 4 (deferred): Targeted test execution — requires TreeSitter symbol index.
 *
 * Tiers 2-4 are blocked on TreeSitter native integration (future milestone).
 * This stub provides the interface contract and delegates Tier 1 to the lock manager.
 */
class SemanticConflictFunnel(
    private val lockManager: WorkspaceLockManager
) {
    /**
     * Tier 1: Check for pre-write collisions against active locks.
     * Returns ConflictRisk.None if no contention, or the collision type.
     */
    fun checkPreWriteCollision(taskId: String, requestedSymbols: Set<String>): ConflictRisk {
        return lockManager.evaluateCollisionRisk(
            proposedFiles = emptySet(),
            proposedSymbols = requestedSymbols
        )
    }

    /**
     * Tier 2-4: Verify branch integration after code changes.
     *
     * DEFERRED — requires TreeSitter CInterop for:
     * - Tier 2: computeImpactedSymbolSlice(changedFiles) — AST-based change impact analysis
     * - Tier 3: findTargetedTestsForSlice(impactedSymbols) — bounded mutation testing
     * - Tier 4: execute targeted tests via ProcessRunner
     *
     * Current stub: returns Passed (no-op) until TreeSitter is available.
     * When TreeSitter lands, this will:
     *   1. Parse changed files into AST
     *   2. Compute impacted symbol slice (callers, dependents)
     *   3. Find targeted tests for the slice
     *   4. Execute those tests via ProcessRunner
     *   5. Return Passed/Failed/EscalationRequired
     */
    suspend fun verifyBranchIntegration(
        branchName: String,
        changedFiles: List<VirtualPath>
    ): SemanticVerificationResult {
        // DEFERRED: TreeSitter CInterop not yet available.
        // When ready, replace with:
        //   val impactedSymbols = treeSitter.computeImpactedSymbolSlice(changedFiles)
        //   val targetedTests = treeSitter.findTargetedTestsForSlice(impactedSymbols)
        //   val testResult = testRunner.execute(ProcessConfiguration("gradle", listOf("test", ...)))
        //   return if (testResult.isSuccess) Passed else Failed(testResult.stderr)
        return SemanticVerificationResult.Passed
    }
}
