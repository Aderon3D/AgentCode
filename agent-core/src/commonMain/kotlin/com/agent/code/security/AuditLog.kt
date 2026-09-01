package com.agent.code.security

import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable

@Serializable
data class AuditEntry(
    val timestampMs: Long,
    val toolName: String,
    val argumentsSummary: String,
    val success: Boolean,
    val outputSummary: String,
    val blocked: Boolean = false,
    val blockReason: String? = null
)

object AuditLog {
    private val entries = mutableListOf<AuditEntry>()
    private val maxEntries = 1000

    fun record(entry: AuditEntry) {
        synchronized(entries) {
            entries.add(entry)
            if (entries.size > maxEntries) {
                entries.removeAt(0)
            }
        }
    }

    fun log(
        toolName: String,
        argumentsJson: String,
        success: Boolean,
        output: String,
        blocked: Boolean = false,
        blockReason: String? = null
    ) {
        record(AuditEntry(
            timestampMs = Clock.System.now().toEpochMilliseconds(),
            toolName = toolName,
            argumentsSummary = argumentsJson.take(200),
            success = success,
            outputSummary = output.take(500),
            blocked = blocked,
            blockReason = blockReason
        ))
    }

    fun recent(count: Int = 50): List<AuditEntry> {
        synchronized(entries) {
            return entries.takeLast(count)
        }
    }

    fun clear() {
        synchronized(entries) { entries.clear() }
    }
}
