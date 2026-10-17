package com.agent.code.opencode

import com.agent.code.provider.LlmEvent
import com.agent.code.provider.LlmRequest
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlinx.serialization.json.JsonObject
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

    // Kotlin-side build stamp. The native loader carries its own (elfloader.c
    // ELFLOADER_ROUND), but that only moves when the C changes — a Kotlin-only
    // fix ships a stale tag and two different APKs read identically (log 20).
    // Bump APP_BUILD on every client-side change so a pasted dump names its APK.
    companion object {
        const val APP_BUILD = "r27"
    }

    // x-opencode-directory on every session request: the server's workspace
    // routing resolves directory from ?directory=, then this header, then
    // cwd. On device cwd alone left workspaceID=undefined and the location
    // boot hung (log 21/22) — both reference clients (oc-remote, P4OC) pass
    // the directory explicitly on every call.
    private fun io.ktor.client.request.HttpRequestBuilder.dirHeader() {
        val dir = manager.workspaceDir
        if (dir.isNotBlank()) header("x-opencode-directory", dir)
    }
    @Suppress("TooGenericExceptionCaught") // HTTP boundary: any failure maps to Result.failure
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
            parseChunkObject(Json.parseToJsonElement(data).jsonObject)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseChunkObject(json: JsonObject): List<LlmEvent> {
        val choices = json["choices"]?.jsonArray
        if (choices == null || choices.isEmpty()) return emptyList()
        val delta = choices[0].jsonObject["delta"]?.jsonObject ?: return emptyList()

        val content = delta["content"]?.jsonPrimitive?.contentOrNull
        if (content != null) return listOf(LlmEvent.ContentChunk(content))

        return parseToolCallChunks(delta).ifEmpty { parseUsage(json) }
    }

    private fun parseToolCallChunks(delta: JsonObject): List<LlmEvent> {
        val toolCalls = delta["tool_calls"]?.jsonArray ?: return emptyList()
        val events = mutableListOf<LlmEvent>()
        for (tc in toolCalls) {
            val tcObj = tc.jsonObject
            val fn = tcObj["function"]?.jsonObject ?: continue
            val name = fn["name"]?.jsonPrimitive?.contentOrNull ?: continue
            val args = fn["arguments"]?.jsonPrimitive?.contentOrNull ?: ""
            val id = tcObj["id"]?.jsonPrimitive?.contentOrNull ?: "tc-${System.nanoTime()}"
            events.add(LlmEvent.ToolCallChunk(id, name, args))
        }
        return events
    }

    private fun parseUsage(json: JsonObject): List<LlmEvent> {
        val usage = json["usage"]?.jsonObject ?: return emptyList()
        val prompt = usage["prompt_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val completion = usage["completion_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        return listOf(LlmEvent.UsageReport(prompt, completion))
    }

    // ---- Native session API (the real opencode agent) ----
    // The OpenAI-shaped /api/chat* paths above don't exist on this server
    // (unknown routes return the SPA HTML fallback with 200). The v2 session
    // endpoints below are the actual agent pipeline:
    //   POST /api/session                 -> create a session
    //   POST /api/session/{id}/prompt     -> queue the goal text
    //   GET  /api/event                   -> global SSE stream (filter by sessionID)
    // The per-session GET /api/session/{id}/event hangs on device: it sits
    // behind the workspace-routing middleware that waits on the location
    // boot, which never resolves (workspaceID=undefined, log 21/22). The
    // global /api/event is outside that middleware — both reference clients
    // (oc-remote, P4OC) use it and filter by sessionID client-side.

    suspend fun createSession(providerID: String, modelID: String): String {
        val body = buildJsonObject {
            put("model", buildJsonObject {
                put("providerID", JsonPrimitive(providerID))
                put("id", JsonPrimitive(modelID))
            })
        }
        val text = httpClient.post("${manager.baseUrl()}/api/session") {
            dirHeader()
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
            dirHeader()
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        // Always drain the body so the connection returns to the pool.
        val bodyText = resp.bodyAsText()
        if (resp.status.value >= 400) {
            error("sendPrompt: ${resp.status} ${bodyText.take(300)}")
        }
    }

    // Permission/question auto-reply (open issue 3, docs §8). The agent can
    // pause mid-run on a permission.asked / question.asked event and wait
    // forever for a human answer that doesn't exist on-device — that's a
    // stall we own. Reply "once" (allow this single call, no persistent
    // saved-permission) and reject questions (no payload shape to guess,
    // rejection unblocks the run with zero state).
    suspend fun replyPermission(sessionID: String, requestID: String) {
        val resp = httpClient.post(
            "${manager.baseUrl()}/api/session/$sessionID/permission/$requestID/reply"
        ) {
            dirHeader()
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject { put("reply", JsonPrimitive("once")) }.toString())
            timeout { requestTimeoutMillis = 15_000 }
        }
        if (resp.status.value >= 400) {
            error("replyPermission: ${resp.status} ${resp.bodyAsText().take(300)}")
        }
    }

    suspend fun questionReject(sessionID: String, requestID: String) {
        val resp = httpClient.post(
            "${manager.baseUrl()}/api/session/$sessionID/question/$requestID/reject"
        ) {
            dirHeader()
            contentType(ContentType.Application.Json)
            setBody("{}")
            timeout { requestTimeoutMillis = 15_000 }
        }
        if (resp.status.value >= 400) {
            error("questionReject: ${resp.status} ${resp.bodyAsText().take(300)}")
        }
    }

    // SSE surface (this method + replyPermission/questionReject + SessionEvent)
    // is retained but unused under the run architecture — AgentBrain drives
    // `opencode run` via OpenCodeManager.runPrompt. Kept so the connection-
    // release hygiene below survives if the serve/SSE path is ever re-enabled.
    fun sessionEvents(sessionID: String, idleTimeoutMs: Long = 120_000L): Flow<SessionEvent> = flow {
        // Global stream: outside the workspace-routing middleware that hangs
        // the per-session endpoint on device (location boot never resolves,
        // logs 21/22). Live-only — no replay — so AgentBrain still subscribes
        // before sendPrompt.
        // requestTimeout stays INFINITE (an agent run can idle for minutes
        // between steps — a finite request budget kills healthy runs, r15).
        // connectTimeout is finite on purpose: a deadline turns a wedged
        // connect into a visible StreamInfo error instead of a silent hang.
        val resp = httpClient.get("${manager.baseUrl()}/api/event") {
            dirHeader()
            timeout {
                requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                socketTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
                connectTimeoutMillis = 15_000
            }
        }
        emit(SessionEvent.StreamInfo("SSE connect ${resp.status.value}"))
        val channel = resp.bodyAsChannel()
        try {
            while (true) {
                // Idle deadline, not a whole-run deadline: reset on every event,
                // so only a true stall trips it. Without it a server that accepts
                // the stream but never closes it hangs the run forever.
                val line = withTimeout(idleTimeoutMs) { channel.readUTF8Line() } ?: break
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (!eventBelongsToSession(payload, sessionID)) continue
                val event = parseSessionEvent(payload)
                if (event != null) emit(event)
            }
        } finally {
            // Release the response/connection on early termination (idle timeout
            // or collector cancellation) instead of leaking it in the pool.
            channel.cancel(null)
        }
        emit(SessionEvent.StreamInfo("SSE stream ended"))
    }

    internal fun parseSessionEvent(payload: String): SessionEvent? =
        parseSessionEventJson(payload)
}

