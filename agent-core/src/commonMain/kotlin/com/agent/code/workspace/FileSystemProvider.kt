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
    suspend fun write(path: VirtualPath, content: String): Result<Unit>
    suspend fun exists(path: VirtualPath): Boolean
    suspend fun delete(path: VirtualPath): Result<Unit>
    suspend fun applyPatch(path: VirtualPath, patches: List<PatchOperation>): Result<Unit>
    suspend fun walkTree(root: VirtualPath, maxDepth: Int = 10, ignorePatterns: List<String> = listOf(".git", "build", "node_modules", ".gradle")): Result<FileNode.Directory>
}
