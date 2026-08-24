package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath

class InMemoryFileSystem : FileSystemProvider {
    private val files = mutableMapOf<String, String>()

    override fun read(path: VirtualPath): Result<String> =
        files[path.rawPath]?.let { Result.success(it) }
            ?: Result.failure(FileError.NotFound(path, "No such file: ${path.rawPath}"))

    override fun write(path: VirtualPath, content: String): Result<Unit> {
        files[path.rawPath] = content
        return Result.success(Unit)
    }

    override fun exists(path: VirtualPath): Boolean = files.containsKey(path.rawPath)
}
