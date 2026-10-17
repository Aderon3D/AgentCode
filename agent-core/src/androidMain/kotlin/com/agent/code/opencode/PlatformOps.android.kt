package com.agent.code.opencode

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

actual object PlatformOps {
    actual fun createDirectories(path: String) {
        val dir = File(path)
        if (dir.exists()) return
        if (!dir.mkdirs() && !dir.exists()) {
            throw IllegalStateException("Failed to create directory: $path")
        }
    }

    actual fun downloadFile(url: String, destPath: String) {
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        try {
            conn.connect()
            // Follow redirects manually (HttpURLConnection doesn't cross-domain)
            var redirects = 0
            while (conn.responseCode in 300..399 && redirects < 5) {
                val location = conn.getHeaderField("Location") ?: break
                val base = conn.url
                conn.disconnect()
                conn = URL(base, location).openConnection() as HttpURLConnection
                conn.instanceFollowRedirects = true
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.connect()
                redirects++
            }
            if (conn.responseCode !in 200..299) {
                val body = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw IllegalStateException("HTTP ${conn.responseCode}: ${body.take(200)}")
            }
            conn.inputStream.use { input ->
                File(destPath).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    actual fun extractGzip(sourcePath: String, destPath: String) {
        File(sourcePath).inputStream().use { gzInput ->
            GZIPInputStream(gzInput).use { input ->
                File(destPath).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
    }

    actual fun deleteFile(path: String) {
        if (!File(path).delete() && File(path).exists()) {
            throw IllegalStateException("Failed to delete $path")
        }
    }

    actual fun setExecutable(path: String) {
        if (!File(path).setExecutable(true)) {
            throw IllegalStateException("Failed to set executable: $path")
        }
    }

    actual fun currentTimeMs(): Long = System.currentTimeMillis()

    actual fun httpGet(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): Int {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = connectTimeoutMs
        conn.readTimeout = readTimeoutMs
        return try {
            conn.responseCode
        } finally {
            try { conn.inputStream?.close() } catch (_: Exception) {}
            conn.disconnect()
        }
    }

    actual fun spawnLdLinux(
        loaderPath: String,
        glibcDir: String,
        binaryPath: String,
        workDir: String,
        logPath: String,
        args: Array<String>
    ): Int = ElfLoader.nativeSpawn(loaderPath, glibcDir, binaryPath, workDir, logPath, args)

    actual fun checkChild(pid: Int): Int = ElfLoader.nativeCheck(pid)
}
