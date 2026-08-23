package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath
import java.io.File

class RealFileSystem : FileSystemProvider {
    override fun read(path: VirtualPath): Result<String> = try {
        Result.success(File(path.rawPath).readText())
    } catch (e: Exception) {
        Result.failure(FileError.IOError(path, e.message ?: "read failed", e))
    }

    override fun write(path: VirtualPath, content: String): Result<Unit> = try {
        val file = File(path.rawPath)
        file.parentFile?.mkdirs()
        file.writeText(content)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(FileError.IOError(path, e.message ?: "write failed", e))
    }

    override fun exists(path: VirtualPath): Boolean =
        File(path.rawPath).exists()
}
