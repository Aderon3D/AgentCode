package com.agent.code

import com.agent.code.core.fsm.ToolCall
import com.agent.code.core.journal.AgentEvent
import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.InMemoryWalStore
import com.agent.code.core.journal.eventJson
import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.FileNode
import com.agent.code.workspace.InMemoryFileSystem
import com.agent.code.workspace.PatchOperation
import com.agent.code.workspace.ProcessConfiguration
import com.agent.code.workspace.ProcessEvent
import com.agent.code.workspace.ProcessOutput
import com.agent.code.workspace.StubProcessRunner
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class M51FileSystemTest {

    @Test
    fun applyPatchReplacesSearchBlock() = runBlocking {
        val fs = InMemoryFileSystem()
        val path = VirtualPath.of("/src/main.kt")
        fs.write(path, "fun main() { println(\"hi\") }")

        val patches = listOf(PatchOperation("println(\"hi\")", "println(\"hello world\")"))
        val result = fs.applyPatch(path, patches)
        assertTrue(result.isSuccess)
        assertEquals("fun main() { println(\"hello world\") }", fs.read(path).getOrThrow())
    }

    @Test
    fun applyPatchMultipleSearchBlocks() = runBlocking {
        val fs = InMemoryFileSystem()
        val path = VirtualPath.of("/src/main.kt")
        fs.write(path, "val a = 1\nval b = 2\nval c = 3")

        val patches = listOf(
            PatchOperation("val a = 1", "val a = 10"),
            PatchOperation("val c = 3", "val c = 30"),
        )
        assertTrue(fs.applyPatch(path, patches).isSuccess)
        assertEquals("val a = 10\nval b = 2\nval c = 30", fs.read(path).getOrThrow())
    }

    @Test
    fun applyPatchFailsOnMissingSearchBlock() { runBlocking {
        val fs = InMemoryFileSystem()
        val path = VirtualPath.of("/src/main.kt")
        fs.write(path, "fun main() {}")

        val patches = listOf(PatchOperation("nonexistent", "replacement"))
        val result = fs.applyPatch(path, patches)
        assertTrue(result.isFailure)
        assertIs<com.agent.code.workspace.FileError.PatchFailed>(result.exceptionOrNull())
    } }

    @Test
    fun applyPatchFailsOnMissingFile() { runBlocking {
        val fs = InMemoryFileSystem()
        val path = VirtualPath.of("/no/such/file.kt")
        val result = fs.applyPatch(path, listOf(PatchOperation("a", "b")))
        assertTrue(result.isFailure)
        assertIs<com.agent.code.workspace.FileError.NotFound>(result.exceptionOrNull())
    } }

    @Test
    fun walkTreeFlatDirectory() = runBlocking {
        val fs = InMemoryFileSystem()
        fs.put(VirtualPath.of("/root/a.txt"), "aaa")
        fs.put(VirtualPath.of("/root/b.txt"), "bbb")

        val tree = fs.walkTree(VirtualPath.of("/root")).getOrThrow()
        assertEquals(2, tree.children.size)
        assertTrue(tree.children.all { it is FileNode.File })
    }

    @Test
    fun walkTreeNestedDirectories() = runBlocking {
        val fs = InMemoryFileSystem()
        fs.put(VirtualPath.of("/root/a.txt"), "aaa")
        fs.put(VirtualPath.of("/root/sub/b.txt"), "bbb")
        fs.put(VirtualPath.of("/root/sub/deep/c.txt"), "ccc")

        val tree = fs.walkTree(VirtualPath.of("/root")).getOrThrow()
        val files = tree.children.filterIsInstance<FileNode.File>()
        val dirs = tree.children.filterIsInstance<FileNode.Directory>()
        assertEquals(1, files.size, "only a.txt at root")
        assertEquals(1, dirs.size, "one sub-directory")
        assertEquals("sub", dirs[0].name)

        val deep = dirs[0].children.filterIsInstance<FileNode.Directory>()
        assertEquals(1, deep.size, "one deep directory")
        assertEquals("deep", deep[0].name)
        assertEquals(1, deep[0].children.size, "one file in deep")
    }

    @Test
    fun walkTreeRespectsMaxDepth() = runBlocking {
        val fs = InMemoryFileSystem()
        fs.put(VirtualPath.of("/root/a.txt"), "aaa")
        fs.put(VirtualPath.of("/root/sub/b.txt"), "bbb")
        fs.put(VirtualPath.of("/root/sub/deep/c.txt"), "ccc")

        val tree = fs.walkTree(VirtualPath.of("/root"), maxDepth = 1).getOrThrow()
        val dirs = tree.children.filterIsInstance<FileNode.Directory>()
        assertEquals(1, dirs.size)
        assertTrue(dirs[0].children.isEmpty(), "maxDepth=1: sub children hidden")
    }

    @Test
    fun walkTreeRespectsIgnorePatterns() = runBlocking {
        val fs = InMemoryFileSystem()
        fs.put(VirtualPath.of("/root/a.txt"), "aaa")
        fs.put(VirtualPath.of("/root/.git/config"), "git")
        fs.put(VirtualPath.of("/root/build/out.jar"), "jar")
        fs.put(VirtualPath.of("/root/src/Main.kt"), "kt")

        val tree = fs.walkTree(VirtualPath.of("/root"), ignorePatterns = listOf(".git", "build")).getOrThrow()
        val allNames = collectNames(tree)
        assertTrue(".git" !in allNames)
        assertTrue("build" !in allNames)
        assertTrue("a.txt" in allNames)
        assertTrue("Main.kt" in allNames)
    }

    private fun collectNames(node: FileNode): List<String> = when (node) {
        is FileNode.File -> listOf(node.name)
        is FileNode.Directory -> listOf(node.name) + node.children.flatMap { collectNames(it) }
    }
}

