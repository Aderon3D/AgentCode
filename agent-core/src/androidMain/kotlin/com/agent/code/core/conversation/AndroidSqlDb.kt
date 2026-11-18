package com.agent.code.core.conversation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The app's conversation store: system SQLite (no bundled native lib), in the
 * app-private data dir next to the opencode install.
 *
 * [legacy] is the r43 JSON-lines file; it is imported on the first load and
 * then deleted, so an install that upgrades keeps its thread.
 */
fun sqlConversationStore(
    context: Context,
    name: String = "conversations.db",
    legacy: ConversationStore? = null,
): ConversationStore = SqlConversationStore(AndroidSqlDb(context.applicationContext, name), legacy)

/** Schema lives in [SqlConversationStore], so the helper only opens the file. */
private class AndroidSqlDb(context: Context, name: String) : SqlDb {
    private val openHelper = object : SQLiteOpenHelper(context, name, null, 1) {
        override fun onCreate(db: SQLiteDatabase) = Unit
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    override fun exec(sql: String) {
        openHelper.writableDatabase.execSQL(sql)
    }

    override fun query(sql: String, args: List<Any?>): List<SqlRow> =
        openHelper.readableDatabase
            .rawQuery(sql, Array(args.size) { args[it]?.toString().orEmpty() })
            .use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        add(SqlRow((0 until cursor.columnCount).map { i -> if (cursor.isNull(i)) null else cursor.getString(i) }))
                    }
                }
            }

    override fun update(sql: String, args: List<Any?>) {
        openHelper.writableDatabase.execSQL(sql, args.toTypedArray())
    }
}
