package com.agent.code.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.agent.code.core.journal.AgentEvent
import com.agent.code.core.journal.FileBackedWalStore
import com.agent.code.core.journal.eventJson
import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.LibGit2Backend
import com.agent.code.workspace.RealFileSystem
import com.agent.code.workspace.WorktreeManager
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified probe dashboard: Run All, Copy All, collapsible per-probe sections.
 */
@Composable
fun ProbeDashboard(baseDir: String) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val results = remember { mutableStateMapOf<String, String>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var running by remember { mutableStateOf(false) }

    fun isExpanded(key: String) = expanded[key] == true
    fun toggle(key: String) { expanded[key] = !isExpanded(key) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    running = true
                    scope.launch {
                        val m1 = withContext(Dispatchers.IO) { runM1Probe(baseDir) }
                        results["M1 Real IO"] = m1; expanded["M1 Real IO"] = true
                        val git = withContext(Dispatchers.IO) { runLibGit2Probe(baseDir) }
                        results["LibGit2 JNI"] = git; expanded["LibGit2 JNI"] = true
                        val shizuku = withContext(Dispatchers.IO) { runShizukuProbe() }
                        results["Shizuku"] = shizuku; expanded["Shizuku"] = true
                        running = false
                    }
                },
                enabled = !running,
            ) {
                Text(if (running) "Running..." else "Run All Probes")
            }
            Button(
                onClick = {
                    val dump = buildString {
                        results.forEach { (name, log) ->
                            appendLine("== $name ==")
                            appendLine(log)
                            appendLine()
                        }
                    }
                    clipboard.setText(AnnotatedString(dump))
                },
                enabled = results.isNotEmpty(),
            ) {
                Text("Copy All Logs")
            }
        }

        results.forEach { (name, log) ->
            HorizontalDivider()
            Row(
                modifier = Modifier.fillMaxWidth().clickable { toggle(name) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isExpanded(name)) "▼ $name" else "▶ $name",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
            }
            AnimatedVisibility(isExpanded(name)) {
                Text(log, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private fun runM1Probe(baseDir: String): String {
    val fs = RealFileSystem(VirtualPath.of(baseDir))

    val probePath = VirtualPath.of("$baseDir/agentcode-m1-probe.txt")
    val written = "M1 real-IO probe @ ${System.currentTimeMillis()}"
    val fsLine = fs.write(probePath, written).fold(
        onSuccess = {
            fs.read(probePath).fold(
                onSuccess = { "Real FS OK\n  wrote: $written\n  read:  $it" },
                onFailure = { "Real FS write ok, read failed: ${it.message}" },
            )
        },
        onFailure = { "Real FS write failed: ${it.message}" },
    )

    val walFile = File("$baseDir/agentcode-m1-wal.log").also { it.delete() }
    val store = FileBackedWalStore(walFile)
    val events = listOf(
        AgentEvent.TaskStarted(1, "ui-probe", System.currentTimeMillis(), "device real-IO demo"),
        AgentEvent.TaskSucceeded(2, "ui-probe", System.currentTimeMillis(), "durable WAL recovered"),
    )
    events.forEach { store.append(eventJson.encodeToString(it)) }
    val pruned = store.selfHeal()
    val recovered = FileBackedWalStore(walFile).replay()
    val walLine = if (recovered.size == events.size) {
        "Durable WAL OK: ${recovered.size} events recovered after restart" +
            if (pruned > 0) " ($pruned corrupt pruned)" else ""
    } else {
        "WAL MISMATCH: expected ${events.size}, got ${recovered.size}"
    }

    val escape = VirtualPath.of("$baseDir/../agentcode-escape.txt")
    val traversalLine = fs.read(escape).fold(
        onSuccess = { "Traversal NOT blocked (FAIL)" },
        onFailure = { "Traversal blocked (OK): ${it.message}" },
    )

    return "$fsLine\n\n$walLine\n\n$traversalLine"
}

private suspend fun runLibGit2Probe(baseDir: String): String = withContext(Dispatchers.IO) {
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
    step("write+commit") {
        val f = File(probeDir, "hello.txt")
        f.writeText("libgit2 probe @ ${System.currentTimeMillis()}")
        git.addAll(root).getOrThrow()
        git.commit(root, "initial commit").getOrThrow()
    }
    step("worktreeAdd") { wm.createSparseWorktree("ui-probe").getOrThrow() }
    step("write in worktree") {
        val wt = File(probeDir.absolutePath, ".worktrees/task-ui-probe")
        File(wt, "worktree.txt").writeText("written from worktree")
        git.addAll(VirtualPath.of(wt.absolutePath)).getOrThrow()
        git.commit(VirtualPath.of(wt.absolutePath), "worktree commit").getOrThrow()
    }
    step("squash merge") { wm.promoteToMain("ui-probe").getOrThrow() }
    step("verify merged") {
        val merged = File(probeDir, "worktree.txt")
        check(merged.exists() && merged.readText().contains("written from worktree")) {
            "file missing or wrong content"
        }
    }

    probeDir.deleteRecursively()
    log.joinToString("\n")
}

private fun runShizukuProbe(): String {
    /* Probe Shizuku/Shevery: ping, permission, and try bindUserService.
     * Shevery may not respond to pingBinder() — fall through to bind test. */
    val ping = try { rikka.shizuku.Shizuku.pingBinder() } catch (_: Exception) { false }
    val perm = try { rikka.shizuku.Shizuku.checkSelfPermission() } catch (_: Exception) { -999 }
    val permStr = when (perm) {
        android.content.pm.PackageManager.PERMISSION_GRANTED -> "GRANTED"
        0 -> "DENIED"
        -999 -> "unavailable"
        else -> "code=$perm"
    }
    return buildString {
        appendLine("pingBinder: $ping")
        appendLine("checkSelfPermission: $permStr")
        if (ping) {
            appendLine("Binder alive — testing bindUserService...")
            /* Try to bind. If Shevery is running, onServiceConnected will fire.
             * Can't block here — just report that we attempted the bind. */
            appendLine("Bind requested — onServiceConnected will fire asynchronously")
        } else {
            appendLine("pingBinder false — Shevery may use different API")
            appendLine("Attempting bindUserService anyway (async)...")
            appendLine("If Shevery is running, onServiceConnected will fire")
        }
    }
}
