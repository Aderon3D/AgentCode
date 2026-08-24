package com.agent.code

import com.agent.code.core.fsm.ToolCall
import com.agent.code.core.journal.AgentEvent
import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.FileBackedWalStore
import com.agent.code.core.path.VirtualPath
import com.agent.code.mcp.McpHost
import com.agent.code.workspace.GitProcessRunner
import com.agent.code.workspace.RealFileSystem
import com.agent.code.workspace.WorktreeManager
import java.io.File as JFile
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
        assertTrue(fs.write(path, "hello bedrock").isSuccess)
        assertTrue(fs.exists(path))
        assertEquals("hello bedrock", fs.read(path).getOrThrow())
    }

    @Test
    fun gitProcessRunnerDrivesRealRepo() = runBlocking {
        val dir = createTempDirectory("m1-git").toFile()
        val runner = GitProcessRunner()

        runner.run(listOf("git", "init", "-q", dir.absolutePath)).getOrThrow()
        runner.run(listOf("git", "-C", dir.absolutePath, "config", "user.email", "m1@agent.code")).getOrThrow()
        runner.run(listOf("git", "-C", dir.absolutePath, "config", "user.name", "M1")).getOrThrow()
        dir.resolve("f.txt").writeText("x")
        runner.run(listOf("git", "-C", dir.absolutePath, "add", "f.txt")).getOrThrow()
        runner.run(listOf("git", "-C", dir.absolutePath, "commit", "-q", "-m", "seed")).getOrThrow()

        val log = runner.run(listOf("git", "-C", dir.absolutePath, "log", "--oneline")).getOrThrow()
        assertTrue(log.contains("seed"), "expected commit in log, got: $log")
    }

    @Test
    fun fileBackedWalSurvivesRestart() {
        val file = createTempDirectory("m1-wal").resolve("wal.log").toFile()
        val taskId = "T1"

        val journal1 = AgentEventJournal(FileBackedWalStore(file))
        journal1.append(AgentEvent.TaskStarted(1, taskId, 0, "do thing"))
        journal1.append(AgentEvent.TaskSucceeded(2, taskId, 1, "done"))

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
        assertEquals(com.agent.code.core.fsm.AgentState.Idle, journal.recoverState("T9"))
    }

    @Test
    fun mcpHostEditsRealRepoThroughRealBackends() = runBlocking {
        val dir = createTempDirectory("m1-mcp").toFile()
        val runner = GitProcessRunner()
        runner.run(listOf("git", "init", "-q", dir.absolutePath)).getOrThrow()
        runner.run(listOf("git", "-C", dir.absolutePath, "config", "user.email", "m1@agent.code")).getOrThrow()
        runner.run(listOf("git", "-C", dir.absolutePath, "config", "user.name", "M1")).getOrThrow()

        val fs = RealFileSystem()
        val target = VirtualPath.of("${dir.absolutePath}/hello.txt")
        fs.write(target, "hello world").getOrThrow()
        runner.run(listOf("git", "-C", dir.absolutePath, "add", "hello.txt")).getOrThrow()
        runner.run(listOf("git", "-C", dir.absolutePath, "commit", "-q", "-m", "seed")).getOrThrow()

        val host = McpHost(fs, runner)
        val patch = """{"path":"${dir.absolutePath}/hello.txt","search":"world","replace":"bedrock"}"""
        val res = host.dispatch(ToolCall("1", "apply_diff_patch", patch))
        assertTrue(res.isSuccess, res.output)

        assertEquals("hello bedrock", fs.read(target).getOrThrow())
        val status = runner.run(listOf("git", "-C", dir.absolutePath, "status", "--porcelain")).getOrThrow()
        assertTrue(status.contains("hello.txt"), "git should see modified file: $status")
    }

    @Test
    fun worktreeManagerSquashMergesTaskBranch() = runBlocking {
        val dir = createTempDirectory("m1-wt").toFile()
        val runner = GitProcessRunner()
        val root = VirtualPath.of(dir.absolutePath)
        runner.run(listOf("git", "init", "-q", dir.absolutePath)).getOrThrow()
        runner.run(listOf("git", "-C", dir.absolutePath, "config", "user.email", "m1@agent.code")).getOrThrow()
        runner.run(listOf("git", "-C", dir.absolutePath, "config", "user.name", "M1")).getOrThrow()
        dir.resolve("base.txt").writeText("base")
        runner.run(listOf("git", "-C", dir.absolutePath, "add", "base.txt")).getOrThrow()
        runner.run(listOf("git", "-C", dir.absolutePath, "commit", "-q", "-m", "seed")).getOrThrow()
        runner.run(listOf("git", "-C", dir.absolutePath, "branch", "-M", "main")).getOrThrow()

        val wm = WorktreeManager(root, runner)
        val wt = wm.createSparseWorktree("T1", emptyList()).getOrThrow()
        JFile(wt.rawPath, "feature.txt").writeText("feature work")
        runner.run(listOf("git", "-C", wt.rawPath, "add", "feature.txt")).getOrThrow()
        runner.run(listOf("git", "-C", wt.rawPath, "commit", "-q", "-m", "add feature")).getOrThrow()

        wm.finalizeAndSquashBranch("T1").getOrThrow()

        assertTrue(JFile(dir, "feature.txt").exists(), "squash-merged feature should land on main")
        val branches = runner.run(listOf("git", "-C", root.rawPath, "branch")).getOrThrow()
        assertFalse(branches.contains("agent/task-T1"), "task branch should be removed")
    }

    @Test
    fun processRunnerDrainsLargeStderrWithoutBlocking() = runBlocking {
        val runner = GitProcessRunner()
        // Emit >64KB to stderr and exit 0; must complete (no pipe deadlock).
        val script = "i=0; while [ \$i -lt 100000 ]; do printf E >&2; i=\$((i+1)); done"
        val res = runner.run(listOf("sh", "-c", script))
        assertTrue(res.isSuccess, "stderr-heavy process should finish: ${res.exceptionOrNull()?.message}")
    }
}
