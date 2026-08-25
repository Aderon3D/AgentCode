package com.agent.code.workspace

class StubProcessRunner : ProcessRunner {
    override suspend fun run(command: List<String>): Result<String> =
        Result.failure(UnsupportedOperationException("ProcessRunner is stubbed in M0.5; real git runs in M1"))
}
