package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class GitProcessRunner : ProcessRunner {
    override suspend fun run(command: List<String>): Result<String> = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(command).redirectErrorStream(false).start()
        val out = async { proc.inputStream.bufferedReader().use(BufferedReader::readText) }
        val err = async { proc.errorStream.bufferedReader().use(BufferedReader::readText) }
        val code = proc.waitFor()
        val outText = out.await()
        val errText = err.await()
        if (code != 0) {
            Result.failure(IllegalStateException("git ${command.firstOrNull()} failed (exit $code): $errText"))
        } else {
            Result.success(outText)
        }
    }

    override suspend fun execute(config: ProcessConfiguration): Result<ProcessOutput> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val cmd = listOf(config.command) + config.args
        val pb = ProcessBuilder(cmd)
            .directory(java.io.File(config.workingDir.rawPath))
            .redirectErrorStream(false)
        config.environmentVariables.forEach { (k, v) -> pb.environment()[k] = v }

        val proc = pb.start()
        val out = async { proc.inputStream.bufferedReader().use(BufferedReader::readText) }
        val err = async { proc.errorStream.bufferedReader().use(BufferedReader::readText) }
        val code = proc.waitFor()
        val elapsed = System.currentTimeMillis() - startTime
        Result.success(ProcessOutput(code, out.await(), err.await(), elapsed))
    }

    override fun executeStreaming(config: ProcessConfiguration): Flow<ProcessEvent> = callbackFlow {
        val cmd = listOf(config.command) + config.args
        val pb = ProcessBuilder(cmd)
            .directory(java.io.File(config.workingDir.rawPath))
            .redirectErrorStream(false)
        config.environmentVariables.forEach { (k, v) -> pb.environment()[k] = v }

        val proc = pb.start()
        val stdoutReader = Thread {
            proc.inputStream.bufferedReader().forEachLine { line ->
                trySend(ProcessEvent.StdoutLine(line))
            }
        }
        val stderrReader = Thread {
            proc.errorStream.bufferedReader().forEachLine { line ->
                trySend(ProcessEvent.StderrLine(line))
            }
        }
        stdoutReader.start()
        stderrReader.start()
        stdoutReader.join()
        stderrReader.join()
        val code = proc.waitFor()
        trySend(ProcessEvent.Terminated(code))
        close()
        awaitClose { proc.destroyForcibly() }
    }
}
