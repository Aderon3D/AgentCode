package com.agent.code.workspace

class StubProcessRunner : ProcessRunner {
    override suspend fun run(command: List<String>): String =
        throw UnsupportedOperationException("ProcessRunner is stubbed in M0.5; real git runs in M1")
}
