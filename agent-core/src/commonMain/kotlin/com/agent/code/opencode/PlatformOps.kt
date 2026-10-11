package com.agent.code.opencode

expect object PlatformOps {
    fun createDirectories(path: String)
    fun downloadFile(url: String, destPath: String)
    fun extractGzip(sourcePath: String, destPath: String)
    fun deleteFile(path: String)
    fun setExecutable(path: String)
    fun currentTimeMs(): Long
    fun httpGet(url: String, connectTimeoutMs: Int, readTimeoutMs: Int): Int
    fun spawnLdLinux(
        loaderPath: String,
        glibcDir: String,
        binaryPath: String,
        workDir: String,
        logPath: String,
        args: Array<String>
    ): Int
    fun checkChild(pid: Int): Int
}
