package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath

class WorktreeManager(
    private val rootRepoPath: VirtualPath,
    private val gitBackend: GitBackend
) {
    suspend fun createSparseWorktree(
        taskId: String,
        targetDirectories: List<String> = emptyList(),
        baseBranch: String = "main"
    ): Result<VirtualPath> {
        val worktreePath = rootRepoPath.resolve(".worktrees/task-$taskId")
        val branch = "agent/task-$taskId"

        gitBackend.worktreeAdd(rootRepoPath, branch, worktreePath, baseBranch)
            .onFailure { return Result.failure(it) }

        if (targetDirectories.isNotEmpty()) {
            gitBackend.sparseCheckoutSet(worktreePath, targetDirectories).onFailure { originalError ->
                // ponytail: partial worktree+branch must be torn down; keep the
                // original failure even if cleanup itself fails
                gitBackend.worktreeRemove(rootRepoPath, ".worktrees/task-$taskId")
                gitBackend.branchDelete(rootRepoPath, branch)
                return Result.failure(originalError)
            }
        }
        return Result.success(worktreePath)
    }

    suspend fun finalizeAndSquashBranch(taskId: String, targetBranch: String = "main"): Result<Unit> {
        val branch = "agent/task-$taskId"
        gitBackend.checkout(rootRepoPath, targetBranch)
            .onFailure { return Result.failure(it) }
        gitBackend.mergeSquash(rootRepoPath, branch)
            .onFailure { return Result.failure(it) }
        gitBackend.addAll(rootRepoPath)
            .onFailure { return Result.failure(it) }
        gitBackend.commit(rootRepoPath, "squash task $taskId")
            .onFailure { return Result.failure(it) }
        gitBackend.worktreeRemove(rootRepoPath, ".worktrees/task-$taskId")
            .onFailure { return Result.failure(it) }
        gitBackend.branchDelete(rootRepoPath, branch)
            .onFailure { return Result.failure(it) }
        return Result.success(Unit)
    }

    // ponytail: module promotion == squash-merge a finished task worktree into
    // main. (Opening the resulting commit as a PR is out of app scope.)
    suspend fun promoteToMain(taskId: String): Result<Unit> = finalizeAndSquashBranch(taskId, "main")
}
