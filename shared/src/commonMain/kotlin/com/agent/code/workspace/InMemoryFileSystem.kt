package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath

class InMemoryFileSystem : FileSystemProvider {
    private val files = mutableMapOf<String, String>()

    override fun read(path: VirtualPath): String =
        files[path.rawPath] ?: error("No such file: ${path.rawPath}")

    override fun write(path: VirtualPath, content: String) {
        files[path.rawPath] = content
    }

    override fun exists(path: VirtualPath): Boolean = files.containsKey(path.rawPath)
}
