package com.agent.code.workspace

interface ProcessRunner {
    suspend fun run(command: List<String>): String
}
