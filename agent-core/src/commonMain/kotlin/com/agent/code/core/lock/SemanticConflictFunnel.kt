package com.agent.code.core.lock

import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.KotlinParser
import com.agent.code.workspace.ProcessRunner

sealed interface SemanticVerificationResult {
    object Passed : SemanticVerificationResult
    data class Failed(val reason: String) : SemanticVerificationResult
    data class EscalationRequired(val ambiguousInvariants: List<String>) : SemanticVerificationResult
}

/**
 * 4-Tier Semantic Conflict Funnel (§4 Development_Doc.md).
 *
 * Tier 1: Pre-write collision check via WorkspaceLockManager.
 * Tier 2: Backward AST Slicing — compute impacted symbols from changed files via KotlinParser.
 * Tier 3: Bounded test execution on changed files via ProcessRunner.
 * Tier 4: Targeted test selection — map changed symbols to test files via symbol index.
 */
class SemanticConflictFunnel(
    private val lockManager: WorkspaceLockManager,
    private val testRunner: ProcessRunner? = null,
    private val parser: KotlinParser? = null,
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
     * Tier 2: Compute impacted symbol slice from changed file contents.
     * Given old + new source, returns symbols whose definitions changed.
     */
    fun computeImpactedSymbols(
        oldSource: String,
        newSource: String,
    ): Set<String> {
        val p = parser ?: return emptySet()
        val oldSymbols = p.collectSymbolNames(oldSource).associateBy { it.name }
        val newSymbols = p.collectSymbolNames(newSource).associateBy { it.name }

        val impacted = mutableSetOf<String>()

        // Symbols that were added or removed
        impacted.addAll(newSymbols.keys.filter { it !in oldSymbols })
        impacted.addAll(oldSymbols.keys.filter { it !in newSymbols })

        // Symbols whose line ranges changed (body modified)
        for ((name, newSym) in newSymbols) {
            val oldSym = oldSymbols[name] ?: continue
            if (oldSym.startLine != newSym.startLine || oldSym.endLine != newSym.endLine) {
                impacted.add(name)
            }
        }

        return impacted
    }

    /**
     * Tier 4: Find test files that reference the given symbols.
     * Scans fileContents for test files that import or reference the impacted symbols.
     */
    fun findTargetedTests(
        impactedSymbols: Set<String>,
        fileContents: Map<VirtualPath, String>,
    ): Set<VirtualPath> {
        if (impactedSymbols.isEmpty()) return emptySet()
        val p = parser ?: return emptySet()

        return fileContents.filter { (path, content) ->
            val name = path.rawPath.substringAfterLast('/')
            val isTest = name.endsWith("Test.kt") || name.endsWith("Spec.kt") || name.endsWith("Tests.kt")
            if (!isTest) return@filter false

            // Check if test file references any impacted symbol
            val testSymbols = p.collectSymbolNames(content).map { it.name }.toSet()
            testSymbols.intersect(impactedSymbols).isNotEmpty() ||
                // Fallback: text search for symbol names in test source
                impactedSymbols.any { sym -> content.contains(sym) }
        }.keys
    }

    /**
     * Verify branch integration after code changes.
     *
     * Tier 1: lock-based collision (via checkPreWriteCollision, caller handles).
     * Tier 2: AST slicing — compute impacted symbols from changed file contents.
     * Tier 3: Run `gradle test` on changed files. If no test runner injected, passes.
     * Tier 4: Targeted test selection via symbol index.
     */
    suspend fun verifyBranchIntegration(
        branchName: String,
        changedFiles: List<VirtualPath>,
        fileContents: Map<VirtualPath, String> = emptyMap(),
        oldContents: Map<VirtualPath, String> = emptyMap(),
    ): SemanticVerificationResult {
        if (changedFiles.isEmpty()) return SemanticVerificationResult.Passed

        // Tier 2: Compute impacted symbols via AST diff
        val impactedSymbols = mutableSetOf<String>()
        if (parser != null) {
            for (path in changedFiles) {
                val newSrc = fileContents[path] ?: continue
                val oldSrc = oldContents[path]
                if (oldSrc != null) {
                    impactedSymbols.addAll(computeImpactedSymbols(oldSrc, newSrc))
                } else {
                    // New file — all symbols are impacted
                    impactedSymbols.addAll(parser.collectSymbolNames(newSrc).map { it.name })
                }
            }
        }

        // Tier 4: Find targeted tests via symbol index
        val targetedTests = if (impactedSymbols.isNotEmpty() && parser != null) {
            findTargetedTests(impactedSymbols, fileContents)
        } else emptySet()

        // Tier 3: Run tests
        val runner = testRunner ?: return SemanticVerificationResult.Passed

        val testClasses = buildList {
            // Add explicitly changed test files
            addAll(changedFiles.filter { path ->
                val name = path.rawPath.substringAfterLast('/')
                name.endsWith("Test.kt") || name.endsWith("Spec.kt") || name.endsWith("Tests.kt")
            }.map { it.rawPath.substringAfterLast('/').removeSuffix(".kt") })

            // Add Tier 4 targeted tests
            addAll(targetedTests.map { it.rawPath.substringAfterLast('/').removeSuffix(".kt") })
        }.distinct()

        val testArgs = if (testClasses.isNotEmpty()) {
            listOf("test", "--tests", testClasses.joinToString(","))
        } else {
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
