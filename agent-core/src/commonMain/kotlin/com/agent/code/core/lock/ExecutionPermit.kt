package com.agent.code.core.lock

import com.agent.code.core.path.VirtualPath

data class ExecutionPermit(
    val taskId: String,
    val requiresAst3WayMerge: Boolean,
    val overlappingFiles: Set<VirtualPath> = emptySet()
)
