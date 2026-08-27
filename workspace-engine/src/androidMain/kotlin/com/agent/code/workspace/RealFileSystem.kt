package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets

class RealFileSystem(private val root: VirtualPath) : FileSystemProvider {
    private val canonicalRoot = File(root.rawPath).canonicalPath

    private fun confinedFile(path: VirtualPath): Result<File> {
        val raw = File(path.rawPath)
        val canonical = if (raw.isAbsolute) raw.canonicalPath else File(canonicalRoot, path.rawPath).canonicalPath
        val confined = canonical == canonicalRoot || canonical.startsWith("$canonicalRoot${File.separator}")
        if (!confined) {
            return Result.failure(FileError.PermissionDenied(path, "path escapes workspace root: $canonical"))
        }
        return Result.success(File(canonical))
    }

    override suspend fun read(path: VirtualPath): Result<String> = confinedFile(path).fold(
        onSuccess = { file ->
            try {
                if (!file.exists()) {
                    Result.failure(FileError.NotFound(path, "file not found"))
                } else {
                    Result.success(file.readText(StandardCharsets.UTF_8))
                }
            } catch (e: SecurityException) {
                Result.failure(FileError.PermissionDenied(path, e.message ?: "read denied"))
            } catch (e: FileNotFoundException) {
                Result.failure(FileError.NotFound(path, "file not found"))
            } catch (e: Exception) {
                Result.failure(FileError.IOError(path, e.message ?: "read failed", e))
            }
        },
        onFailure = { Result.failure(it) }
    )

    override suspend fun write(path: VirtualPath, content: String): Result<Unit> = confinedFile(path).fold(
        onSuccess = { target ->
            try {
                val parent = target.parentFile ?: File(".")
                parent.mkdirs()
                val tmp = File(parent, "${target.name}.tmp-${System.nanoTime()}")
                try {
                    tmp.outputStream().use { os ->
                        os.write(content.toByteArray(StandardCharsets.UTF_8))
                        os.flush()
                        os.fd.sync()
                    }
                    if (!tmp.renameTo(target)) {
                        target.writeText(content, StandardCharsets.UTF_8)
                        tmp.delete()
                    }
                    Result.success(Unit)
                } catch (e: Exception) {
                    tmp.delete()
                    throw e
                }
            } catch (e: SecurityException) {
                Result.failure(FileError.PermissionDenied(path, e.message ?: "write denied"))
            } catch (e: Exception) {
                Result.failure(FileError.IOError(path, e.message ?: "write failed", e))
            }
        },
        onFailure = { Result.failure(it) }
    )

    override suspend fun exists(path: VirtualPath): Boolean =
        confinedFile(path).fold({ it.exists() }, { false })

    override suspend fun delete(path: VirtualPath): Result<Unit> = confinedFile(path).fold(
        onSuccess = { file ->
            try {
                if (file.delete()) {
                    Result.success(Unit)
                } else {
                    Result.failure(FileError.IOError(path, "delete failed: not a file or not found"))
                }
            } catch (e: SecurityException) {
                Result.failure(FileError.PermissionDenied(path, e.message ?: "delete denied"))
            } catch (e: Exception) {
                Result.failure(FileError.IOError(path, e.message ?: "delete failed", e))
            }
        },
        onFailure = { Result.failure(it) }
    )

    override suspend fun applyPatch(path: VirtualPath, patches: List<PatchOperation>): Result<Unit> {
        val original = read(path).getOrElse { return Result.failure(it) }
        var content = original
        for ((index, patch) in patches.withIndex()) {
            if (!content.contains(patch.searchBlock)) {
                return Result.failure(FileError.PatchFailed(path, "search block #${index + 1} not found in ${path.rawPath}"))
            }
            content = content.replaceFirst(patch.searchBlock, patch.replaceBlock)
        }
        return write(path, content)
    }

    override suspend fun walkTree(root: VirtualPath, maxDepth: Int, ignorePatterns: List<String>): Result<FileNode.Directory> {
        return confinedFile(root).fold(
            onSuccess = { dir ->
                if (!dir.exists()) return Result.failure(FileError.NotFound(root, "directory not found"))
                if (!dir.isDirectory) return Result.failure(FileError.NotFound(root, "not a directory"))
                Result.success(buildTree(dir, root, 0, maxDepth, ignorePatterns))
            },
            onFailure = { Result.failure(it) }
        )
    }

    private fun buildTree(file: File, virtualPath: VirtualPath, depth: Int, maxDepth: Int, ignorePatterns: List<String>): FileNode.Directory {
        val children = file.listFiles()
            ?.filter { child ->
                val name = child.name
                ignorePatterns.none { ig -> name == ig || name.startsWith("$ig/") }
            }
            ?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name })
            ?.takeIf { depth < maxDepth }
            ?.map { child ->
                val childPath = virtualPath.resolve(child.name)
                if (child.isDirectory) {
                    buildTree(child, childPath, depth + 1, maxDepth, ignorePatterns)
                } else {
                    FileNode.File(childPath, child.name, child.length(), child.lastModified())
                }
            }
            ?: emptyList()
        return FileNode.Directory(virtualPath, virtualPath.fileName, children)
    }
}
