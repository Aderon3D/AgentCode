package com.agent.code.opencode

import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class OpenCodeConfig(
    val installDir: VirtualPath = VirtualPath.of("/data/user/0/com.agent.code/files/opencode"),
    val nativeLibraryDir: String = "/data/app/~~placeholder~~/com.agent.code/lib/arm64",
    val binaryName: String = "opencode",
    val defaultPort: Int = 4096,
    val startupTimeoutMs: Long = 15_000L,
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

    // Installed-asset fingerprint: the glibc set is baked into the sentinel
    // value, so changing the list forces a re-download instead of running a
    // half-updated loader that is missing a newly-required library.
    private val glibcFiles = listOf("libc.so.6", "libm.so.6", "libpthread.so.0", "libdl.so.2")
    private val loaderSentinel = "2:" + glibcFiles.joinToString(",")

    val state: OpenCodeState get() = _state

    /* Liveness, not a snapshot: once Running nobody used to watch the child,
     * so a loader kill (storm cap) or a crash left the UI on "Running" forever
     * (r14 log: state=Running in the header, "killing child" in the tail).
     * checkChild is an atomic read — cheap enough for the 500ms poll. */
    fun currentState(): OpenCodeState {
        val s = _state
        if (s is OpenCodeState.Running) {
            val code = try { PlatformOps.checkChild(s.pid) } catch (_: Exception) { 0 }
            if (code != 0) _state = OpenCodeState.Error("OpenCode exited (${childStatus(code)})")
        }
        return _state
    }

    suspend fun ensureInstalled(): OpenCodeState = stateMutex.withLock {
        ensureInstalledInternal()
    }

    private suspend fun ensureInstalledInternal(): OpenCodeState {
        val binaryPath = config.installDir.resolve(config.binaryName)
        val sentinelPath = config.installDir.resolve(".loader-v2")
        val upToDate = fileSystem.exists(binaryPath) &&
            fileSystem.read(sentinelPath).getOrNull()?.trim() == loaderSentinel
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

        val installed = ensureInstalledInternal()
        if (installed is OpenCodeState.Error) return@withLock installed

        _state = OpenCodeState.Starting
        serverPort = port

        val installDir = config.installDir.rawPath
        val logFile = config.installDir.resolve("opencode.log")
        // Nameservers for the child's DNS shim (getaddrinfo preload, read via
        // OC_RESOLV_CONF). ponytail: hardcoded public DNS — switch to
        // ConnectivityManager device DNS if a carrier blocks outbound :53
        // (shows up as EAI failures in the opencode server log we dump).
        fileSystem.write(
            config.installDir.resolve("resolv.conf"),
            "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"
        )

        val pid = try {
            PlatformOps.spawnLdLinux(
                loaderPath = "${config.nativeLibraryDir}/libld.so",
                glibcDir = "$installDir/glibc",
                binaryPath = "$installDir/${config.binaryName}",
                workDir = projectDir.rawPath,
                logPath = logFile.rawPath,
                args = arrayOf("serve", "--port", port.toString())
            )
        } catch (e: Exception) {
            _state = OpenCodeState.Error("Spawn failed: ${e.message}")
            return@withLock _state
        }

        if (pid <= 0) {
            val logSnippet = readLogSnippet(logFile)
            _state = OpenCodeState.Error("Process did not start (spawn=$pid)$logSnippet")
            return@withLock _state
        }

        delay(1000)

        try {
            waitForServer(port, pid)
        } catch (e: Exception) {
            // Failure path never reaches stop() (state is Error, not Running) —
            // kill the child here or a timed-out spawn keeps running, keeps
            // appending storm lines to the log we are about to read.
            try {
                processRunner.run(listOf("kill", "-9", pid.toString()))
                delay(200)
                PlatformOps.checkChild(pid)
            } catch (_: Exception) {}
            val logSnippet = readLogSnippet(logFile)
            _state = OpenCodeState.Error("Server failed to start: ${e.message}$logSnippet")
            return@withLock _state
        }

        _state = OpenCodeState.Running(port, pid)
        _state
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
        }
        val loaderBudget = maxChars * 3 / 4
        val serverBudget = maxChars - loaderBudget
        val body = try {
            fileSystem.read(logFile).getOrNull().orEmpty()
        } catch (e: Exception) {
            "(log read failed: ${e.message})"
        }
        val truncated = body.length > loaderBudget
        val tail = if (truncated) body.takeLast(loaderBudget) else body
        val serverBody = try {
            fileSystem.read(serverLog).getOrNull().orEmpty()
        } catch (e: Exception) {
            "(server log read failed: ${e.message})"
        }
        val serverTruncated = serverBody.length > serverBudget
        val serverTail = if (serverTruncated) serverBody.takeLast(serverBudget) else serverBody
        return buildString {
            append(header)
            if (truncated) appendLine("[... ${body.length - loaderBudget} earlier chars trimmed ...]")
            append(tail.ifBlank { "(log empty)" })
            appendLine()
            appendLine("=== opencode server log (${serverLog.rawPath}) ===")
            if (serverTruncated) appendLine("[... ${serverBody.length - serverBudget} earlier chars trimmed ...]")
            append(serverTail.ifBlank { "(server log empty)" })
        }
    }

    private suspend fun readLogSnippet(logFile: VirtualPath): String {
        return try {
            val logResult = fileSystem.read(logFile).getOrNull()
            if (logResult != null) {
                val all = logResult.lines()
                if (all.isNotEmpty()) "\n\nLog:\n${all.joinToString("\n")}" else ""
            } else ""
        } catch (_: Exception) { "" }
    }

    suspend fun stop() = stateMutex.withLock {
        if (_state !is OpenCodeState.Running) return@withLock
        val pid = (_state as OpenCodeState.Running).pid
        if (pid == 0) {
            _state = OpenCodeState.Stopped
            return@withLock
        }

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
            // Reap the child so it does not linger as a zombie.
            try { PlatformOps.checkChild(pid) } catch (_: Exception) {}
        }
        _state = OpenCodeState.Stopped
    }

    fun baseUrl(): String = "http://127.0.0.1:$serverPort"

    private suspend fun waitForServer(port: Int, pid: Int) {
        val deadline = PlatformOps.currentTimeMs() + config.startupTimeoutMs
        var lastError = "no attempts made"
        while (PlatformOps.currentTimeMs() < deadline) {
            val child = try { PlatformOps.checkChild(pid) } catch (_: Exception) { 0 }
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