// The global /api/event stream wraps every event as
// {directory, payload:{type, properties}}; the per-session endpoint (and
// older builds) send the flat {type, data}. Unwrap both shapes here so the
// when() below is version-agnostic.
internal fun unwrapEventEnvelope(root: kotlinx.serialization.json.JsonObject): Pair<String?, kotlinx.serialization.json.JsonObject>? {
    val payload = root["payload"]?.jsonObject ?: root
    val type = payload["type"]?.jsonPrimitive?.contentOrNull
    val data = payload["properties"]?.jsonObject ?: payload["data"]?.jsonObject
    return if (type != null && data != null) type to data else null
}

// Global stream delivers events for every session; drop the ones that
// aren't ours before parsing. Events with no sessionID (server.connected
// etc.) are dropped too — the brain only wants its own run.
internal fun eventBelongsToSession(payload: String, sessionID: String): Boolean {
    return try {
        val root = Json.parseToJsonElement(payload).jsonObject
        val ps = root["payload"]?.jsonObject ?: root
        val props = ps["properties"]?.jsonObject ?: ps["data"]?.jsonObject ?: return false
        props["sessionID"]?.jsonPrimitive?.contentOrNull == sessionID
    } catch (_: Exception) {
        false
    }
}

internal fun parseSessionEventJson(payload: String): SessionEvent? {
    return try {
        val root = Json.parseToJsonElement(payload).jsonObject
        val (type, data) = unwrapEventEnvelope(root) ?: return null
        SessionEventParsers.dispatch(type, data)
    } catch (_: Exception) {
        null
    }
}

