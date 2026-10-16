package com.agent.code.opencode

import com.agent.code.provider.LlmEvent
import com.agent.code.provider.LlmRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readUTF8Line
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
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
) : OpenCodeApi {
    override suspend fun healthCheck(): Result<String> {
        return try {
            val response = httpClient.get("${manager.baseUrl()}/api/health")
            if (response.status != io.ktor.http.HttpStatusCode.OK) {
                Result.failure(IllegalStateException("Health check returned ${response.status}"))
            } else {
                Result.success("OpenCode running on port ${manager.currentState().let {
                    if (it is OpenCodeState.Running) it.port else "unknown"
                }}")
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendChat(request: LlmRequest): String {
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
        return response.bodyAsText()
    }

    override fun streamChat(request: LlmRequest): Flow<LlmEvent> = flow {
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
        var dataBuffer = StringBuilder()

        while (true) {
            val line = channel.readUTF8Line() ?: break
            if (line.startsWith("data: ")) {
                val data = line.removePrefix("data: ").trim()
                if (data == "[DONE]") break
                dataBuffer.append(data)
            } else if (line.isBlank() && dataBuffer.isNotEmpty()) {
                val eventData = dataBuffer.toString()
                dataBuffer.clear()
                val events = parseChunk(eventData)
                for (event in events) emit(event)
            } else if (!line.isBlank()) {
                dataBuffer.append(line)
            }
        }

        if (dataBuffer.isNotEmpty()) {
            val events = parseChunk(dataBuffer.toString())
            for (event in events) emit(event)
        }
    }

    override suspend fun listSessions(): List<String> {
        return try {
            val response = httpClient.get("${manager.baseUrl()}/api/sessions")
            val text = response.bodyAsText()
            val json = Json.parseToJsonElement(text.ifBlank { "[]" })
            json.jsonArray.map { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "" }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun listModels(): List<String> {
        return try {
            val response = httpClient.get("${manager.baseUrl()}/api/models")
            val text = response.bodyAsText()
            val json = Json.parseToJsonElement(text.ifBlank { "[]" })
            json.jsonArray.map { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: "" }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseChunk(data: String): List<LlmEvent> {
        return try {
            val json = Json.parseToJsonElement(data).jsonObject
            val choices = json["choices"]?.jsonArray ?: return emptyList()
            if (choices.isEmpty()) return emptyList()
            val delta = choices[0].jsonObject["delta"]?.jsonObject ?: return emptyList()

            val content = delta["content"]?.jsonPrimitive?.contentOrNull
            if (content != null) return listOf(LlmEvent.ContentChunk(content))

            val toolCalls = delta["tool_calls"]?.jsonArray
            if (toolCalls != null) {
                val events = mutableListOf<LlmEvent>()
                for (tc in toolCalls) {
                    val tcObj = tc.jsonObject
                    val fn = tcObj["function"]?.jsonObject ?: continue
                    val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: continue
                    val args = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: ""
                    val id = tcObj["id"]?.jsonPrimitive?.contentOrNull ?: "tc-${System.nanoTime()}"
                    events.add(LlmEvent.ToolCallChunk(id, name, args))
                }
                if (events.isNotEmpty()) return events
            }

            val usage = json["usage"]?.jsonObject
            if (usage != null) {
                val prompt = usage["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val completion = usage["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                return listOf(LlmEvent.UsageReport(prompt, completion))
            }

            emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // ---- Native session API (the real opencode agent) ----
    // The OpenAI-shaped /api/chat* paths above don't exist on this server
    // (unknown routes return the SPA HTML fallback with 200). The v2 session
    // endpoints below are the actual agent pipeline:
    //   POST /api/session                 -> create a session
    //   POST /api/session/{id}/prompt     -> queue the goal text
    //   GET  /api/session/{id}/event      -> SSE stream of run events

    suspend fun createSession(providerID: String, modelID: String): String {
        val body = buildJsonObject {
            put("model", buildJsonObject {
                put("providerID", JsonPrimitive(providerID))
                put("id", JsonPrimitive(modelID))
            })
        }
        val text = httpClient.post("${manager.baseUrl()}/api/session") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }.bodyAsText()
        // Guard against the SPA HTML fallback (200 but not JSON).
        if (text.trimStart().startsWith("<")) error("createSession: server returned HTML, not JSON")
        val id = runCatching {
            Json.parseToJsonElement(text).jsonObject["data"]?.jsonObject?.get("id")
                ?.jsonPrimitive?.contentOrNull
        }.getOrNull()
        return id ?: error("createSession: no session id in response: ${text.take(200)}")
    }

    suspend fun sendPrompt(sessionID: String, text: String) {
        val body = buildJsonObject {
            put("prompt", buildJsonObject { put("text", JsonPrimitive(text)) })
        }
        val resp = httpClient.post("${manager.baseUrl()}/api/session/$sessionID/prompt") {
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        // Always drain the body so the connection returns to the pool.
        val bodyText = resp.bodyAsText()
        if (resp.status.value >= 400) error("sendPrompt: ${resp.status} ${bodyText.take(300)}")
    }

    fun sessionEvents(sessionID: String, idleTimeoutMs: Long = 120_000L): Flow<SessionEvent> = flow {
        // The event channel idles between agent steps (LLM latency can exceed
        // the default 10s read timeout and would kill a healthy run).
        val channel = httpClient.get("${manager.baseUrl()}/api/session/$sessionID/event") {
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
            }
        }.bodyAsChannel()
        try {
            while (true) {
                // Idle deadline, not a whole-run deadline: reset on every event, so
                // only a true stall trips it. Without it a server that accepts the
                // stream but never closes it hangs the run forever (no
                // idle/completion event: the stream just stays open).
                val line = withTimeout(idleTimeoutMs) { channel.readUTF8Line() } ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                val event = parseSessionEvent(payload)
                if (event != null) emit(event)
            }
        } finally {
            // Release the response/connection on early termination (idle timeout
            // or collector cancellation) instead of leaking it in the pool.
            channel.cancel()
        }
    }

    private fun parseSessionEvent(payload: String): SessionEvent? {
        return try {
            val root = Json.parseToJsonElement(payload).jsonObject
            val type = root["type"]?.jsonPrimitive?.contentOrNull
            val data = root["data"]?.jsonObject ?: return null
            fun str(k: String) = data[k]?.jsonPrimitive?.contentOrNull
            when (type) {
                "session.next.prompt.admitted" -> SessionEvent.PromptAdmitted(
                    str("messageID") ?: "?", str("delivery") ?: "?"
                )
                "session.next.step.started" -> SessionEvent.StepStarted(
                    data["model"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull ?: "?",
                    str("agent") ?: "?"
                )
                "session.next.text.delta" -> SessionEvent.TextDelta(str("text") ?: "")
                "session.next.text.ended" -> SessionEvent.TextEnded(str("text") ?: "")
                "session.next.tool.called" -> SessionEvent.ToolCalled(str("tool") ?: "?")
                "session.next.tool.input.ended" -> SessionEvent.ToolInput(str("command") ?: str("filePath") ?: "")
                "session.next.tool.success" -> SessionEvent.ToolFinished(str("callID") ?: "?", true)
                "session.next.tool.error" -> SessionEvent.ToolFinished(str("callID") ?: "?", false)
                "session.next.step.ended" -> SessionEvent.StepEnded(str("finish") ?: "unknown")
                "session.next.step.failed" -> {
                    val err = data["error"]?.jsonObject
                    SessionEvent.Failed(
                        "${err?.get("type")?.jsonPrimitive?.contentOrNull ?: "error"}: " +
                            "${err?.get("message")?.jsonPrimitive?.contentOrNull ?: "step failed"}"
                    )
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }
}

sealed interface SessionEvent {
    data class PromptAdmitted(val messageID: String, val delivery: String) : SessionEvent
    data class StepStarted(val model: String, val agent: String) : SessionEvent
    data class TextDelta(val text: String) : SessionEvent
    data class TextEnded(val text: String) : SessionEvent
    data class ToolInput(val detail: String) : SessionEvent
    data class ToolCalled(val tool: String) : SessionEvent
    data class ToolFinished(val callID: String, val ok: Boolean) : SessionEvent
    data class StepEnded(val finish: String) : SessionEvent
    data class Failed(val message: String) : SessionEvent
}
