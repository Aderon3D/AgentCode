package com.agent.code.core.journal

import java.io.File

class FileBackedWalStore(private val file: File) : WalStore {
    init {
        file.parentFile?.mkdirs()
    }

    override fun append(serialized: String) {
        // ponytail: single-line append; atomic for small writes on local FS,
        // upgrade to channel/fsync if durability under concurrency matters.
        file.appendText(serialized + "\n")
    }

    override fun replay(): List<String> =
        if (!file.exists()) emptyList() else file.readLines().filter { it.isNotBlank() }

    override fun clear() {
        if (file.exists()) {
            file.writeText("")
        }
    }
}
