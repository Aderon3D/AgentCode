package com.agent.code.core.conversation

/** One result row, addressed by column index (0-based). */
class SqlRow(private val values: List<Any?>) {
    fun text(index: Int): String? = when (val value = values.getOrNull(index)) {
        null -> null
        is String -> value
        else -> value.toString()
    }

    fun long(index: Int): Long? = when (val value = values.getOrNull(index)) {
        null -> null
        is Number -> value.toLong()
        else -> value.toString().toLongOrNull()
    }
}

/**
 * The three operations the conversation store needs.
 *
 * Keeping the SQL out of the Android API is what lets a host test run the real
 * statements on a real SQLite engine (androidHostTest, JDBC) while the app uses
 * the system SQLite — the SQL is the part that must be right.
 */
interface SqlDb {
    fun exec(sql: String)
    fun query(sql: String, args: List<Any?> = emptyList()): List<SqlRow>
    fun update(sql: String, args: List<Any?> = emptyList())
}
