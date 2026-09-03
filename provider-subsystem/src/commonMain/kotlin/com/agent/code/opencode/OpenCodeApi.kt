package com.agent.code.opencode

import com.agent.code.provider.LlmEvent
import com.agent.code.provider.LlmRequest
import kotlinx.coroutines.flow.Flow

interface OpenCodeApi {
    suspend fun healthCheck(): Result<String>
    suspend fun sendChat(request: LlmRequest): String
    fun streamChat(request: LlmRequest): Flow<LlmEvent>
    suspend fun listSessions(): List<String>
    suspend fun listModels(): List<String>
}
