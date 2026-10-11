package com.agent.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.code.opencode.OpenCodeState

@Composable
fun AgentPanel(
    viewModel: AgentViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(state.events.size) {
        if (state.events.isNotEmpty()) {
            listState.animateScrollToItem(state.events.lastIndex)
        }
    }

    Column(modifier = modifier.fillMaxWidth().padding(8.dp)) {
        val clipboard = LocalClipboardManager.current
        val fullError = buildString {
            val ps = state.processState
            if (ps is OpenCodeState.Error) appendLine("OpenCode: ${ps.message}")
            state.error?.let { appendLine("Agent: $it") }
        }.trim()
        // Always offer a log export (healthy runs included) — a run that looks
        // fine can still hide ptrace/SIGSYS churn worth inspecting.
        Row(
            Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            if (fullError.isNotEmpty()) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(fullError)) }) {
                    Text("Copy Error", fontSize = 11.sp)
                }
            }
            TextButton(onClick = { viewModel.fetchLog() }) { Text("Copy Logs", fontSize = 11.sp) }
        }
        // When a dump arrives, put it on the clipboard AND keep it visible in
        // the panel below (was: auto-cleared — the user never saw logs in-app).
        LaunchedEffect(state.logDump) {
            state.logDump?.let {
                clipboard.setText(AnnotatedString(it))
            }
        }

        ProcessStatusHeader(
            processState = state.processState,
            isRunning = state.isRunning,
            onStart = { viewModel.startOpenCode() },
            onStop = { viewModel.stopOpenCode() }
        )

        Spacer(Modifier.height(8.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Agent goal...") },
                enabled = !state.isRunning && state.processState is OpenCodeState.Running,
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    viewModel.executeTask(inputText)
                    inputText = ""
                },
                enabled = !state.isRunning && inputText.isNotBlank()
                        && state.processState is OpenCodeState.Running
            ) { Text("Run") }
            if (state.isRunning) {
                Spacer(Modifier.width(4.dp))
                OutlinedButton(onClick = { viewModel.cancelTask() }) { Text("Stop") }
            }
        }

        state.error?.let {
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Error: $it", color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.weight(1f))
                val clipboard = LocalClipboardManager.current
                TextButton(onClick = { clipboard.setText(AnnotatedString(it)) }) { Text("Copy", fontSize = 11.sp) }
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyColumn(
            state = listState,
            // NOT weight(): App's root Column is verticalScroll (unbounded
            // height), and weight() collapses to 0 there — the feed never
            // rendered. Fixed bounds = visible empty box + internal scroll.
            modifier = Modifier.fillMaxWidth()
                .heightIn(min = 120.dp, max = 420.dp)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    RoundedCornerShape(4.dp)
                ).padding(4.dp)
        ) {
            items(state.events) { event ->
                AgentEventRow(event)
            }
        }

        state.logDump?.let { dump ->
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                Text(
                    // Full dump is ~200k chars — head (goal/state header) + tail
                    // (server log + recent loader forensics) only.
                    text = dump.take(600) + "\n…\n" + dump.takeLast(4500),
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            RoundedCornerShape(4.dp)
                        ).padding(4.dp)
                )
                TextButton(onClick = { viewModel.clearLogDump() }) {
                    Text("Close", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun ProcessStatusHeader(
    processState: OpenCodeState,
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (label, color) = when (processState) {
            is OpenCodeState.NotInstalled -> "Not installed" to MaterialTheme.colorScheme.error
            is OpenCodeState.Installing -> "Installing ${(processState.progress * 100).toInt()}% — ${processState.message}" to MaterialTheme.colorScheme.primary
            is OpenCodeState.Starting -> "Starting..." to MaterialTheme.colorScheme.primary
            is OpenCodeState.Running -> "Running on :${processState.port}" to MaterialTheme.colorScheme.tertiary
            is OpenCodeState.Error -> "Error: ${processState.message}" to MaterialTheme.colorScheme.error
            is OpenCodeState.Stopped -> "Stopped" to MaterialTheme.colorScheme.onSurfaceVariant
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text("OpenCode: $label", color = color, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (processState is OpenCodeState.Error) {
                TextButton(onClick = { clipboard.setText(AnnotatedString(processState.message)) }) { Text("Copy", fontSize = 10.sp) }
            }
        }
        if (processState is OpenCodeState.Installing) {
            LinearProgressIndicator(
                progress = { processState.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
        Row {
            if (processState is OpenCodeState.NotInstalled
                || processState is OpenCodeState.Error
                || processState is OpenCodeState.Stopped
            ) {
                TextButton(onClick = onStart) { Text("Start") }
            }
            if (processState is OpenCodeState.Running) {
                TextButton(onClick = onStop) { Text("Stop") }
            }
        }
    }
}

@Composable
private fun AgentEventRow(event: UiEvent) {
    val icon: String
    val text: String
    val color: androidx.compose.ui.graphics.Color
    val font: FontFamily
    when (event) {
        is UiEvent.Thinking -> { icon = "\uD83D\uDCAD"; text = event.text; color = MaterialTheme.colorScheme.onSurface; font = FontFamily.Default }
        is UiEvent.ToolStarted -> { icon = "\uD83D\uDD27"; text = "\u2192 ${event.name}(${event.args.take(80)})"; color = MaterialTheme.colorScheme.primary; font = FontFamily.Monospace }
        is UiEvent.ToolFinished -> {
            icon = if (event.success) "\u2705" else "\u274C"
            text = "\u2190 ${event.name} ${if (event.success) "ok" else "FAIL"}"
            color = MaterialTheme.colorScheme.onSurface
            font = FontFamily.Monospace
        }
        is UiEvent.Iteration -> { icon = "\uD83D\uDD04"; text = "Iteration ${event.number} complete"; color = MaterialTheme.colorScheme.onSurface; font = FontFamily.Default }
        is UiEvent.Complete -> { icon = "\u2705"; text = "Done: ${event.summary}"; color = MaterialTheme.colorScheme.tertiary; font = FontFamily.Default }
        is UiEvent.Failed -> { icon = "\u274C"; text = "Failed: ${event.reason}"; color = MaterialTheme.colorScheme.error; font = FontFamily.Default }
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text("$icon ", fontSize = 11.sp)
        Text(
            text = text,
            fontSize = 11.sp,
            color = color,
            fontFamily = font,
            lineHeight = 14.sp
        )
    }
}
