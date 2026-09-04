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
    val installDir: VirtualPath = VirtualPath.of("/data/data/com.agent.code/files/opencode"),
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

        // Download and extract glibc libs
        val glibcFiles = listOf(
            "ld-linux-aarch64.so.1",
            "libc.so.6",
            "libpthread.so.0",
            "libdl.so.2"
        )

        glibcFiles.forEachIndexed { index, name ->
            _state = OpenCodeState.Installing(
                (index + 1).toFloat() / (glibcFiles.size + 1),
                "Downloading $name..."
            )
            val dest = "$glibcDir/$name"
            PlatformOps.downloadFile("${config.releaseUrl}/$name", dest)
        }

        // Download and extract opencode binary (gzipped)
        _state = OpenCodeState.Installing(0.8f, "Downloading opencode binary...")
        val gzFile = "$installDir/${config.binaryName}.gz"
        PlatformOps.downloadFile("${config.releaseUrl}/opencode.gz", gzFile)

        _state = OpenCodeState.Installing(0.9f, "Extracting binary...")
        val binaryDest = "$installDir/${config.binaryName}"
        PlatformOps.extractGzip(gzFile, binaryDest)
        PlatformOps.deleteFile(gzFile)
        PlatformOps.setExecutable(binaryDest)

        _state = OpenCodeState.Installing(0.95f, "Setting up wrapper...")
        createWrapper(installDir)

        _state = OpenCodeState.Installing(1.0f, "Ready")
    }

    private suspend fun createWrapper(installDir: String) {
        val wrapperPath = "$installDir/run-opencode.sh"
        val binaryName = config.binaryName
        // Write wrapper script content via processRunner
        val script = "#!/bin/sh\n" +
            "_INSTALL_DIR=\"$installDir\"\n" +
            "_GLIBC_DIR=\"\$_INSTALL_DIR/glibc\"\n" +
            "_BIN=\"\$_INSTALL_DIR/$binaryName\"\n" +
            "\n" +
            "# Sanitize LD_LIBRARY_PATH (remove Android bionic libs that break glibc)\n" +
            "if [ -n \"\$LD_LIBRARY_PATH\" ]; then\n" +
            "    LD_LIBRARY_PATH=\$(printf '%s' \"\$LD_LIBRARY_PATH\" | tr ':' '\\n' | grep -v \"^/data/user/.*com.agent.code/files/support\\\$\" | paste -sd:)\n" +
            "    export LD_LIBRARY_PATH\n" +
            "fi\n" +
            "\n" +
            "exec \"\$_GLIBC_DIR/ld-linux-aarch64.so.1\" \\\n" +
            "     --library-path \"\$_GLIBC_DIR:/system/lib64:/apex/com.android.runtime/lib64\" \\\n" +
            "     \"\$_BIN\" \"\$@\"\n"
        processRunner.run(listOf("sh", "-c", "cat > '$wrapperPath' << 'WRAPPER_EOF'\n$script\nWRAPPER_EOF"))
        PlatformOps.setExecutable(wrapperPath)
    }

    suspend fun start(
        projectDir: VirtualPath,
        port: Int = config.defaultPort
    ): OpenCodeState = stateMutex.withLock {
        if (_state is OpenCodeState.Running) return@withLock _state

        val wrapperPath = config.installDir.resolve("run-opencode.sh")
        if (!fileSystem.exists(wrapperPath)) {
            val installed = ensureInstalledInternal()
            if (installed is OpenCodeState.Error) return@withLock installed
        }

        _state = OpenCodeState.Starting
        serverPort = port

        val pidFile = config.installDir.resolve("opencode.pid")
        val logFile = config.installDir.resolve("opencode.log")

        val cmd = "cd '${projectDir.rawPath}' && " +
            "sh '${wrapperPath.rawPath}' serve " +
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
        val deadline = PlatformOps.currentTimeMs() + config.startupTimeoutMs
        while (PlatformOps.currentTimeMs() < deadline) {
            try {
                val code = PlatformOps.httpGet("http://127.0.0.1:$port/api/health", 1000, 1000)
                if (code == 200) return
            } catch (_: Exception) {}
            delay(500)
        }
        throw IllegalStateException("OpenCode server did not start within ${config.startupTimeoutMs}ms on port $port")
    }
}
