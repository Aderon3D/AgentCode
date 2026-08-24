package com.agent.code.core.journal

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

class FileBackedWalStore(private val file: File) : WalStore {
    private val lock = Any()

    init {
        file.parentFile?.mkdirs()
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
