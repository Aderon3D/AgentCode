package com.agent.code.opencode

import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val LOG_TAIL_LINES = 200

data class OpenCodeConfig(
    val installDir: VirtualPath = VirtualPath.of("/data/user/0/com.agent.code/files/opencode"),
    val nativeLibraryDir: String = "/data/app/~~placeholder~~/com.agent.code/lib/arm64",
    val binaryName: String = "opencode",
    val defaultPort: Int = 4096,
    val startupTimeoutMs: Long = 15_000L,
    val runTimeoutMs: Long = 120_000L,
    val releaseUrl: String = "https://github.com/Aderon3D/AgentCode/releases/download/opencode-binary"
)

sealed interface OpenCodeState {
    data object NotInstalled : OpenCodeState
    data class Installing(val progress: Float = 0f, val message: String = "") : OpenCodeState
    data object Starting : OpenCodeState
    data class Running(val port: Int, val pid: Int) : OpenCodeState
    data class Error(val message: String) : OpenCodeState
    data object Stopped : OpenCodeState
}

class OpenCodeManager(
    private val fileSystem: FileSystemProvider,
    private val processRunner: ProcessRunner,
    private val config: OpenCodeConfig = OpenCodeConfig()
) {
    private val stateMutex = Mutex()
    @Volatile
    private var _state: OpenCodeState = OpenCodeState.NotInstalled
    private var serverPort: Int = config.defaultPort

    // Pid of the currently spawned child, recorded before the state becomes
    // Running so stop() can still kill a child spawned during Starting.
    @Volatile
    private var activePid: Int = 0

    // Installed-asset fingerprint: the glibc set is baked into the sentinel
    // value, so changing the list forces a re-download instead of running a
    // half-updated loader that is missing a newly-required library.
    private val glibcFiles = listOf("libc.so.6", "libm.so.6", "libpthread.so.0", "libdl.so.2")
    private val loaderSentinel = "2:" + glibcFiles.joinToString(",")

    // Set by start(); the client sends it as x-opencode-directory so the
    // server resolves the workspace explicitly instead of relying on cwd
    // (workspaceID=undefined on device — both reference clients pass it).
    @Volatile
    var workspaceDir: String = ""
        private set

    // Path of the events file the last runPrompt() spawned into. The r24/r25
    // stall was invisible because logDump() only ever read opencode.log — the
    // run subprocess's stdout (JSON events + elfloader forensics) lands in
    // run-<taskId>.events, a file nothing read. Exposed so logDump() can show it.
    @Volatile
    var lastRunEventsPath: String? = null
        private set

    val state: OpenCodeState get() = _state

    /* Liveness, not a snapshot: once Running nobody used to watch the child,
     * so a loader kill (storm cap) or a crash left the UI on "Running" forever
     * (r14 log: state=Running in the header, "killing child" in the tail).
     * checkChild is an atomic read — cheap enough for the 500ms poll. */
    fun currentState(): OpenCodeState {
        val s = _state
        if (s is OpenCodeState.Running) {
            val code = try { PlatformOps.checkChild(s.pid) } catch (_: Exception) { 0 }
            // Only clobber if nobody moved the state since we sampled it — a
            // poll otherwise overwrites a concurrent stop() result with Error
            // (leaving the child unkilled, since stop() then early-returns).
            if (code != 0 && _state === s) _state = OpenCodeState.Error("OpenCode exited (${childStatus(code)})")
        }
        return _state
    }

    suspend fun ensureInstalled(): OpenCodeState = stateMutex.withLock {
        ensureInstalledInternal()
    }

    private suspend fun ensureInstalledInternal(): OpenCodeState {
        val binaryPath = config.installDir.resolve(config.binaryName)
        val sentinelPath = config.installDir.resolve(".loader-v2")
        val upToDate = withContext(Dispatchers.IO) {
            fileSystem.exists(binaryPath) &&
                fileSystem.read(sentinelPath).getOrNull()?.trim() == loaderSentinel
        }
        if (upToDate) {
            _state = OpenCodeState.Stopped
            return _state
        }

        _state = OpenCodeState.Installing(0f, "Downloading OpenCode...")
        return try {
            downloadAndExtract()
            _state = OpenCodeState.Stopped
            _state
        } catch (e: Exception) {
            _state = OpenCodeState.Error("Install failed: ${e.message}")
            _state
        }
    }

    private suspend fun downloadAndExtract() = withContext(Dispatchers.IO) {
        val installDir = config.installDir.rawPath
        val glibcDir = "$installDir/glibc"
        PlatformOps.createDirectories(installDir)
        PlatformOps.createDirectories(glibcDir)

        glibcFiles.forEachIndexed { index, name ->
            _state = OpenCodeState.Installing(
                (index + 1).toFloat() / (glibcFiles.size + 1),
                "Downloading $name..."
            )
            val dest = "$glibcDir/$name"
            PlatformOps.downloadFile("${config.releaseUrl}/$name", dest)
            PlatformOps.setExecutable(dest)
        }

        _state = OpenCodeState.Installing(0.8f, "Downloading opencode binary...")
        val gzFile = "$installDir/${config.binaryName}.gz"
        PlatformOps.downloadFile("${config.releaseUrl}/opencode.gz", gzFile)

        _state = OpenCodeState.Installing(0.9f, "Extracting binary...")
        val binaryDest = "$installDir/${config.binaryName}"
        PlatformOps.extractGzip(gzFile, binaryDest)
        PlatformOps.deleteFile(gzFile)
        PlatformOps.setExecutable(binaryDest)

        _state = OpenCodeState.Installing(0.95f, "Finalizing...")
        fileSystem.write(VirtualPath.of("$installDir/.loader-v2"), loaderSentinel)
            .getOrThrow()

        _state = OpenCodeState.Installing(1.0f, "Ready")
    }

    suspend fun start(
        projectDir: VirtualPath,
        port: Int = config.defaultPort
    ): OpenCodeState = stateMutex.withLock {
        if (_state is OpenCodeState.Running) return@withLock _state
        // Fail loudly instead of spawning from the placeholder nativeLibraryDir:
        // libld.so would not open and the child would _exit(127) with no clue.
        if (config.nativeLibraryDir.contains("~~placeholder~~")) {
            _state = OpenCodeState.Error("nativeLibraryDir not configured (placeholder default)")
            return@withLock _state
        }

        val installed = ensureInstalledInternal()
        if (installed is OpenCodeState.Error) return@withLock installed

        _state = OpenCodeState.Starting
        serverPort = port
        workspaceDir = projectDir.rawPath

        val installDir = config.installDir.rawPath
        val logFile = config.installDir.resolve("opencode.log")
        // Nameservers for the child's DNS shim (getaddrinfo preload, read via
        // OC_RESOLV_CONF). ponytail: hardcoded public DNS — switch to
        // ConnectivityManager device DNS if a carrier blocks outbound :53
        // (shows up as EAI failures in the opencode server log we dump).
        val resolv = withContext(Dispatchers.IO) {
            fileSystem.write(
                config.installDir.resolve("resolv.conf"),
                "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"
            )
        }
        if (resolv.isFailure) {
            _state = OpenCodeState.Error(
                "resolv.conf write failed: ${resolv.exceptionOrNull()?.message}"
            )
            return@withLock _state
        }

        val pid = try {
            withContext(Dispatchers.IO) {
                PlatformOps.spawnLdLinux(
                    loaderPath = "${config.nativeLibraryDir}/libld.so",
                    glibcDir = "$installDir/glibc",
                    binaryPath = "$installDir/${config.binaryName}",
                    workDir = projectDir.rawPath,
                    logPath = logFile.rawPath,
                    args = arrayOf("serve", "--port", port.toString())
                )
            }
        } catch (e: Exception) {
            _state = OpenCodeState.Error("Spawn failed: ${e.message}")
            return@withLock _state
        }

        if (pid <= 0) {
            val logSnippet = readLogSnippet(logFile)
            _state = OpenCodeState.Error("Process did not start (spawn=$pid)$logSnippet")
            return@withLock _state
        }

        activePid = pid

        try {
            delay(1000)
            waitForServer(port, pid)
        } catch (e: CancellationException) {
            // The coroutine is already cancelled, so the kill must run under
            // NonCancellable or it is cancelled before it executes.
            withContext(NonCancellable) {
                try {
                    processRunner.run(listOf("kill", "-9", pid.toString()))
                    delay(200)
                    PlatformOps.checkChild(pid)
                } catch (_: Exception) {}
            }
            activePid = 0
            throw e
        } catch (e: Exception) {
            // Failure path never reaches stop() (state is Error, not Running) —
            // kill the child here or a timed-out spawn keeps running, keeps
            // appending storm lines to the log we are about to read.
            try {
                processRunner.run(listOf("kill", "-9", pid.toString()))
                delay(200)
                PlatformOps.checkChild(pid)
            } catch (_: Exception) {}
            activePid = 0
            val logSnippet = readLogSnippet(logFile)
            _state = OpenCodeState.Error("Server failed to start: ${e.message}$logSnippet")
            return@withLock _state
        }

        _state = OpenCodeState.Running(port, pid)
        _state
    }

    // Per-run `opencode run --format json` (the r24 pivot). Unlike start(),
    // this spawns a transient one-shot process whose events come on stdout —
    // no port, no SSE, no long-lived server to hang under ptrace. stdout is
    // redirected to an events file by spawnLdLinux; we offset-tail it and
    // yield each JSON line (the elfloader writes its own "elfloader:" forensics
    // to the same file — skipped by the "{" filter). The flow completes when the
    // child exits AND the file stops growing.
    fun runPrompt(
        taskId: String,
        providerID: String,
        model: String,
        prompt: String,
        workspace: VirtualPath,
        sessionId: String? = null
    ): Flow<String> = flow {
        // Non-blocking install check. ensureInstalled() takes stateMutex, which
        // start() holds for its entire duration (including waitForServer, up to
        // 15s) — calling it here deadlocked the run flow against the web-UI
        // server startup (r24 log 24: feed showed "run <model> ..." but the
        // run subprocess never spawned). A plain exists() is enough: start()
        // installs before serving, and a missing binary fails at spawn time.
        val binaryPath = config.installDir.resolve(config.binaryName)
        if (!withContext(Dispatchers.IO) { fileSystem.exists(binaryPath) }) {
            error("OpenCode not installed: $binaryPath missing")
        }

        // Kill the web-UI server before spawning the run child. Both spawn via
        // nativeSpawn -> spawn_server_thread -> drain_loop -> waitpid(-1), and
        // waitpid(-1) is process-scoped: two concurrent drain threads in this
        // process steal each other's child-stop events. When the serve thread
        // reaps a run-child stop, ptrace returns ESRCH (only the *forking*
        // thread can ptrace its child, elfloader.c:1003), PTRACE_CONT also
        // fails, and the run child parks in ptrace_stop forever (r26: run
        // spawned but GETSIGINFO errno=3 then infinite hb state=t). Run mode
        // is self-contained — no port, no SSE — so the server is not needed.
        // ponytail: serializes serve vs run; concurrent runPrompts (multi-agent)
        // would still collide — add a spawn mutex if that ever ships.
        stop()

        val installDir = config.installDir.rawPath
        val eventsFile = config.installDir.resolve("run-$taskId.events")
        // Free the previous run's event file: it holds multi-MB loader forensics
        // and only the latest is ever dumped, so per-task files grew /data
        // unboundedly across repeated runs.
        lastRunEventsPath?.let { prev ->
            withContext(Dispatchers.IO) { runCatching { fileSystem.delete(VirtualPath.of(prev)) } }
        }
        lastRunEventsPath = eventsFile.rawPath
        // Blocking FS writes + ensureWorkspaceConfig must leave the collector's
        // thread — the UI collects this flow on Main.
        withContext(Dispatchers.IO) {
            // DNS shim for the child's getaddrinfo preload (same as start()) —
            // genuinely required, so a failed write aborts the run loudly.
            fileSystem.write(
                config.installDir.resolve("resolv.conf"),
                "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"
            ).getOrElse { error("resolv.conf write failed: ${it.message}") }
            // Fail-fast npm (insurance): if opencode's startup install (bun/npm)
            // still reaches for a registry package, point it at a dead loopback so
            // it ECONNREFUSEs in milliseconds instead of blocking the dns-shim on
            // registry.npmjs.org. HOME=installDir (set by the loader), so this
            // .npmrc is read. Best-effort: a reachable package is an enhancement,
            // not a dependency — the run proceeds with stock behavior (§3.7).
            fileSystem.write(
                config.installDir.resolve(".npmrc"),
                "registry=http://127.0.0.1:1/\n"
            )
            // Starter workspace config so run-mode has a config anchor from cwd.
            ensureWorkspaceConfig(workspace)
        }

        // --pure: skip external plugin loading. On device opencode resolves any
        // config-named plugins through npm, which can hang or fail with no
        // network/cache — a silent no-events stall. The starter config lists
        // none, but --pure keeps the agent path deterministic even if a user
        // adds one. Plugins are an enhancement, not a dependency (§3.7).
        val args = mutableListOf(
            "run", "--format", "json", "--auto", "--pure",
            "--dir", workspace.rawPath,
            "-m", "$providerID/$model"
        )
        if (sessionId != null) args += listOf("-s", sessionId)
        args += prompt

        val pid = withContext(Dispatchers.IO) {
            PlatformOps.spawnLdLinux(
                loaderPath = "${config.nativeLibraryDir}/libld.so",
                glibcDir = "$installDir/glibc",
                binaryPath = "$installDir/${config.binaryName}",
                workDir = workspace.rawPath,
                logPath = eventsFile.rawPath,
                args = args.toTypedArray()
            )
        }
        if (pid <= 0) {
            val snippet = readLogSnippet(eventsFile)
            error("run did not start (spawn=$pid)$snippet")
        }
        // Record the pid so stop() and the cleanup below can reap this child.
        // start() only records activePid for the serve child; without this a
        // timed-out/cancelled run leaks a ptrace-traced child, and the next
        // spawn hits the process-scoped waitpid(-1) collision (r26).
        activePid = pid

        // Offset-tail the events file. checkChild returns 0 while the child is
        // alive and non-zero once it exits (1=exit 0, 2=fail, >=100=signal).
        // The deadline is an IDLE deadline: refreshed on every emitted JSON
        // line, so a healthy multi-step run (tools + retries, minutes long) is
        // not killed, while a child that stops producing events (ptrace stall)
        // still trips it (the r15 silent-hang class).
        var deadline = PlatformOps.currentTimeMs() + config.runTimeoutMs
        var offset = 0L
        var stablePolls = 0
        var drained = false
        try {
            while (true) {
                val content = withContext(Dispatchers.IO) {
                    try { fileSystem.read(eventsFile).getOrNull().orEmpty() } catch (_: Exception) { "" }
                }
                if (content.length > offset) {
                    // Only consume whole lines: a read can land mid-line for a
                    // large tool output; emitting the fragment (then advancing
                    // past it) would silently lose the event.
                    val chunk = content.substring(offset.toInt())
                    val nl = chunk.lastIndexOf('\n')
                    if (nl >= 0) {
                        val complete = chunk.substring(0, nl)
                        offset += nl + 1
                        stablePolls = 0
                        for (line in complete.lineSequence()) {
                            val trimmed = line.trim()
                            if (trimmed.startsWith("{")) {
                                emit(trimmed)
                                deadline = PlatformOps.currentTimeMs() + config.runTimeoutMs
                            }
                        }
                    } else {
                        stablePolls++
                    }
                } else {
                    stablePolls++
                }
                val exit = withContext(Dispatchers.IO) {
                    try { PlatformOps.checkChild(pid) } catch (_: Exception) { 0 }
                }
                // Drained: child gone and no new bytes for a few consecutive polls.
                if (exit != 0 && stablePolls >= 3) {
                    // Flush a final event that had no trailing newline.
                    val content = withContext(Dispatchers.IO) {
                        try { fileSystem.read(eventsFile).getOrNull().orEmpty() } catch (_: Exception) { "" }
                    }
                    val tail = content.substring(minOf(offset.toInt(), content.length)).trim()
                    if (tail.startsWith("{")) emit(tail)
                    drained = true
                    break
                }
                if (PlatformOps.currentTimeMs() >= deadline) {
                    val snippet = readLogSnippet(eventsFile)
                    error("run timed out after ${config.runTimeoutMs}ms (exit=$exit)$snippet")
                }
                delay(150)
            }
        } finally {
            // Any exit that did not drain normally (timeout, collector
            // cancellation, spawn error) must kill the child under
            // NonCancellable — on the cancellation path the job is already
            // cancelled and the kill would be skipped otherwise (same reason
            // start() does this). Else the traced child lingers and collides
            // with the next spawn.
            if (!drained) {
                withContext(NonCancellable) {
                    try {
                        processRunner.run(listOf("kill", "-9", pid.toString()))
                        delay(200)
                        PlatformOps.checkChild(pid)
                    } catch (_: Exception) {}
                }
            }
            activePid = 0
        }
    }

    // Write a starter opencode.json into the workspace if none exists, so
    // run-mode has a config anchor from cwd. Never overwrite a user's config.
    private suspend fun ensureWorkspaceConfig(workspace: VirtualPath) {
        val cfg = workspace.resolve("opencode.json")
        if (fileSystem.exists(cfg)) return
        // No `plugin` list: declaring npm plugins makes opencode run its npm
        // install path (arborist) at startup even under `--pure` (opencode
        // #31810/#37888), which on-device blocks in the dns-shim resolving
        // registry.npmjs.org and stalls the run at `run <model> ...` (r25 log
        // 27). --pure already loads no external plugins, so the list only hurt.
        // ponytail: plugins off; if they're ever wanted offline, vendor them
        // under .opencode/plugins/ and reference the local paths (§3.7).
        val json = """
            {
              "${'$'}schema": "https://opencode.ai/config.json"
            }
        """.trimIndent()
        fileSystem.write(cfg, json)
    }

    // On-demand log export so the log can be pulled even while healthy.
    // Tails the file: the loader forensics lines are what matter, and the
    // clipboard can't take a multi-MB string.
    suspend fun logDump(maxChars: Int = 200_000): String {
        val logFile = config.installDir.resolve("opencode.log")
        // The child's own opencode log (HOME = install dir, elfloader): the
        // only place that shows whether the session/prompt/LLM call actually
        // arrived server-side — loader stdout never sees Kotlin-side traffic.
        val serverLog = VirtualPath.of("${config.installDir.rawPath}/.local/share/opencode/log/opencode.log")
        val header = buildString {
            appendLine("state=${_state}")
            appendLine("port=$serverPort log=${logFile.rawPath} at=${PlatformOps.currentTimeMs()}")
            appendLine("runEvents=${lastRunEventsPath ?: "none"}")
        }
        // Split the budget across the three sections so the combined dump
        // (auto-copied to the clipboard) stays within maxChars.
        val runBudget = maxChars / 4
        val loaderBudget = maxChars / 2
        val serverBudget = maxChars - loaderBudget - runBudget
        val body = try {
            fileSystem.readTail(logFile, loaderBudget + 1).getOrNull().orEmpty()
        } catch (e: Exception) {
            "(log read failed: ${e.message})"
        }
        val truncated = body.length > loaderBudget
        val tail = if (truncated) body.takeLast(loaderBudget) else body
        val serverBody = try {
            fileSystem.readTail(serverLog, serverBudget + 1).getOrNull().orEmpty()
        } catch (e: Exception) {
            "(server log read failed: ${e.message})"
        }
        val serverTruncated = serverBody.length > serverBudget
        val serverTail = if (serverTruncated) serverBody.takeLast(serverBudget) else serverBody
        // r24/r25: the run subprocess writes its JSON events + elfloader
        // forensics to run-<taskId>.events, NOT opencode.log. Without this the
        // agent feed could stall with a log that looked perfectly healthy.
        val runBody = lastRunEventsPath?.let { path ->
            val raw = try {
                fileSystem.read(VirtualPath.of(path)).getOrNull().orEmpty()
            } catch (e: Exception) {
                "(run events read failed: ${e.message})"
            }
            if (raw.length > runBudget) {
                "[... ${raw.length - runBudget} earlier chars trimmed ...]\n" + raw.takeLast(runBudget)
            } else raw
        }
        return buildString {
            append(header)
            if (truncated) appendLine("[... earlier log trimmed ...]")
            append(tail.ifBlank { "(log empty)" })
            appendLine()
            appendLine("=== opencode server log (${serverLog.rawPath}) ===")
            if (serverTruncated) appendLine("[... earlier server log trimmed ...]")
            append(serverTail.ifBlank { "(server log empty)" })
            appendLine()
            appendLine("=== run events (${lastRunEventsPath ?: "never spawned"}) ===")
            append((runBody ?: "(no run attempted)").ifBlank { "(run events empty)" })
        }
    }

    // Tail-only, like logDump: a failed spawn can leave a multi-MB loader log,
    // and start() runs on the UI dispatcher — reading + re-joining all of it
    // would build a giant UI state string and can ANR. Read off the main thread
    // and keep only the last LOG_TAIL_LINES lines.
    private suspend fun readLogSnippet(logFile: VirtualPath): String = withContext(Dispatchers.IO) {
        try {
            val all = fileSystem.read(logFile).getOrNull()?.lines().orEmpty()
            if (all.isNotEmpty())
                "\n\nLog (last ${minOf(all.size, LOG_TAIL_LINES)} lines):\n" +
                    all.takeLast(LOG_TAIL_LINES).joinToString("\n")
            else ""
        } catch (_: Exception) { "" }
    }

    suspend fun stop() = stateMutex.withLock {
        // Also covers a child spawned during Starting: activePid is recorded
        // before the state becomes Running.
        val st = _state
        val pid = (st as? OpenCodeState.Running)?.pid ?: activePid
        // Declare Stopped BEFORE killing. The 500ms liveness poll (currentState)
        // maps Running + a dead child to Error("OpenCode exited (signal N)"), and
        // the UI cancels its poll on Error. runPrompt() SIGTERMs the serve on
        // purpose (run pivot), so leaving Running during the ~2s kill/reap window
        // made a deliberate stop surface as a stuck "OpenCode exited (signal 15)"
        // error banner. Stopped-first makes the poll see the intent, not the corpse.
        if (st is OpenCodeState.Running) _state = OpenCodeState.Stopped
        if (pid == 0) return@withLock

        val alive = try {
            val r = processRunner.run(listOf("kill", "-0", pid.toString()))
            r.isSuccess
        } catch (_: Exception) { false }

        if (alive) {
            processRunner.run(listOf("kill", "-TERM", pid.toString()))
            delay(2000)
            val stillAlive = try {
                val r = processRunner.run(listOf("kill", "-0", pid.toString()))
                r.isSuccess
            } catch (_: Exception) { false }
            if (stillAlive) {
                processRunner.run(listOf("kill", "-9", pid.toString()))
            }
            delay(200)
            // Reap the child so it does not linger as a zombie (nativeCheck reads
            // /proc and appends a heartbeat — keep it off the caller's thread).
            try { withContext(Dispatchers.IO) { PlatformOps.checkChild(pid) } } catch (_: Exception) {}
        }
        activePid = 0
        _state = OpenCodeState.Stopped
    }

    fun baseUrl(): String = "http://127.0.0.1:$serverPort"

    private suspend fun waitForServer(port: Int, pid: Int) {
        val deadline = PlatformOps.currentTimeMs() + config.startupTimeoutMs
        var lastError = "no attempts made"
        while (PlatformOps.currentTimeMs() < deadline) {
            val child = try {
                // nativeCheck reads /proc and appends a heartbeat to the log —
                // keep it off the caller's (Main) thread.
                withContext(Dispatchers.IO) { PlatformOps.checkChild(pid) }
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) { 0 }
            if (child != 0) throw IllegalStateException("OpenCode exited during startup (${childStatus(child)})")
            try {
                // Blocking socket I/O must leave the caller's thread: start()
                // runs on the UI scope (MainActivity rememberCoroutineScope),
                // and Android throws NetworkOnMainThreadException otherwise.
                val code = withContext(Dispatchers.IO) {
                    PlatformOps.httpGet("http://127.0.0.1:$port/api/health", 1000, 1000)
                }
                if (code == 200) return
                lastError = "HTTP $code"
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { lastError = e.toString().take(160) }
            delay(100)
        }
        throw IllegalStateException("OpenCode server did not start within ${config.startupTimeoutMs}ms on port $port (last health: $lastError)")
    }

    private fun childStatus(code: Int): String = when {
        code == 1 -> "exit 0"
        code == 2 -> "exit fail"
        code >= 100 -> "signal ${code - 100}"
        code == 3 -> "stopped"
        else -> "wait error $code"
    }
}
