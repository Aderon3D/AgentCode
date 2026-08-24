package com.agent.code.core.lock

import com.agent.code.core.path.VirtualPath

sealed interface ConflictRisk {
    object None : ConflictRisk
    data class FileOverlapRequiresMerge(val files: Set<VirtualPath>) : ConflictRisk
    data class FatalSymbolCollision(val symbols: Set<String>) : ConflictRisk
}
