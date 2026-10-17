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

data class PatchOperation(val searchBlock: String, val replaceBlock: String)

sealed interface FileNode {
    val path: VirtualPath
    val name: String
    data class File(override val path: VirtualPath, override val name: String, val sizeBytes: Long, val lastModifiedMs: Long) : FileNode
    data class Directory(override val path: VirtualPath, override val name: String, val children: List<FileNode>) : FileNode
}

interface FileSystemProvider {
    suspend fun read(path: VirtualPath): Result<String>
    /**
     * Read at most the last [maxChars] characters of [path] (UTF-8). Default
     * reads the whole file and slices; implementations backed by a real file
     * should override to seek (#201: log tails can be multi-MB).
     */
    suspend fun readTail(path: VirtualPath, maxChars: Int): Result<String> =
        read(path).map { if (it.length <= maxChars) it else it.takeLast(maxChars) }
    /**
     * Read [path] from byte offset [fromByteOffset] to the end (UTF-8). Default
     * reads the whole file and drops [fromByteOffset] chars — correct only for
     * the ASCII in-memory providers; real-file implementations seek. Lets a
     * reader tail an append-only file without re-decoding it every poll (#201).
     * [fromByteOffset] must land on a character boundary.
     */
    suspend fun readFrom(path: VirtualPath, fromByteOffset: Long): Result<String> =
        read(path).map { if (fromByteOffset <= 0) it else it.drop(fromByteOffset.coerceAtMost(it.length.toLong()).toInt()) }
    suspend fun write(path: VirtualPath, content: String): Result<Unit>
    suspend fun exists(path: VirtualPath): Boolean
    suspend fun delete(path: VirtualPath): Result<Unit>
    suspend fun applyPatch(path: VirtualPath, patches: List<PatchOperation>): Result<Unit>
    suspend fun walkTree(root: VirtualPath, maxDepth: Int = 10, ignorePatterns: List<String> = listOf(".git", "build", "node_modules", ".gradle")): Result<FileNode.Directory>
}
