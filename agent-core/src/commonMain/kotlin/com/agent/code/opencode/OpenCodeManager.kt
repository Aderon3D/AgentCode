package com.agent.code.opencode

import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class OpenCodeConfig(
    val installDir: VirtualPath = VirtualPath.of("/data/data/com.agent.code/files/opencode"),
    val binaryName: String = "opencode",
    val defaultPort: Int = 4096,
    val startupTimeoutMs: Long = 15_000L
)

sealed interface OpenCodeState {
    data object NotInstalled : OpenCodeState
    data object Installing : OpenCodeState
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
    private var _state: OpenCodeState = OpenCodeState.NotInstalled
    private var serverPort: Int = config.defaultPort

    val state: OpenCodeState get() = _state

    fun currentState(): OpenCodeState = _state

    suspend fun ensureInstalled(): OpenCodeState = stateMutex.withLock {
        ensureInstalledInternal()
    }

    private suspend fun ensureInstalledInternal(): OpenCodeState {
        val binaryPath = config.installDir.resolve(config.binaryName)
        if (fileSystem.exists(binaryPath)) {
            _state = OpenCodeState.Stopped
            return _state
        }

        _state = OpenCodeState.Installing
        val dir = config.installDir.rawPath
        val result = processRunner.run(listOf(
            "sh", "-c",
            "mkdir -p '${dir}' && " +
            "curl -fsSL https://opencode.ai/install.sh | " +
            "BINDIR='${dir}' sh"
        ))
        _state = if (result.isSuccess) {
            OpenCodeState.Stopped
        } else {
            OpenCodeState.Error("Install failed: ${result.getOrElse { "" }}")
        }
        return _state
    }

    suspend fun start(
        projectDir: VirtualPath,
        port: Int = config.defaultPort
    ): OpenCodeState = stateMutex.withLock {
        if (_state is OpenCodeState.Running) return@withLock _state

        val binaryPath = config.installDir.resolve(config.binaryName)
        if (!fileSystem.exists(binaryPath)) {
            val installed = ensureInstalledInternal()
            if (installed is OpenCodeState.Error) return@withLock installed
        }

        _state = OpenCodeState.Starting
        serverPort = port

        val pidFile = config.installDir.resolve("opencode.pid")
        val logFile = config.installDir.resolve("opencode.log")

        val cmd = "cd '${projectDir.rawPath}' && " +
            "'${binaryPath.rawPath}' serve " +
            "--port $port " +
            "--pid-file '${pidFile.rawPath}' " +
            "> '${logFile.rawPath}' 2>&1 & " +
            "echo \$!"

        val result = processRunner.run(listOf("sh", "-c", cmd))

        if (!result.isSuccess) {
            _state = OpenCodeState.Error("Start failed: ${result.getOrElse { "" }}")
            return@withLock _state
        }

        val pid = try {
            val pidContent = processRunner.run(listOf("cat", pidFile.rawPath))
            pidContent.getOrNull()?.trim()?.toIntOrNull() ?: 0
        } catch (_: Exception) { 0 }

        try {
            waitForServer(port)
        } catch (e: Exception) {
            _state = OpenCodeState.Error("Server failed to start: ${e.message}")
            return@withLock _state
        }

        _state = OpenCodeState.Running(port, pid)
        _state
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
        }
        _state = OpenCodeState.Stopped
    }

    fun baseUrl(): String = "http://127.0.0.1:$serverPort"

    private suspend fun waitForServer(port: Int) {
        val deadline = System.currentTimeMillis() + config.startupTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val url = java.net.URL("http://127.0.0.1:$port/api/health")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 1000
                conn.readTimeout = 1000
                try {
                    if (conn.responseCode == 200) return
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {}
            delay(500)
        }
        throw IllegalStateException("OpenCode server did not start within ${config.startupTimeoutMs}ms on port $port")
    }
}
