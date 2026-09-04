package com.agent.code.opencode

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

actual object PlatformOps {
    actual fun createDirectories(path: String) {
        File(path).mkdirs()
    }

    actual fun downloadFile(url: String, destPath: String) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.connect()
        conn.inputStream.use { input ->
            File(destPath).outputStream().use { output ->
                input.copyTo(output)
            }
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
        File(path).delete()
    }

    actual fun setExecutable(path: String) {
        File(path).setExecutable(true)
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
