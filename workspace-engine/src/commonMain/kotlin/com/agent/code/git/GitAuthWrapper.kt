package com.agent.code.git

import com.agent.code.core.path.VirtualPath
import com.agent.code.core.security.SecureVault
import com.agent.code.workspace.FileSystemProvider
import kotlin.random.Random

// §12.1 Development_Doc.md — non-interactive Git auth via GIT_ASKPASS.
// Stages the PAT as an ephemeral file + askpass script, hands the script path
// to the caller, then deletes both in `finally` so the secret never lingers.
class GitAuthWrapper(
    private val credentialsVault: SecureVault,
    private val fs: FileSystemProvider
) {
    suspend fun <T> withEphemeralCredentials(block: suspend (askPassPath: VirtualPath) -> T): T {
        val pat = credentialsVault.getKey("GITHUB_PAT")
            ?: throw SecurityException("No GITHUB_PAT found in SecureVault")
        val runId = randomId()
        val secretPath = VirtualPath.of("/data/data/com.agent.code/cache/.git-secret-$runId.txt")
        val scriptPath = VirtualPath.of("/data/data/com.agent.code/cache/git-askpass-$runId.sh")

        val scriptContent = """
            #!/bin/sh
            case "$1" in
                *Username*) echo "oauth2" ;;
                *Password*) cat '${secretPath.rawPath}' ;;
            esac
        """.trimIndent()

        fs.write(secretPath, pat)
            .onFailure { throw SecurityException("Failed to stage ephemeral secret", it) }
        fs.write(scriptPath, scriptContent)
            .onFailure { throw SecurityException("Failed to stage askpass script", it) }

        return try {
            block(scriptPath)
        } finally {
            fs.delete(scriptPath)
            fs.delete(secretPath)
        }
    }

    private fun randomId(): String =
        (1..8).map { "0123456789abcdef"[Random.nextInt(16)] }.joinToString("")
}
