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
        val rootPrefix = "${root.rawPath}/"
        val allMatching = synchronized(files) {
            files.keys.filter { it.startsWith(rootPrefix) || it == root.rawPath }
                .map { it.removePrefix(root.rawPath).trimStart('/') }
                .filter { it.isNotEmpty() }
                .filter { part -> ignorePatterns.none { ig -> part == ig || part.startsWith("$ig/") || part.contains("/$ig/") } }
        }

        return Result.success(buildTreeFromPaths(root, allMatching, 0, maxDepth, ignorePatterns))
    }

    private fun buildTreeFromPaths(
        basePath: VirtualPath,
        relativePaths: List<String>,
        depth: Int,
        maxDepth: Int,
        ignorePatterns: List<String>
    ): FileNode.Directory {
        if (depth >= maxDepth) {
            return FileNode.Directory(basePath, basePath.fileName, emptyList())
        }

        val directChildren = mutableMapOf<String, MutableList<String>>()
        val directFiles = mutableListOf<String>()

        for (path in relativePaths) {
            val slashIdx = path.indexOf('/')
            if (slashIdx < 0) {
                // Direct file
                directFiles.add(path)
            } else {
                // Nested path — group by first segment
                val dirName = path.substring(0, slashIdx)
                val remainder = path.substring(slashIdx + 1)
                directChildren.getOrPut(dirName) { mutableListOf() }.add(remainder)
            }
        }

        val children = mutableListOf<FileNode>()

        // Add files
        for (fileName in directFiles) {
            val fullPath = basePath.resolve(fileName)
            val content = files["${basePath.rawPath}/$fileName"] ?: ""
            children.add(FileNode.File(fullPath, fileName, content.length.toLong(), 0L))
        }

        // Add directories (recursively)
        for ((dirName, subPaths) in directChildren) {
            val dirPath = basePath.resolve(dirName)
            val dirNode = buildTreeFromPaths(dirPath, subPaths, depth + 1, maxDepth, ignorePatterns)
            children.add(dirNode)
        }

        return FileNode.Directory(basePath, basePath.fileName, children)
    }

    // Test helper
    fun put(path: VirtualPath, content: String) {
        files[path.rawPath] = content
    }
}
