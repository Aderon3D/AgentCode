package com.agent.code

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.agent.code.bootstrap.MissionControlBootstrap
import com.agent.code.bootstrap.Timeline
import com.agent.code.core.journal.LogEntry

@Composable
@Preview
fun App(m1Demo: @Composable (() -> Unit)? = null) {
    MaterialTheme {
        var showSpine by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = { showSpine = !showSpine }) {
                Text("M0.5 Spine Demo")
            }
            AnimatedVisibility(showSpine) {
                M05SpineDemo()
            }
            m1Demo?.invoke()
        }
    }
}

/**
 * Barebones harness that runs the M0.5 bootstrap spine on the real runtime and
 * renders the resulting [Timeline]. Proves the WAL-recovery invariant
 * (finalState == recoveredState) and the orchestration spine end-to-end.
 * Stubbed subsystems (LLM/MCP/process) are not exercised here.
 */
@Composable
fun M05SpineDemo() {
    val clipboard = LocalClipboardManager.current
    var timeline by remember { mutableStateOf<Timeline?>(null) }
    var copied by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Button(onClick = { timeline = MissionControlBootstrap.runDemo() }) {
            Text("Run M0.5 Demo")
        }
        timeline?.let { tl ->
            val recovered = tl.finalState == tl.recoveredState
            // Fixed summary — stays pinned so the recovery verdict is always visible.
            Text(
                text = if (recovered) "WAL recovery: OK (identical)" else "WAL recovery: MISMATCH",
                fontWeight = FontWeight.Bold,
            )
            Text("Final state: ${tl.finalState}")
            Text("Recovered state: ${tl.recoveredState}")
            Text("Events: ${tl.events.size}  |  Telemetry frames: ${tl.telemetryFrames.size}")
            Button(onClick = {
                clipboard.setText(AnnotatedString(buildLogDump(tl)))
                copied = true
            }) {
                Text(if (copied) "Copied!" else "Copy logs")
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            // Stream 1 — the durable WAL event journal.
            Text("Event journal (WAL)", fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                tl.events.forEach { Text("• $it") }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            // Stream 2 — the 50ms telemetry sampling engine's frames.
            Text("Telemetry frames (50ms engine)", fontWeight = FontWeight.Bold)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                tl.telemetryFrames.forEachIndexed { i, frame ->
                    Text("Frame $i (${frame.size})", fontWeight = FontWeight.Bold)
                    frame.forEach { Text("  ${logEntryLine(it)}") }
                }
            }
        }
    }
}

/** Plain-text dump of the whole timeline for clipboard export. */
private fun buildLogDump(tl: Timeline): String = buildString {
    appendLine("M0.5 Spine Demo — Timeline dump")
    appendLine("WAL recovery: ${if (tl.finalState == tl.recoveredState) "OK (identical)" else "MISMATCH"}")
    appendLine("Final state: ${tl.finalState}")
    appendLine("Recovered state: ${tl.recoveredState}")
    appendLine("Events: ${tl.events.size} | Telemetry frames: ${tl.telemetryFrames.size}")
    appendLine()
    appendLine("== Event journal (WAL) ==")
    tl.events.forEach { appendLine("• $it") }
    appendLine()
    appendLine("== Telemetry frames (50ms engine) ==")
    tl.telemetryFrames.forEachIndexed { i, frame ->
        appendLine("Frame $i (${frame.size})")
        frame.forEach { appendLine("  ${logEntryLine(it)}") }
    }
}

/** One-line rendering of a telemetry [LogEntry]. */
private fun logEntryLine(e: LogEntry): String {
    val body = when (e) {
        is LogEntry.AgentThought -> e.markdown
        is LogEntry.ToolCallStarted -> "tool=${e.toolName} args=${e.args}"
        is LogEntry.TerminalStream -> "${if (e.isError) "ERR " else ""}${e.line}"
        is LogEntry.SystemWarning -> e.message
    }
    return "[${e.timestampMs}] $body"
}