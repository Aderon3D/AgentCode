package com.agent.code.core.lock

import com.agent.code.core.path.VirtualPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TaskLockCoordinator(
    private val lockManager: WorkspaceLockManager
) {
    private val symbolWaiters = mutableMapOf<String, MutableList<CompletableDeferred<Unit>>>()
    private val coordinatorMutex = Mutex()

    suspend fun acquireTaskExecutionPermit(
        taskId: String,
        branchName: String,
        files: Set<VirtualPath>,
        symbols: Set<String>
    ): ExecutionPermit {
        val requestedLock = ActiveTaskLock(taskId, branchName, files, symbols)

        while (true) {
            val registrationResult = lockManager.waitForMaintenanceAndRegisterLock(taskId, requestedLock)

            when (registrationResult) {
                is ConflictRisk.None -> {
                    return ExecutionPermit(taskId = taskId, requiresAst3WayMerge = false)
                }
                is ConflictRisk.FileOverlapRequiresMerge -> {
                    return ExecutionPermit(
                        taskId = taskId,
                        requiresAst3WayMerge = true,
                        overlappingFiles = registrationResult.files
                    )
                }
                is ConflictRisk.FatalSymbolCollision -> {
                    val waitDeferred = CompletableDeferred<Unit>()
                    coordinatorMutex.withLock {
                        for (symbol in registrationResult.symbols) {
                            symbolWaiters.getOrPut(symbol) { mutableListOf() }.add(waitDeferred)
                        }
                    }
                    waitDeferred.await()
                }
            }
        }
    }

    suspend fun releaseTaskExecutionPermit(taskId: String, symbols: Set<String>) {
        lockManager.releaseLock(taskId)
        coordinatorMutex.withLock {
            for (symbol in symbols) {
                val waiters = symbolWaiters.remove(symbol)
                waiters?.forEach { it.complete(Unit) }
            }
        }
    }
}
