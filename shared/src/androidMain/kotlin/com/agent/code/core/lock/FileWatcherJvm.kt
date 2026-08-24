package com.agent.code.core.lock

import com.agent.code.core.path.VirtualPath
import kotlinx.coroutines.*
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey

actual class FileWatcher {
    private var scope: Job? = null

    actual fun startWatching(
        targetDirectory: VirtualPath,
        onFileChanged: (VirtualPath, ChangeType) -> Unit
    ) {
        stopWatching()
        val dir = Path.of(targetDirectory.rawPath)
        if (!java.io.File(dir.toUri()).exists()) return

        val watchService = FileSystems.getDefault().newWatchService()
        dir.register(watchService,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
        )

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            while (isActive) {
                val key = watchService.take() ?: break
                for (event in key.pollEvents()) {
                    val kind = event.kind()
                    val child = event.context() as? Path ?: continue
                    val fullPath = dir.resolve(child)
                    val vpath = VirtualPath.of(fullPath.toString())
                    val changeType = when (kind) {
                        StandardWatchEventKinds.ENTRY_CREATE -> ChangeType.CREATED
                        StandardWatchEventKinds.ENTRY_MODIFY -> ChangeType.MODIFIED
                        StandardWatchEventKinds.ENTRY_DELETE -> ChangeType.DELETED
                        else -> continue
                    }
                    onFileChanged(vpath, changeType)
                }
                key.reset()
            }
        }
    }

    actual fun stopWatching() {
        scope?.cancel()
        scope = null
    }
}
