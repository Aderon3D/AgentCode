package com.agent.code.provider

import kotlinx.coroutines.flow.Flow

enum class Role { SYSTEM, USER, ASSISTANT, TOOL }

data class ChatMessage(val role: Role, val content: String)

data class LlmRequest(
    val modelId: String,
    val messages: List<ChatMessage>,
    val temperature: Float = 0.2f,
    val maxTokens: Int = 8192
)

sealed interface LlmEvent {
    data class ReasoningChunk(val text: String) : LlmEvent
    data class ContentChunk(val text: String) : LlmEvent
    data class ToolCallChunk(val id: String, val name: String, val jsonArgsDelta: String) : LlmEvent
    data class UsageReport(val promptTokens: Int, val completionTokens: Int) : LlmEvent
    data class SystemWarning(val code: Int, val message: String) : LlmEvent
}

interface LlmProvider {
    val providerId: String
    val displayName: String
    fun streamCompletion(request: LlmRequest): Flow<LlmEvent>
    suspend fun healthCheck(): Result<List<String>>
}
