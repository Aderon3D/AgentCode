package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath
import kotlinx.coroutines.flow.Flow

data class ProcessConfiguration(
    val command: String,
    val args: List<String> = emptyList(),
    val workingDir: VirtualPath,
    val environmentVariables: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 120_000
)

data class ProcessOutput(val exitCode: Int, val stdout: String, val stderr: String, val executionTimeMs: Long)

sealed interface ProcessEvent {
    data class StdoutLine(val line: String) : ProcessEvent
    data class StderrLine(val line: String) : ProcessEvent
    data class Terminated(val exitCode: Int) : ProcessEvent
}

interface ProcessRunner {
    suspend fun run(command: List<String>): Result<String>
    suspend fun execute(config: ProcessConfiguration): Result<ProcessOutput>
    fun executeStreaming(config: ProcessConfiguration): Flow<ProcessEvent>
}
