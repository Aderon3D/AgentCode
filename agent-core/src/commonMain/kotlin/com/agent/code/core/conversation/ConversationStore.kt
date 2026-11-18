package com.agent.code.core.conversation

import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.FileSystemProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** One finished turn: what the user asked, and the raw run events that answered it. */
data class Turn(val goal: String, val lines: List<String>)

/** A restorable conversation. [sessionId] is the newest opencode session seen. */
data class Conversation(val sessionId: String?, val turns: List<Turn>)

/**
 * Durable conversation state.
 *
 * Raw run lines are stored, not parsed UI: replay goes back through the same
 * parser that produced them, so a restored transcript cannot drift from a live
 * one. A turn is appended only after a run succeeds — a failed model attempt is
 * not part of the conversation the user saw.
 */
interface ConversationStore {
    suspend fun load(): Conversation
    suspend fun append(turn: Turn, sessionId: String?)
    suspend fun clear()
}

/**
 * JSON-lines file store: one object per turn, UTF-8, newest last.
 *
 * The whole file is rewritten on append (the provider has no append), capped at
 * [MAX_CHARS] by dropping the oldest turns. ponytail: single conversation, no
 * queries, O(file) per write — replace with the M7 SQLDelight tables when more
 * than one conversation must be kept.
 */
class FileConversationStore(
    private val fileSystem: FileSystemProvider,
    private val file: VirtualPath,
    private val maxChars: Int = MAX_CHARS,
) : ConversationStore {

    override suspend fun load(): Conversation {
        val text = fileSystem.read(file).getOrNull() ?: return Conversation(null, emptyList())
        var sessionId: String? = null
        val turns = text.lineSequence().mapNotNull { line ->
            val record = decode(line) ?: return@mapNotNull null
            record.sessionId?.let { sessionId = it }
            record.turn
        }.toList()
        return Conversation(sessionId, turns)
    }

    override suspend fun append(turn: Turn, sessionId: String?) {
        val existing = fileSystem.read(file).getOrNull().orEmpty()
        val encoded = buildList {
            addAll(existing.lineSequence().filter { it.isNotBlank() })
            add(encode(turn, sessionId))
        }
        var start = 0
        var total = encoded.sumOf { it.length + 1 }
        while (total > maxChars && start < encoded.lastIndex) {
            total -= encoded[start].length + 1
            start++
        }
        fileSystem.write(file, encoded.drop(start).joinToString("\n", postfix = "\n"))
    }

    override suspend fun clear() {
        // A missing file is already the cleared state.
        fileSystem.delete(file)
    }

    private data class Record(val sessionId: String?, val turn: Turn)

    private fun encode(turn: Turn, sessionId: String?): String = buildJsonObject {
        sessionId?.let { put("sessionId", it) }
        put("goal", turn.goal)
        putJsonArray("lines") { turn.lines.forEach { add(it) } }
    }.toString()

    private fun decode(line: String): Record? = runCatching {
        val obj = Json.parseToJsonElement(line).jsonObject
        Record(
            sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull,
            turn = Turn(
                goal = obj["goal"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                lines = obj["lines"]?.jsonArray?.map { it.jsonPrimitive.contentOrNull.orEmpty() }.orEmpty(),
            ),
        )
    }.getOrNull()

    companion object {
        /** Keeps the file small enough to read on the UI-adjacent path. */
        const val MAX_CHARS = 1_000_000
    }
}