class M51TokenChunkTest {

    @Test
    fun tokenChunkReceivedSerializesAndRecovers() {
        val event = AgentEvent.TokenChunkReceived(1, "T1", 1000L, "Hello ")
        val json = eventJson.encodeToString<AgentEvent>(event)
        val recovered = eventJson.decodeFromString<AgentEvent>(json)
        assertIs<AgentEvent.TokenChunkReceived>(recovered)
        assertEquals("Hello ", recovered.delta)
        assertEquals("T1", recovered.taskId)
    }

    @Test
    fun tokenChunkReceivedPersistedInWal() {
        val store = InMemoryWalStore()
        val journal = AgentEventJournal(store)
        journal.append(AgentEvent.TaskStarted(1, "T2", 0, "test"))
        journal.append(AgentEvent.TokenChunkReceived(2, "T2", 100, "chunk"))
        journal.append(AgentEvent.TokenChunkReceived(3, "T2", 200, " chunk2"))
        journal.append(AgentEvent.TaskSucceeded(4, "T2", 300, "done"))

        val events = journal.allEvents()
        val chunks = events.filterIsInstance<AgentEvent.TokenChunkReceived>()
        assertEquals(2, chunks.size)
        assertEquals("chunk", chunks[0].delta)
        assertEquals(" chunk2", chunks[1].delta)
    }
}

class M51ProcessRunnerTest {

    @Test
    fun stubProcessRunnerExecuteReturnsUnsupported() = runBlocking {
        val runner = StubProcessRunner()
        val config = ProcessConfiguration(
            command = "echo",
            args = listOf("hello"),
            workingDir = VirtualPath.of("/tmp")
        )
        val result = runner.execute(config)
        assertTrue(result.isFailure)
    }

    @Test
    fun processOutputDataClass() {
        val output = ProcessOutput(exitCode = 0, stdout = "ok", stderr = "", executionTimeMs = 42)
        assertEquals(0, output.exitCode)
        assertEquals("ok", output.stdout)
        assertEquals(42, output.executionTimeMs)
    }

    @Test
    fun processEventSubtypes() {
        val stdout = ProcessEvent.StdoutLine("hello")
        val stderr = ProcessEvent.StderrLine("error")
        val terminated = ProcessEvent.Terminated(1)
        assertIs<ProcessEvent.StdoutLine>(stdout)
        assertIs<ProcessEvent.StderrLine>(stderr)
        assertIs<ProcessEvent.Terminated>(terminated)
        assertEquals(1, terminated.exitCode)
    }

    @Test
    fun processConfigurationDefaults() {
        val config = ProcessConfiguration(
            command = "ls",
            workingDir = VirtualPath.of("/tmp")
        )
        assertEquals(emptyList(), config.args)
        assertEquals(emptyMap(), config.environmentVariables)
        assertEquals(120_000L, config.timeoutMs)
    }
}