// Per-type session-event extractors, grouped in one object so the file stays
// within its function budget (TooManyFunctions counts file-level functions).
private object SessionEventParsers {
    fun dispatch(type: String?, data: JsonObject): SessionEvent? = when (type) {
        "session.next.prompt.admitted" -> promptAdmitted(data)
        // Observed on 1.18.31 right after admitted; we don't act on it,
        // but naming it stops it from falling into the unknown bucket.
        "session.next.prompted" -> promptAdmitted(data)
        "session.next.step.started" -> stepStarted(data)
        "session.next.text.delta" -> textDelta(data)
        "session.next.text.ended" -> textEnded(data)
        "session.next.tool.called" -> toolCalled(data)
        "session.next.tool.input.ended" -> toolInputEnded(data)
        "session.next.tool.success" -> toolFinished(data, true)
        "session.next.tool.error" -> toolFinished(data, false)
        "session.next.step.ended" -> stepEnded(data)
        // Both v1 and v2 land here; the request id is the only
        // field that survives the version rename (v1 uses "id",
        // v2 uses "requestID"). Auto-replied in AgentBrain.
        "permission.asked", "permission.v2.asked" -> permissionAsked(data)
        "question.asked", "question.v2.asked" -> questionAsked(data)
        "session.next.step.failed" -> stepFailed(data)
        else -> null
    }

    // Field lookup shared by the per-event extractors; null when absent.
    private fun str(data: JsonObject, key: String): String? =
        data[key]?.jsonPrimitive?.contentOrNull

    private fun promptAdmitted(data: JsonObject) = SessionEvent.PromptAdmitted(
        str(data, "messageID") ?: "?", str(data, "delivery") ?: "?"
    )

    private fun stepStarted(data: JsonObject) = SessionEvent.StepStarted(
        data["model"]?.jsonObject?.get("id")?.jsonPrimitive?.contentOrNull ?: "?",
        str(data, "agent") ?: "?"
    )

    private fun textDelta(data: JsonObject) = SessionEvent.TextDelta(str(data, "text") ?: "")

    private fun textEnded(data: JsonObject) = SessionEvent.TextEnded(str(data, "text") ?: "")

    private fun toolCalled(data: JsonObject) = SessionEvent.ToolCalled(str(data, "tool") ?: "?")

    private fun toolInputEnded(data: JsonObject) =
        SessionEvent.ToolInput(str(data, "command") ?: str(data, "filePath") ?: "")

    private fun toolFinished(data: JsonObject, ok: Boolean) =
        SessionEvent.ToolFinished(str(data, "callID") ?: "?", ok)

