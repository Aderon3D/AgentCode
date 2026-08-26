package com.agent.code.core.lock

import com.agent.code.core.path.VirtualPath
import kotlinx.coroutines.*
import java.nio.file.ClosedWatchServiceException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds
import java.nio.file.WatchKey
import java.nio.file.WatchService

actual class FileWatcher {
    private var scope: Job? = null
    private var watchService: WatchService? = null

    actual fun startWatching(
        targetDirectory: VirtualPath,
        onFileChanged: (VirtualPath, ChangeType) -> Unit
    ) {
        stopWatching()
        val dir = Path.of(targetDirectory.rawPath)
        if (!java.io.File(dir.toUri()).exists()) return

        val ws = FileSystems.getDefault().newWatchService()
        watchService = ws
        dir.register(ws,
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_MODIFY,
            StandardWatchEventKinds.ENTRY_DELETE,
        )

        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                while (isActive) {
                    // ponytail: closing the watch service (on stopWatching) makes take()
                    // throw instead of returning null; treat that as a clean shutdown.
                    val key = try {
                        ws.take()
                    } catch (_: ClosedWatchServiceException) {
                        break
                    } catch (_: InterruptedException) {
                        break
                    } ?: break
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
                    if (!key.reset()) break
                }
            } finally {
                try { ws.close() } catch (_: Exception) { }
                watchService = null
            }
        }
    }

    actual fun stopWatching() {
        scope?.cancel()
        scope = null
        try { watchService?.close() } catch (_: Exception) { }
        watchService = null
    }
}
