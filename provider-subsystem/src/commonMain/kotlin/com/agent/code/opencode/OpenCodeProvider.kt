package com.agent.code.opencode

import com.agent.code.provider.LlmEvent
import com.agent.code.provider.LlmProvider
import com.agent.code.provider.LlmRequest
import kotlinx.coroutines.flow.Flow

class OpenCodeProvider(
    private val client: OpenCodeClient
) : LlmProvider {

    override val providerId: String = "opencode"
    override val displayName: String = "OpenCode (local)"

    override fun streamCompletion(request: LlmRequest): Flow<LlmEvent> {
        return client.streamChat(request)
    }

    override suspend fun healthCheck(): Result<List<String>> {
        return client.healthCheck().map { listOf(it) }
    }
}
