package com.agent.code.core.tools

import kotlinx.serialization.Serializable

@Serializable
data class ToolResult(
    val toolCallId: String,
    val isSuccess: Boolean,
    val output: String,
    val executionTimeMs: Long
)
