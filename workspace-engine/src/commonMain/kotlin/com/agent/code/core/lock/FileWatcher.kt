package com.agent.code.core.lock

import com.agent.code.core.path.VirtualPath

enum class ChangeType { CREATED, MODIFIED, DELETED }

expect class FileWatcher {
    fun startWatching(
        targetDirectory: VirtualPath,
        onFileChanged: (VirtualPath, ChangeType) -> Unit
    )
    fun stopWatching()
}
