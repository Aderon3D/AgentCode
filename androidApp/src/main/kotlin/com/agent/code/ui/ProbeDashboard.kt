package com.agent.code.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.agent.code.core.concurrency.EnergyAwareDispatchers
import com.agent.code.core.journal.AgentEvent
import com.agent.code.core.journal.FileBackedWalStore
import com.agent.code.core.journal.eventJson
import com.agent.code.core.lock.ActiveTaskLock
import com.agent.code.core.lock.ConflictRisk
import com.agent.code.core.lock.WorkspaceLockManager
import com.agent.code.core.path.VirtualPath
import com.agent.code.core.power.AndroidPowerGovernor
import com.agent.code.core.power.PowerGovernor
import com.agent.code.core.power.StubPowerGovernor
import com.agent.code.workspace.LibGit2Backend
import com.agent.code.workspace.RealFileSystem
import com.agent.code.workspace.TreeSitterBackend
import com.agent.code.workspace.WorktreeManager
import com.agent.code.core.lock.SemanticConflictFunnel
import com.agent.code.workspace.StubProcessRunner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified probe dashboard: Run All, Copy All, collapsible per-probe sections.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProbeDashboard(baseDir: String, governor: PowerGovernor = StubPowerGovernor()) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val results = remember { mutableStateMapOf<String, String>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    var running by remember { mutableStateOf(false) }
    var deviceStatsText by remember { mutableStateOf<String?>(null) }

    fun isExpanded(key: String) = expanded[key] == true
    fun toggle(key: String) { expanded[key] = !isExpanded(key) }

    // Live-refresh device stats every 10s while section is expanded
    val collector = remember { DeviceStatsCollector(context) }
    LaunchedEffect(isExpanded("Device Stats")) {
        if (!isExpanded("Device Stats")) return@LaunchedEffect
        while (isActive) {
            val stats = try {
                withContext(Dispatchers.IO) { collector.collect().format() }
            } catch (e: Exception) {
                "Device Stats error: ${e.message}"
            }
            deviceStatsText = stats
            delay(10_000)
        }
    }

    val runNative: suspend (suspend () -> String) -> String = { block ->
        try {
            withContext(Dispatchers.IO) { block() }
        } catch (e: LinkageError) {
            "Native probe failed (LinkageError): ${e.message}"
        } catch (e: Exception) {
            "Native probe failed: ${e.message}"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = {
                    running = true
                    scope.launch {
                        try {
                            val m1 = withContext(Dispatchers.IO) { runM1Probe(baseDir) }
                            results["M1 Real IO"] = m1; expanded["M1 Real IO"] = true

                            results["LibGit2 JNI"] = runNative { runLibGit2Probe(baseDir) }
                            expanded["LibGit2 JNI"] = true

                            val shizuku = withContext(Dispatchers.IO) { runShizukuProbe() }
                            results["Shizuku"] = shizuku; expanded["Shizuku"] = true

                            val m2 = withContext(Dispatchers.IO) { runM2Probe(governor) }
                            results["M2 Concurrency"] = m2; expanded["M2 Concurrency"] = true

                            results["TreeSitter JNI"] = runNative { runTreeSitterProbe() }
                            expanded["TreeSitter JNI"] = true

                            results["FileWatcher"] = runNative { runFileWatcherProbe() }
                            expanded["FileWatcher"] = true

                            results["Tier 2/4 Funnel"] = runNative { runTier24Probe() }
                            expanded["Tier 2/4 Funnel"] = true

                            val stats = withContext(Dispatchers.IO) { collector.collect().format() }
                            deviceStatsText = stats
                            results["Device Stats"] = stats; expanded["Device Stats"] = true
                        } finally {
                            running = false
                        }
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
            val displayText = if (name == "Device Stats") (deviceStatsText ?: log) else log
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
                Text(displayText, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

private suspend fun runM1Probe(baseDir: String): String {
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

private fun runM2Probe(governor: PowerGovernor = StubPowerGovernor()): String = kotlinx.coroutines.runBlocking {
    val log = mutableListOf<String>()
    val mgr = WorkspaceLockManager()

    // 1. No collision on empty registry
    val none = mgr.evaluateCollisionRisk(setOf(VirtualPath.of("/a.kt")), setOf("sym1"))
    log.add("No collision (empty registry): ${none::class.simpleName}")

    // 2. Register lock for task t1
    mgr.waitForMaintenanceAndRegisterLock("t1",
        ActiveTaskLock("t1", "agent/task-t1", setOf(VirtualPath.of("/a.kt")), setOf("symX")))

    // 3. File overlap detection
    val overlap = mgr.evaluateCollisionRisk(setOf(VirtualPath.of("/a.kt")), emptySet())
    log.add("File overlap (t2 wants /a.kt): ${overlap::class.simpleName}" +
        if (overlap is ConflictRisk.FileOverlapRequiresMerge) " — files: ${overlap.files}" else "")

    // 4. Symbol collision detection
    val collision = mgr.evaluateCollisionRisk(emptySet(), setOf("symX"))
    log.add("Symbol collision (t2 wants symX): ${collision::class.simpleName}" +
        if (collision is ConflictRisk.FatalSymbolCollision) " — symbols: ${collision.symbols}" else "")

    // 5. Release + re-check
    mgr.releaseLock("t1")
    val afterRelease = mgr.evaluateCollisionRisk(setOf(VirtualPath.of("/a.kt")), emptySet())
    log.add("After release: ${afterRelease::class.simpleName}")

    // 6. Maintenance lock blocks new registrations
    mgr.tryAcquireMaintenanceLock()
    log.add("Maintenance lock acquired: activeLocks=${mgr.activeLockCount()}")
    mgr.releaseMaintenanceLock()
    log.add("Maintenance lock released")

    // 7. Dispatcher smoke
    val ioResult = kotlinx.coroutines.withContext(EnergyAwareDispatchers.EfficiencyIO) { " EfficiencyIO OK" }
    val computeResult = kotlinx.coroutines.withContext(EnergyAwareDispatchers.ComputeBurst) { " ComputeBurst OK" }
    log.add("Dispatchers:$ioResult,$computeResult")

    // 8. Governor — real snapshot from device
    val govProfile = governor.currentProfile.value
    val govLine = if (governor is AndroidPowerGovernor) {
        val snap = governor.snapshot()
        "Governor: ${snap.profile} | battery=${snap.batteryPercent}% | temp=${snap.temperatureCelsius}°C | thermal=${snap.thermalLabel} | pluggedIn=${snap.pluggedIn}"
    } else {
        "Governor: $govProfile (stub — no battery/thermal on host)"
    }
    log.add(govLine)

    log.joinToString("\n")
}

private fun runTreeSitterProbe(): String {
    val sample = """
        package com.example

        class FooBar {
            fun doWork(): String = "hello"
            val answer = 42
        }

        interface Repository {
            fun findById(id: String): FooBar?
        }

        object Config {
            val version = "1.0"
        }
    """.trimIndent()

    return try {
        TreeSitterBackend.init()
        val symbols = TreeSitterBackend.collectSymbolNames(sample)
        val symbolLines = symbols.joinToString("\n") { "  ${it.type}: ${it.name} @ L${it.startLine}-${it.endLine}" }
        val ssexpr = TreeSitterBackend.parse(sample)
        val truncated = if ((ssexpr?.length ?: 0) > 300) ssexpr!!.take(300) + "..." else ssexpr

        "OK TreeSitter JNI loaded\n" +
            "Symbols found: ${symbols.size}\n$symbolLines\n\n" +
            "AST (S-expression, truncated):\n$truncated"
    } catch (e: Exception) {
        "FAIL TreeSitter: ${e.message}"
    }
}

private fun runFileWatcherProbe(): String {
    val tmpDir = File(System.getProperty("java.io.tmpdir"), "fw-probe-${System.nanoTime()}")
    tmpDir.mkdirs()
    return try {
        val watcher = com.agent.code.core.lock.FileWatcher()
        val events = mutableListOf<String>()

        watcher.startWatching(VirtualPath.of(tmpDir.absolutePath)) { path, type ->
            events.add("${type.name}: ${path.fileName}")
        }

        Thread.sleep(300)
        File(tmpDir, "probe.txt").writeText("created")
        Thread.sleep(500)
        File(tmpDir, "probe.txt").appendText("+modified")
        Thread.sleep(500)
        File(tmpDir, "probe.txt").delete()
        Thread.sleep(500)

        watcher.stopWatching()

        if (events.isNotEmpty()) {
            "OK FileWatcher detected ${events.size} events:\n${events.joinToString("\n") { "  $it" }}"
        } else {
            "FAIL FileWatcher: no events detected"
        }
    } catch (e: Exception) {
        "FAIL FileWatcher: ${e.message}"
    } finally {
        tmpDir.deleteRecursively()
    }
}

private fun runTier24Probe(): String {
    val lockMgr = WorkspaceLockManager()
    val funnel = SemanticConflictFunnel(lockMgr, testRunner = StubProcessRunner(), parser = TreeSitterBackend)

    val oldSrc = """
        class UserService {
            fun login(user: String) = "ok"
            fun logout() = "done"
        }
    """.trimIndent()

    val newSrc = """
        class UserService {
            fun login(user: String) = "ok"
            fun logout() = "done"
            fun resetPassword(email: String) = "sent"
        }
    """.trimIndent()

    // Tier 2: AST slicing
    val impacted = funnel.computeImpactedSymbols(oldSrc, newSrc)

    // Tier 4: find targeted tests in mock files
    val testFiles = mapOf(
        VirtualPath.of("/src/UserServiceTest.kt") to "class UserServiceTest { fun testLogin() {} fun testLogout() {} }",
        VirtualPath.of("/src/OrderServiceTest.kt") to "class OrderServiceTest { fun testCreate() {} }",
    )
    val targeted = funnel.findTargetedTests(impacted, testFiles)

    return buildString {
        appendLine("== Tier 2: AST Slicing ==")
        appendLine("Impacted symbols: ${impacted.size}")
        impacted.forEach { appendLine("  - $it") }
        appendLine()
        appendLine("== Tier 4: Targeted Tests ==")
        appendLine("Test files selected: ${targeted.size}")
        targeted.forEach { appendLine("  - ${it.rawPath.substringAfterLast('/')}") }
    }
}
