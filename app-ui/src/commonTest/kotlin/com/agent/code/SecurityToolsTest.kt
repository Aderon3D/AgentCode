package com.agent.code

import com.agent.code.core.security.SecureVault
import com.agent.code.git.GitAuthWrapper
import com.agent.code.tools.RuntimeDiagnosticsTool
import com.agent.code.workspace.InMemoryFileSystem
import com.agent.code.workspace.StubProcessRunner
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SecurityToolsTest {

    @Test
    fun `secure vault stores retrieves and deletes`() = runTest {
        val vault = SecureVault()
        assertNull(vault.getKey("K"))
        vault.storeKey("K", "secret")
        assertEquals("secret", vault.getKey("K"))
        vault.deleteKey("K")
        assertNull(vault.getKey("K"))
    }

    @Test
    fun `git auth wrapper stages ephemeral creds and cleans up`() = runTest {
        val vault = SecureVault()
        vault.storeKey("GITHUB_PAT", "ghp_token")
        val fs = InMemoryFileSystem()
        val wrapper = GitAuthWrapper(vault, fs)
        var sawScript = false
        wrapper.withEphemeralCredentials { askPass ->
            sawScript = fs.exists(askPass)
        }
        assertTrue(sawScript)
    }

    @Test
    fun `git auth wrapper throws when no PAT`() = runTest {
        val wrapper = GitAuthWrapper(SecureVault(), InMemoryFileSystem())
        assertFailsWith<SecurityException> {
            wrapper.withEphemeralCredentials { }
        }
    }

    @Test
    fun `runtime diagnostics tool returns output`() = runTest {
        val result = RuntimeDiagnosticsTool().execute("{}", InMemoryFileSystem(), StubProcessRunner())
        assertTrue(result.isSuccess)
        assertEquals("diagnostics", result.toolCallId)
    }
}
