package com.agent.code.workspace

import com.agent.code.core.path.VirtualPath
import java.io.File

// ponytail: compile-check only — runtime requires the Shizuku app installed
// and running on a real device. Falls back to [RealFileSystem] when Shizuku
// is unavailable or the path is already inside the app sandbox (root).
// Shizuku API 13.x does not expose a public process API; the actual
// ShizukuRemoteProcess integration should be done via AIDL when needed.
class ShizukuFsProvider(
    private val root: VirtualPath,
    private val fallback: RealFileSystem
) : FileSystemProvider {

    private fun isSandboxed(path: VirtualPath): Boolean {
        val raw = File(path.rawPath).canonicalPath
        val canonicalRoot = File(root.rawPath).canonicalPath
        return raw.startsWith("$canonicalRoot${File.separator}") || raw == canonicalRoot
    }

    private fun isAvailable(): Boolean = try {
        Class.forName("rikka.shizuku.Shizuku")
        // ponytail: Shizuku.pingBinder() exists but newProcess is private
        // in API 13.x; actual privileged I/O will use AIDL bindings.
        false
    } catch (_: ClassNotFoundException) {
        false
    }

    override fun read(path: VirtualPath): Result<String> {
        if (!isAvailable() || isSandboxed(path)) return fallback.read(path)
        // TODO: Shizuku AIDL read when bindings are wired
        return fallback.read(path)
    }

    override fun write(path: VirtualPath, content: String): Result<Unit> {
        if (!isAvailable() || isSandboxed(path)) return fallback.write(path, content)
        // TODO: Shizuku AIDL write when bindings are wired
        return fallback.write(path, content)
    }

    override fun exists(path: VirtualPath): Boolean {
        if (!isAvailable() || isSandboxed(path)) return fallback.exists(path)
        // TODO: Shizuku AIDL exists when bindings are wired
        return fallback.exists(path)
    }
}
