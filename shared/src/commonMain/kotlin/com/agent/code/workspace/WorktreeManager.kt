package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath

class WorktreeManager(
    private val rootRepoPath: VirtualPath,
    private val processRunner: ProcessRunner
) {
    suspend fun createSparseWorktree(
        taskId: String,
        targetDirectories: List<String> = emptyList(),
        baseBranch: String = "main"
    ): Result<VirtualPath> {
        val worktreePath = rootRepoPath.resolve(".worktrees/task-$taskId")
        val branch = "agent/task-$taskId"

        processRunner.run(
            listOf("git", "-C", rootRepoPath.rawPath, "worktree", "add", "-b", branch, worktreePath.rawPath, baseBranch)
        ).onFailure { return Result.failure(it) }

        // ponytail: git sparse-checkout needs >=2.25; skip when no dirs requested
        // (also keeps this testable on older git).
        if (targetDirectories.isNotEmpty()) {
            processRunner.run(
                listOf("git", "-C", worktreePath.rawPath, "sparse-checkout", "set") + targetDirectories
            ).onFailure { return Result.failure(it) }
        }
        return Result.success(worktreePath)
    }

    suspend fun finalizeAndSquashBranch(taskId: String, targetBranch: String = "main"): Result<Unit> {
        val branch = "agent/task-$taskId"
        processRunner.run(listOf("git", "-C", rootRepoPath.rawPath, "checkout", targetBranch))
            .onFailure { return Result.failure(it) }
        processRunner.run(listOf("git", "-C", rootRepoPath.rawPath, "merge", "--squash", branch))
            .onFailure { return Result.failure(it) }
        processRunner.run(listOf("git", "-C", rootRepoPath.rawPath, "commit", "-q", "-m", "squash task $taskId"))
            .onFailure { return Result.failure(it) }
        processRunner.run(
            listOf("git", "-C", rootRepoPath.rawPath, "worktree", "remove", "--force", ".worktrees/task-$taskId")
        ).onFailure { return Result.failure(it) }
        processRunner.run(listOf("git", "-C", rootRepoPath.rawPath, "branch", "-D", branch))
            .onFailure { return Result.failure(it) }
        return Result.success(Unit)
    }
}
