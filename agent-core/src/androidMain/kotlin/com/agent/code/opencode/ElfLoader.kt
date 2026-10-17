package com.agent.code.opencode

internal object ElfLoader {
    init {
        System.loadLibrary("elfloader")
    }

    external fun nativeSpawn(
        loader: String,
        glibcDir: String,
        binary: String,
        workDir: String,
        logPath: String,
        args: Array<String>
    ): Int

    external fun nativeCheck(pid: Int): Int
}
