package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

class StubProcessRunner : ProcessRunner {
    override suspend fun run(command: List<String>): Result<String> =
        Result.failure(UnsupportedOperationException("ProcessRunner is stubbed in M0.5; real git runs in M1"))

    override suspend fun execute(config: ProcessConfiguration): Result<ProcessOutput> =
        Result.failure(UnsupportedOperationException("ProcessRunner is stubbed in M0.5; real git runs in M1"))

    override fun executeStreaming(config: ProcessConfiguration): Flow<ProcessEvent> = emptyFlow()
}
