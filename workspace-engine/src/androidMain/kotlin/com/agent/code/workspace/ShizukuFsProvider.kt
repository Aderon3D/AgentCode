package com.agent.code.workspace

import android.content.pm.PackageManager
import com.agent.code.core.path.VirtualPath
import java.io.File
import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import rikka.shizuku.Shizuku

// ponytail: real Shizuku-backed FS I/O. Shizuku.newProcess is private/deprecated
// in API 13.x (slated for removal in API 14), so we reach it via reflection to
// keep the sanctioned privileged-shell behavior from the doc. Paths inside the
// sandbox use the local RealFileSystem; only out-of-sandbox paths are escalated.
// Any failure (Shizuku absent, permission missing, method gone) falls back.
// TODO: when bumping to Shizuku API 14, migrate to bindUserService + AIDL.
class ShizukuFsProvider(
    private val root: VirtualPath,
    private val fallback: RealFileSystem
) : FileSystemProvider {

    // Single-quote a path so it cannot break out of the shell command.
    private fun shellArg(path: String): String =
        "'${path.replace("'", "'\\''")}'"

    private fun isSandboxed(path: VirtualPath): Boolean {
        val raw = File(path.rawPath).canonicalPath
        val canonicalRoot = File(root.rawPath).canonicalPath
        return raw.startsWith("$canonicalRoot${File.separator}") || raw == canonicalRoot
    }

    private fun isAvailable(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private val newProcessMethod: Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    private fun shizuku(command: String): Process? {
        val m = newProcessMethod ?: return null
        return try {
            m.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun read(path: VirtualPath): Result<String> {
        if (!isAvailable() || isSandboxed(path)) return fallback.read(path)
        val p = shizuku("cat ${shellArg(path.rawPath)}") ?: return fallback.read(path)
        val out = p.inputStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val err = p.errorStream.bufferedReader(StandardCharsets.UTF_8).readText()
        val rc = p.waitFor()
        return if (rc == 0) {
            Result.success(out)
        } else {
            Result.failure(FileError.IOError(path, "shizuku read failed (exit $rc): $err"))
        }
    }

    override suspend fun write(path: VirtualPath, content: String): Result<Unit> {
        if (!isAvailable() || isSandboxed(path)) return fallback.write(path, content)
        val p = shizuku("cat > ${shellArg(path.rawPath)}") ?: return fallback.write(path, content)
        return try {
            p.outputStream.use { os ->
                os.write(content.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }
            val rc = p.waitFor()
            if (rc == 0) {
                Result.success(Unit)
            } else {
                val err = p.errorStream.bufferedReader(StandardCharsets.UTF_8).readText()
                Result.failure(FileError.IOError(path, "shizuku write failed (exit $rc): $err"))
            }
        } catch (e: Throwable) {
            Result.failure(FileError.IOError(path, e.message ?: "shizuku write failed", e))
        }
    }

    override suspend fun exists(path: VirtualPath): Boolean {
        if (!isAvailable() || isSandboxed(path)) return fallback.exists(path)
        val p = shizuku("test -e ${shellArg(path.rawPath)}") ?: return fallback.exists(path)
        return p.waitFor() == 0
    }

    // ponytail: ephemeral askpass/secret files are sandboxed, so delegate delete
    // to the confined RealFileSystem; escalate to shizuku rm only if needed later.
    override suspend fun delete(path: VirtualPath): Result<Unit> = fallback.delete(path)

    override suspend fun applyPatch(path: VirtualPath, patches: List<PatchOperation>): Result<Unit> =
        fallback.applyPatch(path, patches)

    override suspend fun walkTree(root: VirtualPath, maxDepth: Int, ignorePatterns: List<String>): Result<FileNode.Directory> =
        fallback.walkTree(root, maxDepth, ignorePatterns)
}
