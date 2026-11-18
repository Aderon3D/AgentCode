package com.agent.code.core.conversation

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/**
 * SQLite-backed conversations (TDD §13): one `Conversation` row per opencode
 * session (`id` = sessionID), one `Message` row per user goal and one per raw
 * run event. Raw lines are stored, not parsed UI, so `AgentBrain.restore`
 * replays them through the same parser a live run uses.
 *
 * Replaces the JSON-lines file store: pass the old store as [legacy] and it is
 * imported on the first [load], then cleared.
 *
 * ponytail: one Mutex instead of a connection pool, and a hard message cap per
 * conversation — the app writes one turn at a time and reads once per launch.
 */
class SqlConversationStore(
    private val db: SqlDb,
    private val legacy: ConversationStore? = null,
    private val maxMessages: Int = MAX_MESSAGES,
) : ConversationStore {

    private val mutex = Mutex()

    init {
        // rowid breaks updatedAt ties: two conversations opened in the same
        // millisecond must still restore newest-first.
        // CREATE ... IF NOT EXISTS keeps this idempotent; user_version is the
        // hook for the M10 tables (runs, receipts) to add columns later.
        db.exec(TABLE_CONVERSATION)
        db.exec(TABLE_MESSAGE)
        db.exec("CREATE INDEX IF NOT EXISTS MessageByConversation ON Message(conversationId, id)")
        db.exec("PRAGMA user_version = $SCHEMA_VERSION")
    }

    override suspend fun load(): Conversation = mutex.withLock {
        migrateLegacy()
        val conversationId = db.query("SELECT id FROM Conversation ORDER BY updatedAt DESC, rowid DESC LIMIT 1")
            .firstOrNull()?.text(0)
            ?: return@withLock Conversation(null, emptyList())
        val rows = db.query(
            "SELECT role, content FROM Message WHERE conversationId = ? ORDER BY id",
            listOf(conversationId),
        )
        val turns = mutableListOf<Pair<String, MutableList<String>>>()
        for (row in rows) {
            val content = row.text(1) ?: continue
            if (row.text(0) == ROLE_USER) {
                turns += content to mutableListOf()
            } else {
                turns.lastOrNull()?.second?.add(content)
            }
        }
        Conversation(
            // Conversation.id doubles as the session id only when the run
            // reported one; "-s local" would be an invalid session.
            sessionId = conversationId.takeIf { it.startsWith(SESSION_PREFIX) },
            turns = turns.map { Turn(it.first, it.second) },
        )
    }

    override suspend fun append(turn: Turn, sessionId: String?) {
        mutex.withLock { appendLocked(turn, sessionId) }
    }

    override suspend fun clear() {
        mutex.withLock {
            db.update("DELETE FROM Message")
            db.update("DELETE FROM Conversation")
        }
    }

    @Suppress("TooGenericExceptionCaught") // boundary: roll a half-written turn back, then rethrow
    private fun appendLocked(turn: Turn, sessionId: String?) {
        val id = sessionId ?: newestConversationId() ?: LOCAL_ID
        val now = Clock.System.now().toEpochMilliseconds()
        db.exec("BEGIN")
        try {
            db.update(
                "INSERT OR IGNORE INTO Conversation (id, title, createdAt, updatedAt, lastModel) VALUES (?,?,?,?,NULL)",
                listOf(id, turn.goal.lineSequence().firstOrNull().orEmpty().take(TITLE_CHARS), now, now),
            )
            db.update("UPDATE Conversation SET updatedAt = ? WHERE id = ?", listOf(now, id))
            db.update(
                "INSERT INTO Message (conversationId, role, content, createdAt) VALUES (?,?,?,?)",
                listOf(id, ROLE_USER, turn.goal, now),
            )
            for (line in turn.lines) {
                db.update(
                    "INSERT INTO Message (conversationId, role, content, createdAt) VALUES (?,?,?,?)",
                    listOf(id, ROLE_EVENT, line, now),
                )
            }
            db.update(
                "DELETE FROM Message WHERE conversationId = ? AND id NOT IN " +
                    "(SELECT id FROM Message WHERE conversationId = ? ORDER BY id DESC LIMIT ?)",
                listOf(id, id, maxMessages),
            )
            db.exec("COMMIT")
        } catch (e: Exception) {
            db.exec("ROLLBACK")
            throw e
        }
    }

    /** Caller holds the mutex. */
    private suspend fun migrateLegacy() {
        val legacy = legacy ?: return
        val conversations = db.query("SELECT COUNT(*) FROM Conversation").firstOrNull()?.long(0) ?: 0L
        if (conversations > 0L) return
        val saved = legacy.load()
        if (saved.turns.isEmpty()) return
        saved.turns.forEach { appendLocked(it, saved.sessionId) }
        legacy.clear()
    }

    private fun newestConversationId(): String? =
        db.query("SELECT id FROM Conversation ORDER BY updatedAt DESC, rowid DESC LIMIT 1").firstOrNull()?.text(0)

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_MESSAGES = 2_000
        private const val TITLE_CHARS = 80
        private const val ROLE_USER = "user"
        private const val ROLE_EVENT = "event"
        private const val SESSION_PREFIX = "ses_"
        private const val LOCAL_ID = "local"

        private const val TABLE_CONVERSATION =
            "CREATE TABLE IF NOT EXISTS Conversation (" +
                "id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, createdAt INTEGER NOT NULL, " +
                "updatedAt INTEGER NOT NULL, lastModel TEXT)"

        private const val TABLE_MESSAGE =
            "CREATE TABLE IF NOT EXISTS Message (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, conversationId TEXT NOT NULL, " +
                "role TEXT NOT NULL, content TEXT NOT NULL, toolName TEXT, toolInput TEXT, " +
                "toolOutput TEXT, ok INTEGER, createdAt INTEGER NOT NULL)"
    }
}
