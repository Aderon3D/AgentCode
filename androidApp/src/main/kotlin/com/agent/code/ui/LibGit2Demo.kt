package com.agent.code.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.LibGit2Backend
import com.agent.code.workspace.WorktreeManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Device-runnable LibGit2 proof: init repo → write+commit → worktree add →
 * write+commit in worktree → squash merge back → verify file in main.
 * Exercises libgit2 JNI end-to-end.
 */
@Composable
fun LibGit2Demo(baseDir: String) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = {
            scope.launch(Dispatchers.IO) {
                val probeDir = File("$baseDir/libgit2-probe").also { it.deleteRecursively() }
                probeDir.mkdirs()
                val root = VirtualPath.of(probeDir.absolutePath)
                val git = LibGit2Backend()
                val wm = WorktreeManager(root, git)
                val log = mutableListOf<String>()

                suspend fun step(name: String, block: suspend () -> Unit) {
                    try { block(); log.add("OK $name") }
                    catch (e: Throwable) { log.add("FAIL $name: ${e.message}") }
                }

                step("initRepo") { git.initRepo(root).getOrThrow() }
                step("write file") {
                    git.addAll(root).getOrThrow()
                    val f = File(probeDir, "hello.txt")
                    f.writeText("libgit2 probe @ ${System.currentTimeMillis()}")
                    git.addAll(root).getOrThrow()
                    git.commit(root, "initial commit").getOrThrow()
                }
                step("worktreeAdd") {
                    wm.createSparseWorktree("ui-probe").getOrThrow()
                }
                step("write in worktree") {
                    val wt = File(probeDir.absolutePath, ".worktrees/task-ui-probe")
                    File(wt, "worktree.txt").writeText("written from worktree")
                    git.addAll(VirtualPath.of(wt.absolutePath)).getOrThrow()
                    git.commit(VirtualPath.of(wt.absolutePath), "worktree commit").getOrThrow()
                }
                step("squash merge") { wm.promoteToMain("ui-probe").getOrThrow() }
                step("verify merged") {
                    val merged = File(probeDir, "worktree.txt")
                    check(merged.exists() && merged.readText().contains("worktree from")) {
                        "file missing or wrong content"
                    }
                }

                // cleanup
                probeDir.deleteRecursively()

                result = log.joinToString("\n")
            }
        }) {
            Text("Run LibGit2 Probe")
        }
        result?.let {
            HorizontalDivider()
            Text("LibGit2 JNI", style = MaterialTheme.typography.titleSmall)
            Text(it)
        }
    }
}
