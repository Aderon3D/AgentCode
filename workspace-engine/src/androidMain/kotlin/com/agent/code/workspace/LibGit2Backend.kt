package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Git backend via libgit2 JNI. Used on Android where no `git` binary exists.
 * Each method opens/closes a git_repository* per call (stateless, matches
 * the ProcessRunner contract). Load libgit2.so on first use.
 */
class LibGit2Backend : GitBackend {

    companion object {
        init {
            System.loadLibrary("git2jni")
        }

        private var initialized = false
    }

    private external fun nativeInit(): String?
    private external fun nativeInitRepo(path: String): String?
    private external fun nativeShutdown(): String?
    private external fun nativeWorktreeAdd(repo: String, name: String, path: String, base: String): String?
    private external fun nativeWorktreeRemove(repo: String, name: String): String?
    private external fun nativeCheckout(repo: String, branch: String): String?
    private external fun nativeMergeSquash(repo: String, branch: String): String?
    private external fun nativeAddAll(repo: String): String?
    private external fun nativeCommit(repo: String, message: String): String?
    private external fun nativeBranchDelete(repo: String, name: String): String?
    private external fun nativeBranchRename(repo: String, oldName: String, newName: String): String?

    private fun ensureInit() {
        if (!initialized) {
            nativeInit()?.let { throw IllegalStateException("libgit2 init: $it") }
            initialized = true
        }
    }

    private suspend fun <T> gitOp(block: () -> T): T = withContext(Dispatchers.IO) {
        ensureInit()
        block()
    }

    private fun String?.toResult(): Result<Unit> =
        if (this == null) Result.success(Unit) else Result.failure(IllegalStateException(this))

    override suspend fun initRepo(path: VirtualPath): Result<Unit> =
        gitOp { nativeInitRepo(path.rawPath).toResult() }

    override suspend fun worktreeAdd(repo: VirtualPath, name: String, path: VirtualPath, baseBranch: String): Result<Unit> =
        gitOp { nativeWorktreeAdd(repo.rawPath, name, path.rawPath, baseBranch).toResult() }

    override suspend fun worktreeRemove(repo: VirtualPath, name: String): Result<Unit> =
        gitOp { nativeWorktreeRemove(repo.rawPath, name).toResult() }

    override suspend fun checkout(repo: VirtualPath, branch: String): Result<Unit> =
        gitOp { nativeCheckout(repo.rawPath, branch).toResult() }

    override suspend fun mergeSquash(repo: VirtualPath, branch: String): Result<Unit> =
        gitOp { nativeMergeSquash(repo.rawPath, branch).toResult() }

    override suspend fun addAll(repo: VirtualPath): Result<Unit> =
        gitOp { nativeAddAll(repo.rawPath).toResult() }

    override suspend fun commit(repo: VirtualPath, message: String): Result<Unit> =
        gitOp { nativeCommit(repo.rawPath, message).toResult() }

    override suspend fun branchDelete(repo: VirtualPath, name: String): Result<Unit> =
        gitOp { nativeBranchDelete(repo.rawPath, name).toResult() }

    override suspend fun branchRename(repo: VirtualPath, oldName: String, newName: String): Result<Unit> =
        gitOp { nativeBranchRename(repo.rawPath, oldName, newName).toResult() }
}
