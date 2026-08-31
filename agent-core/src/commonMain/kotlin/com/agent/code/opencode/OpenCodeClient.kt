package com.agent.code.opencode

import com.agent.code.provider.LlmEvent
import com.agent.code.provider.LlmRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class OpenCodeClient(
    private val httpClient: HttpClient,
    private val manager: OpenCodeManager
) {
    suspend fun healthCheck(): Result<String> {
        return try {
            val response = httpClient.get("${manager.baseUrl()}/api/health")
            Result.success("OpenCode running on port ${manager.currentState().let {
                if (it is OpenCodeState.Running) it.port else "unknown"
            }}")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendChat(request: LlmRequest): String {
        val body = buildJsonObject {
            put("model", JsonPrimitive(request.modelId))
            put("messages", buildJsonArray {
                for (msg in request.messages) {
                    add(buildJsonObject {
                        put("role", JsonPrimitive(msg.role.name.lowercase()))
                        put("content", JsonPrimitive(msg.content))
                    })
                }
            })
            put("temperature", JsonPrimitive(request.temperature))
            put("max_tokens", JsonPrimitive(request.maxTokens))
        }

        val response = httpClient.post("${manager.baseUrl()}/api/chat") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        return response.bodyAsChannel().readUTF8Line() ?: ""
    }

    fun streamChat(request: LlmRequest): Flow<LlmEvent> = flow {
        val body = buildJsonObject {
            put("model", JsonPrimitive(request.modelId))
            put("stream", JsonPrimitive(true))
            put("messages", buildJsonArray {
                for (msg in request.messages) {
                    add(buildJsonObject {
                        put("role", JsonPrimitive(msg.role.name.lowercase()))
                        put("content", JsonPrimitive(msg.content))
                    })
                }
            })
            put("temperature", JsonPrimitive(request.temperature))
            put("max_tokens", JsonPrimitive(request.maxTokens))
        }

        val response = httpClient.post("${manager.baseUrl()}/api/chat/stream") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }

        val channel = response.bodyAsChannel()

        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (line.isBlank()) continue
            if (!line.startsWith("data: ")) continue

            val data = line.removePrefix("data: ").trim()
            if (data == "[DONE]") break

            val event = parseChunk(data) ?: continue
            emit(event)
        }
    }

    suspend fun listSessions(): List<String> {
        return try {
            val response = httpClient.get("${manager.baseUrl()}/api/sessions")
            val json = Json.parseToJsonElement(response.bodyAsChannel().readUTF8Line() ?: "[]")
            json.jsonArray.map { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "" }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun listModels(): List<String> {
        return try {
            val response = httpClient.get("${manager.baseUrl()}/api/models")
            val json = Json.parseToJsonElement(response.bodyAsChannel().readUTF8Line() ?: "[]")
            json.jsonArray.map { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "" }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseChunk(data: String): LlmEvent? {
        return try {
            val json = Json.parseToJsonElement(data).jsonObject
            val choices = json["choices"]?.jsonArray ?: return null
            if (choices.isEmpty()) return null
            val delta = choices[0].jsonObject["delta"]?.jsonObject ?: return null

            val content = delta["content"]?.jsonPrimitive?.contentOrNull
            if (content != null) return LlmEvent.ContentChunk(content)

            val toolCalls = delta["tool_calls"]?.jsonArray
            if (toolCalls != null) {
                for (tc in toolCalls) {
                    val tcObj = tc.jsonObject
                    val fn = tcObj["function"]?.jsonObject ?: continue
                    val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val args = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: ""
                    val id = tcObj["id"]?.jsonPrimitive?.contentOrNull ?: "tc-${System.nanoTime()}"
                    return LlmEvent.ToolCallChunk(id, name, args)
                }
            }

            val usage = json["usage"]?.jsonObject
            if (usage != null) {
                val prompt = usage["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val completion = usage["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                return LlmEvent.UsageReport(prompt, completion)
            }

            null
        } catch (_: Exception) {
            null
        }
    }
}
