package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath

/**
 * Git operations needed by WorktreeManager.
 * On-device: LibGit2Backend (JNI to libgit2.so).
 * On-host:   CliGitBackend (shells out to git CLI).
 */
interface GitBackend {
    suspend fun initRepo(path: VirtualPath): Result<Unit> = Result.success(Unit)
    suspend fun worktreeAdd(repo: VirtualPath, name: String, path: VirtualPath, baseBranch: String): Result<Unit>
    suspend fun worktreeRemove(repo: VirtualPath, name: String): Result<Unit>
    suspend fun checkout(repo: VirtualPath, branch: String): Result<Unit>
    suspend fun mergeSquash(repo: VirtualPath, branch: String): Result<Unit>
    suspend fun addAll(repo: VirtualPath): Result<Unit>
    suspend fun commit(repo: VirtualPath, message: String): Result<Unit>
    suspend fun branchDelete(repo: VirtualPath, name: String): Result<Unit>
    suspend fun branchRename(repo: VirtualPath, oldName: String, newName: String): Result<Unit>
    // ponytail: sparse-checkout is CLI-only; libgit2 doesn't expose it cleanly.
    // Default fails for non-empty requests so callers get an accurate result;
    // CliGitBackend overrides with a real implementation.
    suspend fun sparseCheckoutSet(repo: VirtualPath, directories: List<String>): Result<Unit> =
        if (directories.isEmpty()) Result.success(Unit)
        else Result.failure(UnsupportedOperationException("sparse-checkout not supported by this backend"))

    companion object {
        fun create(processRunner: ProcessRunner): GitBackend = CliGitBackend(processRunner)
    }
}
