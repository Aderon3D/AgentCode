package com.agent.code.opencode

import com.agent.code.core.fsm.ToolCall
import com.agent.code.core.journal.AgentEvent
import com.agent.code.core.journal.AgentEventJournal
import com.agent.code.core.journal.LogEntry
import com.agent.code.core.journal.TelemetryEngine
import com.agent.code.core.path.VirtualPath
import com.agent.code.mcp.McpHost
import com.agent.code.provider.ChatMessage
import com.agent.code.provider.LlmEvent
import com.agent.code.provider.LlmRequest
import com.agent.code.provider.Role
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class AgentConfig(
    val maxIterations: Int = 20,
    val maxToolCallsPerIteration: Int = 5
)

sealed interface BrainEvent {
    data class Thinking(val text: String) : BrainEvent
    data class ToolCallStarted(val toolName: String, val args: String) : BrainEvent
    data class ToolCallFinished(val toolName: String, val success: Boolean) : BrainEvent
    data class IterationComplete(val iteration: Int) : BrainEvent
    data class TaskComplete(val summary: String) : BrainEvent
    data class TaskFailed(val reason: String) : BrainEvent
}

class AgentBrain(
    private val client: OpenCodeClient,
    private val mcp: McpHost,
    private val journal: AgentEventJournal,
    private val telemetry: TelemetryEngine,
    private val config: AgentConfig = AgentConfig()
) {
    private var seq = 0L
    private fun nextId(): Long = ++seq
    private fun now(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()

    fun executeTask(
        taskId: String,
        goal: String,
        workspaceRoot: VirtualPath
    ) = channelFlow {
        val history = mutableListOf<ChatMessage>()
        history.add(ChatMessage(Role.SYSTEM, systemPrompt(workspaceRoot)))
        history.add(ChatMessage(Role.USER, goal))

        journal.append(AgentEvent.TaskStarted(nextId(), taskId, now(), goal))
        telemetry.emit(LogEntry.AgentThought(now(), "Starting: $goal"))

        for (iteration in 1..config.maxIterations) {
            val request = LlmRequest(
                modelId = "mimo-v2.5-free",
                messages = history
            )

            val content = StringBuilder()
            val toolCalls = mutableListOf<PendingToolCall>()

            try {
                client.streamChat(request).collect { event ->
                    when (event) {
                        is LlmEvent.ContentChunk -> content.append(event.text)
                        is LlmEvent.ReasoningChunk -> {
                            send(BrainEvent.Thinking(event.text))
                            telemetry.emit(LogEntry.AgentThought(now(), event.text))
                        }
                        is LlmEvent.ToolCallChunk -> {
                            toolCalls.add(PendingToolCall(event.id, event.name, event.jsonArgsDelta))
                            telemetry.emit(LogEntry.ToolCallStarted(now(), event.name, event.jsonArgsDelta))
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) {
                send(BrainEvent.TaskFailed("LLM error: ${e.message}"))
                return@channelFlow
            }

            val assistantText = content.toString()

            if (toolCalls.isEmpty()) {
                val done = parseDone(assistantText)
                if (done != null) {
                    send(BrainEvent.TaskComplete(done))
                    journal.append(AgentEvent.TaskSucceeded(nextId(), taskId, now(), done))
                    return@channelFlow
                }
                history.add(ChatMessage(Role.ASSISTANT, assistantText))
                history.add(ChatMessage(Role.USER,
                    "No tool calls. Either call tools or respond with {\"done\":true,\"summary\":\"...\"}"))
                continue
            }

            history.add(ChatMessage(Role.ASSISTANT, assistantText + "\n\n" + formatToolCalls(toolCalls)))

            val results = mutableListOf<String>()
            for ((i, pending) in toolCalls.withIndex()) {
                if (i >= config.maxToolCallsPerIteration) break
                send(BrainEvent.ToolCallStarted(pending.name, pending.arguments))
                journal.append(AgentEvent.ToolExecutionRequested(
                    nextId(), taskId, now(), ToolCall(pending.id, pending.name, pending.arguments)))

                val result = mcp.dispatch(ToolCall(pending.id, pending.name, pending.arguments))

                journal.append(AgentEvent.ToolExecutionFinished(nextId(), taskId, now(), result))
                send(BrainEvent.ToolCallFinished(pending.name, result.isSuccess))
                telemetry.emit(LogEntry.ToolCallStarted(now(), pending.name,
                    if (result.isSuccess) "OK" else "FAIL: ${result.output.take(200)}"))

                results.add("Tool ${pending.name} (${pending.id}) [${if (result.isSuccess) "ok" else "err"}]: ${result.output.take(4000)}")
            }

            history.add(ChatMessage(Role.TOOL, results.joinToString("\n\n")))
            send(BrainEvent.IterationComplete(iteration))
        }

        send(BrainEvent.TaskFailed("Max iterations reached"))
    }

    private fun systemPrompt(workspaceRoot: VirtualPath): String = """
        |You are an autonomous coding agent.
        |Workspace: ${workspaceRoot.rawPath}
        |Tools: ${mcp.listTools().joinToString(", ")}
        |
        |Call tools with JSON:
        |```json
        |{"tool_calls":[{"id":"tc1","name":"tool_name","arguments":"{\"arg\":\"val\"}"}]}
        |```
        |
        |When done:
        |```json
        |{"done":true,"summary":"what was done"}
        ```
    """.trimMargin()

    private fun formatToolCalls(calls: List<PendingToolCall>): String {
        val arr = buildJsonArray {
            for (tc in calls) {
                add(buildJsonObject {
                    put("id", JsonPrimitive(tc.id))
                    put("name", JsonPrimitive(tc.name))
                    put("arguments", JsonPrimitive(tc.arguments))
                })
            }
        }
        return buildJsonObject { put("tool_calls", arr) }.toString()
    }

    private fun parseDone(text: String): String? {
        return try {
            val m = Regex("""\{[^}]*"done"\s*:\s*true[^}]*\}""").find(text) ?: return null
            val j = Json.parseToJsonElement(m.value).jsonObject
            if (j["done"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true)
                j["summary"]?.jsonPrimitive?.contentOrNull ?: "Done"
            else null
        } catch (_: Exception) { null }
    }
}

private data class PendingToolCall(val id: String, val name: String, val arguments: String)
