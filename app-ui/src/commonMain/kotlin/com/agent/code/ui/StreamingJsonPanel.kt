package com.agent.code.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agent.code.provider.StreamingJsonStateMachine

/**
 * Live demo of the §5.2 streaming partial-JSON AST parser (StreamingJsonStateMachine).
 * Feeds a tool-call JSON one chunk at a time, mirroring how an SSE tool_call delta
 * arrives split across frames, and shows completeness/error state live.
 */
@Composable
fun StreamingJsonPanel() {
    // ponytail: one representative tool-call payload, split into fixed-size chunks.
    val sampleJson = """{"tool":"apply_diff_patch","args":{"path":"/src/Main.kt","search":"fun old()","replace":"fun new()"}}"""
    val chunks = remember { sampleJson.chunked(12) }
    val machine = remember { StreamingJsonStateMachine() }
    val buffer = remember { mutableStateOf("") }
    val idx = remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Streaming JSON parser (SSE chunks)", fontWeight = FontWeight.Bold)
        val status = when {
            machine.hasError -> "ERROR — malformed JSON"
            machine.isComplete -> "COMPLETE — object closed"
            else -> "STREAMING — ${idx.value}/${chunks.size} chunks fed"
        }
        Text(status)
        Text("buffer: ${buffer.value}")
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Button(
                enabled = idx.value < chunks.size && !machine.isComplete && !machine.hasError,
                onClick = {
                    val chunk = chunks[idx.value]
                    machine.feed(chunk)
                    buffer.value += chunk
                    idx.value += 1
                },
            ) { Text("Feed next chunk") }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = {
                machine.reset()
                buffer.value = ""
                idx.value = 0
            }) { Text("Reset") }
        }
    }
}
