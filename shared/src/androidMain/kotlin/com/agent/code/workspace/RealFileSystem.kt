package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath
import java.io.File
import java.io.FileNotFoundException
import java.nio.charset.StandardCharsets

class RealFileSystem : FileSystemProvider {
    override fun read(path: VirtualPath): Result<String> = try {
        val file = File(path.rawPath)
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

    override fun write(path: VirtualPath, content: String): Result<Unit> = try {
        val target = File(path.rawPath)
        val parent = target.parentFile ?: File(".")
        parent.mkdirs()
        val tmp = File(parent, "${target.name}.tmp-${System.nanoTime()}")
        try {
            tmp.outputStream().use { os ->
                os.write(content.toByteArray(StandardCharsets.UTF_8))
                os.flush()
                // ponytail: fsync before rename so a crash mid-write can't leave a
                // torn target; upgrade to dir fsync if durability must survive power
                // loss, not just process crash.
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

    override fun exists(path: VirtualPath): Boolean =
        File(path.rawPath).exists()
}
