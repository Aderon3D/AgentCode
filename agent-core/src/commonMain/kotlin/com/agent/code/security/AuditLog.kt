package com.agent.code.security

import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.FileSystemProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fileMutex = Mutex()

    @Volatile
    private var fileSystem: FileSystemProvider? = null
    @Volatile
    private var logPath: VirtualPath? = null

    @Volatile
    private var loaded = CompletableDeferred<Unit>()

    fun init(fileSystem: FileSystemProvider?, logDir: VirtualPath?) {
        val oldDeferred = loaded
        loaded = CompletableDeferred()
        this.fileSystem = fileSystem
        this.logPath = logDir?.resolve("audit-log.jsonl")
        if (fileSystem != null && logPath != null) {
            val deferred = loaded
            scope.launch {
                load()
                deferred.complete(Unit)
                oldDeferred.complete(Unit)
            }
        } else {
            loaded.complete(Unit)
            oldDeferred.complete(Unit)
        }
    }

    private suspend fun load() {
        val fs = fileSystem ?: return
        val path = logPath ?: return
        runCatching {
            val content = fs.read(path).getOrNull() ?: return
            synchronized(entries) {
                content.lines().filter { it.isNotBlank() }.forEach { line ->
                    runCatching {
                        entries.add(json.decodeFromString<AuditEntry>(line))
                    }
                }
            }
        }
    }

    suspend fun record(entry: AuditEntry) {
        loaded.await()
        synchronized(entries) {
            entries.add(entry)
            if (entries.size > maxEntries) {
                entries.removeAt(0)
            }
        }
        persistEntry(entry)
    }

    private suspend fun persistEntry(entry: AuditEntry) {
        val fs = fileSystem ?: return
        val path = logPath ?: return
        fileMutex.withLock {
            val line = json.encodeToString(entry) + "\n"
            val existing = fs.read(path).getOrNull()
            if (existing != null) {
                fs.write(path, existing + line)
            } else {
                fs.write(path, line)
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
        scope.launch {
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
