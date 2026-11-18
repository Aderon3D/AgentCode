package com.agent.code.core.conversation

import kotlinx.coroutines.test.runTest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the store's SQL on a real SQLite engine over JDBC. The app uses the
 * system SQLite through [SqlDb], the test uses the same seam, so the schema and
 * statements here are the ones that ship.
 */
class SqlConversationStoreTest {

    private class JdbcDb : SqlDb, AutoCloseable {
        private val connection: Connection = DriverManager.getConnection("jdbc:sqlite::memory:")

        override fun exec(sql: String) {
            connection.createStatement().use { it.execute(sql) }
        }

        override fun query(sql: String, args: List<Any?>): List<SqlRow> =
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                statement.executeQuery().use { result ->
                    buildList {
                        while (result.next()) {
                            add(SqlRow((1..result.metaData.columnCount).map { result.getObject(it) }))
                        }
                    }
                }
            }

        override fun update(sql: String, args: List<Any?>) {
            connection.prepareStatement(sql).use { statement ->
                args.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                statement.executeUpdate()
            }
        }

        override fun close() = connection.close()
    }

    private class FakeLegacy(private val saved: Conversation) : ConversationStore {
        var cleared = false
        override suspend fun load() = saved
        override suspend fun append(turn: Turn, sessionId: String?) = Unit
        override suspend fun clear() {
            cleared = true
        }
    }

    @Test
    fun roundTripsTurnsAndSessionId() = runTest {
        JdbcDb().use { db ->
            val store = SqlConversationStore(db)
            store.append(Turn("first", listOf("""{"a":1}""")), "ses_1")
            store.append(Turn("second\nline", listOf("""{"b":2}""", """{"c":"x\ny"}""")), "ses_1")

            val loaded = store.load()

            assertEquals("ses_1", loaded.sessionId)
            assertEquals(listOf("first", "second\nline"), loaded.turns.map { it.goal })
            assertEquals(listOf("""{"a":1}"""), loaded.turns[0].lines)
            assertEquals(listOf("""{"b":2}""", """{"c":"x\ny"}"""), loaded.turns[1].lines)
        }
    }

    @Test
    fun newestConversationWinsAndLocalIdIsNotASession() = runTest {
        JdbcDb().use { db ->
            val store = SqlConversationStore(db)
            store.append(Turn("old", listOf("l1")), "ses_old")
            store.append(Turn("new", listOf("l2")), "ses_new")

            assertEquals("ses_new", store.load().sessionId)
            assertEquals(listOf("new"), store.load().turns.map { it.goal })
        }
        JdbcDb().use { db ->
            val store = SqlConversationStore(db)
            store.append(Turn("no session", listOf("l")), null)

            val loaded = store.load()
            assertEquals(null, loaded.sessionId)
            assertEquals(listOf("no session"), loaded.turns.map { it.goal })
        }
    }

    @Test
    fun capsMessagesPerConversationKeepingTheNewest() = runTest {
        JdbcDb().use { db ->
            val store = SqlConversationStore(db, maxMessages = 6)
            repeat(5) { i -> store.append(Turn("goal-$i", listOf("line-$i")), "ses_1") }

            val loaded = store.load()

            assertTrue(loaded.turns.size < 5, "expected old turns dropped, got ${loaded.turns.size}")
            assertEquals("goal-4", loaded.turns.last().goal)
        }
    }

    @Test
    fun importsTheLegacyFileStoreOnce() = runTest {
        val legacy = FakeLegacy(Conversation("ses_legacy", listOf(Turn("old goal", listOf("""{"a":1}""")))))
        JdbcDb().use { db ->
            val store = SqlConversationStore(db, legacy = legacy)

            val loaded = store.load()

            assertEquals("ses_legacy", loaded.sessionId)
            assertEquals(listOf("old goal"), loaded.turns.map { it.goal })
            assertTrue(legacy.cleared, "legacy file must be cleared after import")

            store.append(Turn("next", listOf("l")), "ses_legacy")
            assertEquals(listOf("old goal", "next"), store.load().turns.map { it.goal })
            assertEquals(2, store.load().turns.size, "import must not run twice")
        }
    }

    @Test
    fun clearDropsEverything() = runTest {
        JdbcDb().use { db ->
            val store = SqlConversationStore(db)
            store.append(Turn("hi", listOf("l")), "ses_1")
            store.clear()
            assertEquals(Conversation(null, emptyList()), store.load())
        }
    }
}
