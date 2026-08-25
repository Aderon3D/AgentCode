package com.agent.code.core.lock

import com.agent.code.core.path.VirtualPath

data class ActiveTaskLock(
    val agentTaskId: String,
    val branchName: String,
    val lockedFiles: Set<VirtualPath>,
    val lockedSymbolUuids: Set<String>
)
