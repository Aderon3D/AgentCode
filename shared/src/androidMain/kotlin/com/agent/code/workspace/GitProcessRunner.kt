package com.agent.code.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class GitProcessRunner : ProcessRunner {
    override suspend fun run(command: List<String>): Result<String> = withContext(Dispatchers.IO) {
        val proc = ProcessBuilder(command).redirectErrorStream(false).start()
        val out = proc.inputStream.bufferedReader().use(BufferedReader::readText)
        val err = proc.errorStream.bufferedReader().use(BufferedReader::readText)
        val code = proc.waitFor()
        if (code != 0) {
            Result.failure(IllegalStateException("git ${command.firstOrNull()} failed (exit $code): $err"))
        } else {
            Result.success(out)
        }
    }
}
