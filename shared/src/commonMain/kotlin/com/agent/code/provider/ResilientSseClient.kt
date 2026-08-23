package com.agent.code.provider

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class ResilientSseClient(private val maxRetries: Int = 5) {
    fun streamWithRetry(provider: LlmProvider, request: LlmRequest): Flow<LlmEvent> = flow {
        var attempt = 0
        var backoffMs = 1000L
        while (attempt < maxRetries) {
            try {
                provider.streamCompletion(request).collect { emit(it) }
                return@flow
            } catch (e: Exception) {
                attempt++
                if (attempt >= maxRetries) throw e
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }
}
