package com.agent.code.core.journal

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.JsonElement

class FileBackedWalStore(private val file: File) : WalStore {
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
    }

    // ponytail: at startup, drop torn/unparseable lines so a half-written
    // record can't poison every future replay. Returns how many lines pruned.
    override fun selfHeal(): Int = synchronized(lock) {
        if (!file.exists()) return 0
        val lines = file.readLines()
        val (good, bad) = lines.partition { it.isNotBlank() && safeJson(it) }
        if (bad.isEmpty()) return 0
        FileOutputStream(file).use { os ->
            good.forEach {
                os.write((it + "\n").toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }
            os.fd.sync()
        }
        bad.size
    }

    private fun safeJson(line: String): Boolean = try {
        eventJson.decodeFromString<JsonElement>(line)
        true
    } catch (_: Exception) {
        false
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
