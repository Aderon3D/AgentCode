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
            throw RuntimeException("Failed to create directory: $path")
        }
    }

    actual fun downloadFile(url: String, destPath: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.connect()
        try {
            if (conn.responseCode !in 200..299) {
                val body = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw RuntimeException("HTTP ${conn.responseCode}: $body")
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
            throw RuntimeException("Failed to delete $path")
        }
    }

    actual fun setExecutable(path: String) {
        if (!File(path).setExecutable(true)) {
            throw RuntimeException("Failed to set executable: $path")
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
            conn.disconnect()
        }
    }
}
