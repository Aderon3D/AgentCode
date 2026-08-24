package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath

/**
 * Git backend via CLI. Used on host JVM (androidHostTest) where `git` binary exists.
 * On-device (no git binary), callers use LibGit2Backend instead.
 */
class CliGitBackend(private val processRunner: ProcessRunner) : GitBackend {

    private suspend fun git(repo: VirtualPath, vararg args: String): Result<Unit> =
        processRunner.run(listOf("git", "-C", repo.rawPath) + args.toList())
            .map { }

    override suspend fun initRepo(path: VirtualPath): Result<Unit> =
        processRunner.run(listOf("git", "init", "-q", path.rawPath)).map { }

    override suspend fun worktreeAdd(repo: VirtualPath, name: String, path: VirtualPath, baseBranch: String): Result<Unit> =
        git(repo, "worktree", "add", "-b", name, path.rawPath, baseBranch)

    override suspend fun worktreeRemove(repo: VirtualPath, name: String): Result<Unit> =
        git(repo, "worktree", "remove", "--force", name)

    override suspend fun checkout(repo: VirtualPath, branch: String): Result<Unit> =
        git(repo, "checkout", branch)

    override suspend fun mergeSquash(repo: VirtualPath, branch: String): Result<Unit> =
        git(repo, "merge", "--squash", branch)

    override suspend fun addAll(repo: VirtualPath): Result<Unit> =
        git(repo, "add", "-A")

    override suspend fun commit(repo: VirtualPath, message: String): Result<Unit> =
        git(repo, "commit", "-q", "-m", message)

    override suspend fun branchDelete(repo: VirtualPath, name: String): Result<Unit> =
        git(repo, "branch", "-D", name)

    override suspend fun branchRename(repo: VirtualPath, oldName: String, newName: String): Result<Unit> =
        git(repo, "branch", "-M", oldName, newName)

    override suspend fun sparseCheckoutSet(repo: VirtualPath, directories: List<String>): Result<Unit> =
        processRunner.run(listOf("git", "-C", repo.rawPath, "sparse-checkout", "set") + directories)
            .map { }
}
