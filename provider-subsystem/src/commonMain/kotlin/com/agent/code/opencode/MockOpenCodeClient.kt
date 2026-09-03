package com.agent.code.opencode

import com.agent.code.provider.LlmEvent
import com.agent.code.provider.LlmRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class MockOpenCodeClient : OpenCodeApi {
    private var callCount = 0

    override suspend fun healthCheck(): Result<String> = Result.success("Mock running")

    override suspend fun sendChat(request: LlmRequest): String {
        callCount++
        return if (callCount <= 1) {
            """{"tool_calls":[{"id":"tc-1","name":"read_file","arguments":"{\"path\":\"README.md\"}"}]}"""
        } else {
            """{"done":true,"summary":"Mock task complete"}"""
        }
    }

    override fun streamChat(request: LlmRequest): Flow<LlmEvent> = flow {
        callCount++
        if (callCount <= 1) {
            emit(LlmEvent.ReasoningChunk("Thinking about the task...\n"))
            delay(100)
            emit(LlmEvent.ToolCallChunk("tc-1", "read_file", """{"path":"README.md"}"""))
            delay(50)
            emit(LlmEvent.UsageReport(100, 50))
        } else {
            emit(LlmEvent.ContentChunk("Done. "))
            delay(50)
            emit(LlmEvent.ContentChunk("""{"done":true,"summary":"Mock task complete"}"""))
            delay(50)
            emit(LlmEvent.UsageReport(80, 30))
        }
    }

    override suspend fun listSessions(): List<String> = emptyList()
    override suspend fun listModels(): List<String> = listOf("mock-model")
}
