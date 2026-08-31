package com.agent.code.opencode

import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.FileSystemProvider
import com.agent.code.workspace.ProcessRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    private var state: OpenCodeState = OpenCodeState.NotInstalled
    private var serverPort: Int = config.defaultPort

    fun currentState(): OpenCodeState = state

    suspend fun ensureInstalled(): OpenCodeState {
        val binaryPath = config.installDir.resolve(config.binaryName)
        if (fileSystem.exists(binaryPath)) {
            state = OpenCodeState.Stopped
            return state
        }

        state = OpenCodeState.Installing
        val result = processRunner.run(listOf(
            "sh", "-c",
            "mkdir -p ${config.installDir.rawPath} && " +
            "curl -fsSL https://opencode.ai/install.sh | " +
            "BINDIR=${config.installDir.rawPath} sh"
        ))
        return if (result.isSuccess) {
            state = OpenCodeState.Stopped
            state
        } else {
            state = OpenCodeState.Error("Install failed: ${result.getOrElse { "" }}")
            state
        }
    }

    suspend fun start(
        projectDir: VirtualPath,
        port: Int = config.defaultPort
    ): OpenCodeState {
        if (state is OpenCodeState.Running) return state

        val binaryPath = config.installDir.resolve(config.binaryName)
        if (!fileSystem.exists(binaryPath)) {
            val installed = ensureInstalled()
            if (installed is OpenCodeState.Error) return installed
        }

        state = OpenCodeState.Starting
        serverPort = port

        val pidFile = config.installDir.resolve("opencode.pid")
        val logFile = config.installDir.resolve("opencode.log")

        val result = processRunner.run(listOf(
            "sh", "-c",
            """
            |cd ${projectDir.rawPath}
            |${binaryPath.rawPath} serve
            |  --port $port
            |  --pid-file ${pidFile.rawPath}
            |  > ${logFile.rawPath} 2>&1
            |echo $!
            """.trimMargin()
        ))

        if (!result.isSuccess) {
            state = OpenCodeState.Error("Start failed: ${result.getOrElse { "" }}")
            return state
        }

        val pid = result.getOrNull()?.trim()?.toIntOrNull() ?: 0
        waitForServer(port)
        state = OpenCodeState.Running(port, pid)
        return state
    }

    suspend fun stop() {
        if (state !is OpenCodeState.Running) return
        val pid = (state as OpenCodeState.Running).pid
        processRunner.run(listOf("kill", "-TERM", pid.toString()))
        delay(1000)
        processRunner.run(listOf("kill", "-9", pid.toString()))
        state = OpenCodeState.Stopped
    }

    fun baseUrl(): String = "http://127.0.0.1:$serverPort"

    private suspend fun waitForServer(port: Int) {
        val deadline = System.currentTimeMillis() + config.startupTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                val result = processRunner.run(listOf(
                    "curl", "-sf", "http://127.0.0.1:$port/api/health"
                ))
                if (result.isSuccess) return
            } catch (_: Exception) {}
            delay(500)
        }
    }
}