    private fun stepEnded(data: JsonObject) = SessionEvent.StepEnded(str(data, "finish") ?: "unknown")

    private fun permissionAsked(data: JsonObject) = SessionEvent.PermissionAsked(
        str(data, "requestID") ?: str(data, "id") ?: "?",
        str(data, "permission") ?: str(data, "title") ?: "permission"
    )

    private fun questionAsked(data: JsonObject) = SessionEvent.QuestionAsked(
        str(data, "requestID") ?: str(data, "id") ?: "?"
    )

    private fun stepFailed(data: JsonObject): SessionEvent.Failed {
        val err = data["error"]?.jsonObject
        return SessionEvent.Failed(
            "${err?.get("type")?.jsonPrimitive?.contentOrNull ?: "error"}: " +
                "${err?.get("message")?.jsonPrimitive?.contentOrNull ?: "step failed"}"
        )
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
    data class StreamInfo(val detail: String) : SessionEvent
    data class PermissionAsked(val requestID: String, val detail: String) : SessionEvent
    data class QuestionAsked(val requestID: String) : SessionEvent
}

// `opencode run --format json` emits one flat JSON object per line: top-level
// snake_case `type` + a `part` object (part.type is the kebab-case variant).
// No payload/properties envelope like the SSE stream. A completed tool_use
// carries BOTH the start and the finish in one event, so this returns a List
// (usually one element; two for a completed/failed tool). Anything else (file,
// tool, etc.) maps to an empty list and is skipped.
internal fun parseRunEvent(payload: String): List<SessionEvent> {
    return try {
        val root = Json.parseToJsonElement(payload).jsonObject
        val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return emptyList()
        RunEventParsers.dispatch(type, root)
    } catch (_: Exception) {
        emptyList()
    }
}

// `run --format json` per-type extractors, grouped like SessionEventParsers.
private object RunEventParsers {
    fun dispatch(type: String, root: JsonObject): List<SessionEvent> {
        val part = root["part"]?.jsonObject
        return when (type) {
            "step_start" -> listOf(SessionEvent.StepStarted(str(part, "model") ?: "?", "build"))
            "text" -> text(part)
            "tool_use" -> toolUse(part)
            "step_finish" -> listOf(SessionEvent.StepEnded(str(part, "reason") ?: "unknown"))
            "error" -> listOf(SessionEvent.Failed(errorMessage(root)))
            else -> emptyList()
        }
    }

    private fun str(part: JsonObject?, key: String): String? =
        part?.get(key)?.jsonPrimitive?.contentOrNull

    private fun text(part: JsonObject?): List<SessionEvent> {
        val t = str(part, "text") ?: ""
        return if (t.isEmpty()) emptyList() else listOf(SessionEvent.TextEnded(t))
    }

    private fun toolUse(part: JsonObject?): List<SessionEvent> {
        val tool = str(part, "tool") ?: "?"
        val callID = str(part, "callID") ?: "?"
        val state = part?.get("state")?.jsonObject
        val status = state?.get("status")?.jsonPrimitive?.contentOrNull
        // Surface the tool input so the feed shows the args (what the agent
        // ran), not just the bare tool name.
        val inputObj = state?.get("input")?.jsonObject
        val input = if (inputObj != null && inputObj.isNotEmpty()) inputObj.toString() else ""
        val start = if (input.isEmpty()) listOf(SessionEvent.ToolCalled(tool))
                    else listOf(SessionEvent.ToolCalled(tool), SessionEvent.ToolInput(input))
        return when (status) {
            "completed" -> start + SessionEvent.ToolFinished(callID, true)
            "error", "failed" -> start + SessionEvent.ToolFinished(callID, false)
            else -> start
        }
    }

    private fun errorMessage(root: JsonObject): String {
        val err = root["error"]?.jsonObject
        return err?.get("data")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
            ?: err?.get("message")?.jsonPrimitive?.contentOrNull
            ?: "run failed"
    }
}
