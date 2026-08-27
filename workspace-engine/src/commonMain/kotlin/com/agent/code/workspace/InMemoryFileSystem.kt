package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath

class InMemoryFileSystem : FileSystemProvider {
    private val files: MutableMap<String, String> = java.util.Collections.synchronizedMap(mutableMapOf())

    override suspend fun read(path: VirtualPath): Result<String> =
        files[path.rawPath]?.let { Result.success(it) }
            ?: Result.failure(FileError.NotFound(path, "No such file: ${path.rawPath}"))

    override suspend fun write(path: VirtualPath, content: String): Result<Unit> {
        files[path.rawPath] = content
        return Result.success(Unit)
    }

    override suspend fun exists(path: VirtualPath): Boolean = files.containsKey(path.rawPath)

    override suspend fun delete(path: VirtualPath): Result<Unit> {
        return if (files.remove(path.rawPath) != null) {
            Result.success(Unit)
        } else {
            Result.failure(FileError.NotFound(path, "No such file: ${path.rawPath}"))
        }
    }

    override suspend fun applyPatch(path: VirtualPath, patches: List<PatchOperation>): Result<Unit> {
        val original = files[path.rawPath]
            ?: return Result.failure(FileError.NotFound(path, "No such file: ${path.rawPath}"))
        var content = original
        for ((index, patch) in patches.withIndex()) {
            if (!content.contains(patch.searchBlock)) {
                return Result.failure(FileError.PatchFailed(path, "search block #${index + 1} not found in ${path.rawPath}"))
            }
            content = content.replaceFirst(patch.searchBlock, patch.replaceBlock)
        }
        files[path.rawPath] = content
        return Result.success(Unit)
    }

    override suspend fun walkTree(root: VirtualPath, maxDepth: Int, ignorePatterns: List<String>): Result<FileNode.Directory> {
        val rootChildren = files.keys
            .filter { it.startsWith("${root.rawPath}/") }
            .map { it.removePrefix("${root.rawPath}/") }
            .filter { part -> ignorePatterns.none { ig -> part.startsWith(ig) || part.contains("/$ig/") } }
            .map { part ->
                val name = part.substringBefore('/')
                val fullPath = VirtualPath.of("${root.rawPath}/$name")
                val subParts = part.substringAfter('/', "")
                if (subParts.isEmpty()) {
                    // Direct file
                    FileNode.File(fullPath, name, (files["${root.rawPath}/$name"] ?: "").length.toLong(), 0L)
                } else {
                    // Nested — collect as directory
                    FileNode.Directory(fullPath, name, emptyList())
                }
            }
        // Deduplicate directories
        val deduped = rootChildren.distinctBy { it.name }
        return Result.success(FileNode.Directory(root, root.rawPath.substringAfterLast('/'), deduped))
    }

    // Test helper
    fun put(path: VirtualPath, content: String) {
        files[path.rawPath] = content
    }
}
