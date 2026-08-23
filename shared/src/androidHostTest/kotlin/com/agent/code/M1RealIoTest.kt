package com.agent.code

import com.agent.code.core.fsm.ToolCall
import com.agent.code.core.journal.AgentEvent
import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.FileBackedWalStore
import com.agent.code.core.path.VirtualPath
import com.agent.code.mcp.McpHost
import com.agent.code.workspace.GitProcessRunner
import com.agent.code.workspace.RealFileSystem
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class M1RealIoTest {

    @Test
    fun realFileSystemReadWriteExists() {
        val dir = createTempDirectory("m1-fs").toFile()
        val fs = RealFileSystem()
        val path = VirtualPath.of("${dir.absolutePath}/sub/note.txt")

        assertFalse(fs.exists(path))
        fs.write(path, "hello bedrock")
        assertTrue(fs.exists(path))
        assertEquals("hello bedrock", fs.read(path))
    }

    @Test
    fun gitProcessRunnerDrivesRealRepo() = runBlocking {
        val dir = createTempDirectory("m1-git").toFile()
        val runner = GitProcessRunner()

        runner.run(listOf("git", "init", "-q", dir.absolutePath))
        runner.run(listOf("git", "-C", dir.absolutePath, "config", "user.email", "m1@agent.code"))
        runner.run(listOf("git", "-C", dir.absolutePath, "config", "user.name", "M1"))
        dir.resolve("f.txt").writeText("x")
        runner.run(listOf("git", "-C", dir.absolutePath, "add", "f.txt"))
        runner.run(listOf("git", "-C", dir.absolutePath, "commit", "-q", "-m", "seed"))

        val log = runner.run(listOf("git", "-C", dir.absolutePath, "log", "--oneline"))
        assertTrue(log.contains("seed"), "expected commit in log, got: $log")
    }

    @Test
    fun fileBackedWalSurvivesRestart() {
        val file = createTempDirectory("m1-wal").resolve("wal.log").toFile()
        val taskId = "T1"

        val journal1 = AgentEventJournal(FileBackedWalStore(file))
        journal1.append(AgentEvent.TaskStarted(1, taskId, 0, "do thing"))
        journal1.append(AgentEvent.TaskSucceeded(2, taskId, 1, "done"))

        // simulate process restart: fresh store over the same file
        val journal2 = AgentEventJournal(FileBackedWalStore(file))
        val recovered = journal2.recoverState(taskId)

        assertTrue(recovered is com.agent.code.core.fsm.AgentState.Success)
        assertEquals("done", recovered.summary)
    }

    @Test
    fun fileBackedWalSkipsUnparseableLines() {
        val file = createTempDirectory("m1-wal-corrupt").resolve("wal.log").toFile()
        file.writeText("not valid json\n")
        val journal = AgentEventJournal(FileBackedWalStore(file))
        // must not throw; no valid events -> Idle
        assertEquals(com.agent.code.core.fsm.AgentState.Idle, journal.recoverState("T9"))
    }

    @Test
    fun mcpHostEditsRealRepoThroughRealBackends() = runBlocking {
        val dir = createTempDirectory("m1-mcp").toFile()
        val runner = GitProcessRunner()
        runner.run(listOf("git", "init", "-q", dir.absolutePath))
        runner.run(listOf("git", "-C", dir.absolutePath, "config", "user.email", "m1@agent.code"))
        runner.run(listOf("git", "-C", dir.absolutePath, "config", "user.name", "M1"))

        val fs = RealFileSystem()
        val target = VirtualPath.of("${dir.absolutePath}/hello.txt")
        fs.write(target, "hello world")
        runner.run(listOf("git", "-C", dir.absolutePath, "add", "hello.txt"))
        runner.run(listOf("git", "-C", dir.absolutePath, "commit", "-q", "-m", "seed"))

        val host = McpHost(fs, runner)
        val patch = """{"path":"${dir.absolutePath}/hello.txt","search":"world","replace":"bedrock"}"""
        val res = host.dispatch(ToolCall("1", "apply_diff_patch", patch))
        assertTrue(res.isSuccess, res.output)

        assertEquals("hello bedrock", fs.read(target))
        val status = runner.run(listOf("git", "-C", dir.absolutePath, "status", "--porcelain"))
        assertTrue(status.contains("hello.txt"), "git should see modified file: $status")
    }
}
