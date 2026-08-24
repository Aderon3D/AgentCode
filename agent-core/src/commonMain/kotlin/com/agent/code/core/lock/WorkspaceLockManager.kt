package com.agent.code.core.lock

import com.agent.code.core.path.VirtualPath
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WorkspaceLockManager {
    private val stateMutex = Mutex()
    private val activeLocks = mutableMapOf<String, ActiveTaskLock>()
    private var maintenanceDeferred: CompletableDeferred<Unit>? = null

    suspend fun waitForMaintenanceAndRegisterLock(
        taskId: String,
        lock: ActiveTaskLock
    ): ConflictRisk {
        while (true) {
            val deferred = stateMutex.withLock {
                if (maintenanceDeferred == null) {
                    val risk = evaluateCollisionRisk(lock.lockedFiles, lock.lockedSymbolUuids)
                    if (risk is ConflictRisk.None || risk is ConflictRisk.FileOverlapRequiresMerge) {
                        activeLocks[taskId] = lock
                    }
                    return risk
                }
                maintenanceDeferred!!
            }
            deferred.await()
        }
    }

    fun releaseLock(taskId: String) {
        activeLocks.remove(taskId)
    }

    suspend fun tryAcquireMaintenanceLock(): Boolean = stateMutex.withLock {
        if (activeLocks.isNotEmpty() || maintenanceDeferred != null) return false
        maintenanceDeferred = CompletableDeferred()
        return true
    }

    suspend fun releaseMaintenanceLock() = stateMutex.withLock {
        maintenanceDeferred?.complete(Unit)
        maintenanceDeferred = null
    }

    fun activeLockCount(): Int = activeLocks.size

    fun activeTaskIds(): Set<String> = activeLocks.keys.toSet()

    fun evaluateCollisionRisk(
        proposedFiles: Set<VirtualPath>,
        proposedSymbols: Set<String>
    ): ConflictRisk {
        val existingSymbols = activeLocks.values.flatMap { it.lockedSymbolUuids }.toSet()
        val overlappingSymbols = existingSymbols.intersect(proposedSymbols)
        if (overlappingSymbols.isNotEmpty()) {
            return ConflictRisk.FatalSymbolCollision(overlappingSymbols)
        }

        val existingFiles = activeLocks.values.flatMap { it.lockedFiles }.toSet()
        val overlappingFiles = existingFiles.intersect(proposedFiles)
        if (overlappingFiles.isNotEmpty()) {
            return ConflictRisk.FileOverlapRequiresMerge(overlappingFiles)
        }

        return ConflictRisk.None
    }
}
