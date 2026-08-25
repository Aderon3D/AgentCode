package com.agent.code.core.journal

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class FileBackedWalStore(private val file: File) : WalStore {
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
    }

    // ponytail: at startup, drop torn/unparseable lines so a half-written
    // record can't poison every future replay. Writes to a sibling temp file,
    // fsyncs it, then atomically replaces the WAL — a crash mid-rewrite can't
    // destroy valid recovery records. Returns how many lines pruned.
    override fun selfHeal(): Int = synchronized(lock) {
        if (!file.exists()) return 0
        val lines = file.readLines()
        val (good, bad) = lines.partition { it.isNotBlank() && safeJson(it) }
        if (bad.isEmpty()) return 0
        val tmp = File(file.parentFile ?: File("."), "${file.name}.heal-${System.nanoTime()}")
        try {
            FileOutputStream(tmp).use { os ->
                good.forEach {
                    os.write((it + "\n").toByteArray(StandardCharsets.UTF_8))
                    os.flush()
                }
                os.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                // ponytail: cross-device fallback (rename can't span filesystems)
                FileOutputStream(file).use { os ->
                    good.forEach {
                        os.write((it + "\n").toByteArray(StandardCharsets.UTF_8))
                        os.flush()
                    }
                    os.fd.sync()
                }
                tmp.delete()
            }
        } catch (e: Exception) {
            tmp.delete()
            throw e
        }
        bad.size
    }

    private fun safeJson(line: String): Boolean {
        if (line.isBlank()) return false
        return try {
            val element = eventJson.parseToJsonElement(line)
            // ponytail: every AgentEvent is a JSON object carrying eventId; this
            // rejects torn lines, primitives, arrays, and {} without rejecting
            // valid (but non-decoded) records
            element is JsonObject && element["eventId"] != null
        } catch (_: Exception) {
            false
        }
    }

    override fun append(serialized: String) = synchronized(lock) {
        file.parentFile?.mkdirs()
        // ponytail: single-line append + fsync; share a FileChannel if concurrent
        // writers ever become a real concern.
        FileOutputStream(file, true).use { os ->
            os.write((serialized + "\n").toByteArray(StandardCharsets.UTF_8))
            os.flush()
            os.fd.sync()
        }
    }

    override fun replay(): List<String> = synchronized(lock) {
        if (!file.exists()) emptyList() else file.readLines().filter { it.isNotBlank() }
    }

    override fun clear() = synchronized(lock) {
        if (file.exists()) {
            FileOutputStream(file).use { os -> os.fd.sync() }
        }
    }
}
