package com.agent.code.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.code.core.journal.AgentEvent
import com.agent.code.core.journal.FileBackedWalStore
import com.agent.code.core.journal.eventJson
import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.RealFileSystem
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Device-runnable M1 proof: writes/reads a real file through [RealFileSystem]
 * and verifies a [FileBackedWalStore] survives a simulated restart.
 * Git/worktree paths are intentionally excluded — no `git` binary on-device.
 */
@Composable
fun M1RealIoDemo(baseDir: String) {
    val fs = remember(baseDir) { RealFileSystem(VirtualPath.of(baseDir)) }
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = {
            scope.launch(Dispatchers.IO) {
            val probePath = VirtualPath.of("$baseDir/agentcode-m1-probe.txt")
            val written = "M1 real-IO probe @ ${System.currentTimeMillis()}"
            val fsLine = fs.write(probePath, written).fold(
                onSuccess = {
                    fs.read(probePath).fold(
                        onSuccess = { "Real FS OK\n  wrote: $written\n  read:  $it" },
                        onFailure = { "Real FS write ok, read failed: ${it.message}" },
                    )
                },
                onFailure = { "Real FS write failed: ${it.message}" },
            )

            val walFile = File("$baseDir/agentcode-m1-wal.log").also { it.delete() }
            val store = FileBackedWalStore(walFile)
            val events = listOf(
                AgentEvent.TaskStarted(1, "ui-probe", System.currentTimeMillis(), "device real-IO demo"),
                AgentEvent.TaskSucceeded(2, "ui-probe", System.currentTimeMillis(), "durable WAL recovered"),
            )
            events.forEach { store.append(eventJson.encodeToString(it)) }
            val pruned = store.selfHeal()
            val recovered = FileBackedWalStore(walFile).replay()
            val walLine = if (recovered.size == events.size) {
                "Durable WAL OK: ${recovered.size} events recovered after restart" + if (pruned > 0) " ($pruned corrupt pruned)" else ""
            } else {
                "WAL MISMATCH: expected ${events.size}, got ${recovered.size}"
            }

            val escape = VirtualPath.of("$baseDir/../agentcode-escape.txt")
            val traversalLine = fs.read(escape).fold(
                onSuccess = { "TRAVERSAL NOT BLOCKED (read: $it)" },
                onFailure = { "Traversal blocked: ${it.message}" },
            )

            result = "$fsLine\n\n$walLine\n\n$traversalLine"
            }
        }) {
            Text("Run M1 Real IO Probe")
        }
        result?.let {
            HorizontalDivider()
            Text("M1 Real IO", style = MaterialTheme.typography.titleSmall)
            Text(it)
        }
    }
}
