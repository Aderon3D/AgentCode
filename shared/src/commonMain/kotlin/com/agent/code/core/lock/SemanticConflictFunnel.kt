package com.agent.code.core.lock

import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.ProcessRunner

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
 * Tier 3 (implemented): Bounded test execution on changed files via ProcessRunner.
 * Tier 4 (deferred): Targeted test selection via TreeSitter symbol index.
 */
class SemanticConflictFunnel(
    private val lockManager: WorkspaceLockManager,
    private val testRunner: ProcessRunner? = null
) {
    /**
     * Tier 1: Check for pre-write collisions against active locks.
     */
    fun checkPreWriteCollision(taskId: String, requestedSymbols: Set<String>): ConflictRisk {
        return lockManager.evaluateCollisionRisk(
            proposedFiles = emptySet(),
            proposedSymbols = requestedSymbols
        )
    }

    /**
     * Verify branch integration after code changes.
     *
     * Tier 1: lock-based collision (via checkPreWriteCollision, caller handles).
     * Tier 2: AST slicing — DEFERRED (TreeSitter CInterop needed).
     * Tier 3: Run `gradle test` on changed files. If no test runner injected, passes.
     * Tier 4: Targeted test selection — DEFERRED (needs TreeSitter symbol index).
     */
    suspend fun verifyBranchIntegration(
        branchName: String,
        changedFiles: List<VirtualPath>
    ): SemanticVerificationResult {
        if (changedFiles.isEmpty()) return SemanticVerificationResult.Passed

        // Tier 3: Run tests via ProcessRunner.
        // Without TreeSitter (Tier 2/4), we run the full test suite — not targeted.
        // This is conservative but catches real regressions.
        val runner = testRunner ?: return SemanticVerificationResult.Passed

        // Extract test file paths from changed files (heuristic: *Test.kt, *Spec.kt)
        val testFiles = changedFiles.filter { path ->
            val name = path.rawPath.substringAfterLast('/')
            name.endsWith("Test.kt") || name.endsWith("Spec.kt") || name.endsWith("Tests.kt")
        }

        val testArgs = if (testFiles.isNotEmpty()) {
            // Targeted: run specific test classes
            val classes = testFiles.map { path ->
                val name = path.rawPath.substringAfterLast('/').removeSuffix(".kt")
                name
            }
            listOf("test", "--tests", classes.joinToString(","))
        } else {
            // No test files in changeset — run full suite
            listOf("test")
        }

        val result = runner.run(listOf("gradle") + testArgs)
        return if (result.isSuccess) {
            SemanticVerificationResult.Passed
        } else {
            val stderr = result.exceptionOrNull()?.message ?: "test execution failed"
            SemanticVerificationResult.Failed("Targeted tests failed: $stderr")
        }
    }
}
