package com.agent.code.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class GitProcessRunner : ProcessRunner {
    override suspend fun run(command: List<String>): Result<String> = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(command).redirectErrorStream(false).start()
        // Drain stdout and stderr concurrently so a chatty process can't fill one
        // pipe and deadlock the wait on the other.
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
}
