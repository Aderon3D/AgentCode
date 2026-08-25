package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath

sealed class FileError(
    override val message: String,
    open val path: VirtualPath? = null,
    cause: Throwable? = null
) : Exception(message, cause) {
    class NotFound(path: VirtualPath, message: String) : FileError(message, path)
    class PermissionDenied(path: VirtualPath, message: String) : FileError(message, path)
    class PatchFailed(path: VirtualPath, message: String) : FileError(message, path)
    class IOError(path: VirtualPath?, message: String, cause: Throwable? = null) : FileError(message, path, cause)
}

interface FileSystemProvider {
    fun read(path: VirtualPath): Result<String>
    fun write(path: VirtualPath, content: String): Result<Unit>
    fun exists(path: VirtualPath): Boolean
}
