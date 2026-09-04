package com.agent.code.opencode

expect object PlatformOps {
    fun createDirectories(path: String)
    fun downloadFile(url: String, destPath: String)
    fun extractGzip(sourcePath: String, destPath: String)
    fun deleteFile(path: String)
    fun setExecutable(path: String)
    fun currentTimeMs(): Long
    fun httpGet(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): Int
}
