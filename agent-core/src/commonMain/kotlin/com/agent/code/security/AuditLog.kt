package com.agent.code.security

import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.FileSystemProvider
import kotlinx.coroutines.CancellationException
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

    private var fileContent = ""

    fun init(fileSystem: FileSystemProvider?, logDir: VirtualPath?) {
        val oldDeferred = loaded
        loaded = CompletableDeferred()
        this.fileSystem = fileSystem
        this.logPath = logDir?.resolve("audit-log.jsonl")
        if (fileSystem != null && logDir != null) {
            val deferred = loaded
            val fs = fileSystem
            val path = this.logPath!!
            scope.launch {
                try {
                    load(fs, path)
                } finally {
                    deferred.complete(Unit)
                    oldDeferred.complete(Unit)
                }
            }
        } else {
            loaded.complete(Unit)
            oldDeferred.complete(Unit)
        }
    }

    private suspend fun load(fs: FileSystemProvider, path: VirtualPath) {
        runCatching {
            val content = fs.read(path).getOrNull() ?: ""
            fileContent = content
            val parsed = content.lines().filter { it.isNotBlank() }.mapNotNull { line ->
                runCatching { json.decodeFromString<AuditEntry>(line) }.getOrNull()
            }
            synchronized(entries) {
                entries.clear()
                entries.addAll(parsed)
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
            try {
                val line = json.encodeToString(entry) + "\n"
                fileContent += line
                fs.write(path, fileContent)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // I/O failure — entry exists in memory only
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
        fileContent = ""
    }
}
