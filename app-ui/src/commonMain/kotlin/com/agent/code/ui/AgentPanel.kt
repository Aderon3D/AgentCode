package com.agent.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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

    // Tab content is a plain (non-scrollable) Column, so weight() is safe here.
    Column(modifier = modifier.fillMaxSize().padding(8.dp)) {
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
                enabled = !state.isRunning && state.processState !is OpenCodeState.NotInstalled,
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    viewModel.executeTask(inputText)
                    inputText = ""
                },
                enabled = !state.isRunning && inputText.isNotBlank()
                        && state.processState !is OpenCodeState.NotInstalled
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
            modifier = Modifier.fillMaxWidth().weight(1f)
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    RoundedCornerShape(4.dp)
                ).padding(4.dp)
        ) {
            items(state.events) { event ->
                AgentEventRow(event)
            }
        }
    }
}

@Composable
internal fun ProcessStatusHeader(
    processState: OpenCodeState,
    isRunning: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A run is a one-shot child of its own; the serve process it kills
            // during prepareRun leaves processState=Stopped, which used to read
            // as "the app died". While a task is in flight, that is not the
            // server's state to report.
            val (label, color) = if (isRunning) {
                "Running (task)" to MaterialTheme.colorScheme.tertiary
            } else when (processState) {
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
            Row {
                // Don't offer Start while a run is in flight: spawning `serve`
                // beside a live run child collides on the process-scoped
                // waitpid(-1) drain (elfloader single-spawn gate).
                if (canStart(processState, isRunning)) {
                    TextButton(onClick = onStart) { Text("Start") }
                }
                if (processState is OpenCodeState.Running) {
                    TextButton(onClick = onStop) { Text("Stop") }
                }
            }
        }
        if (processState is OpenCodeState.Installing) {
            LinearProgressIndicator(
                progress = { processState.progress },
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

// Start is offered only when no run is in flight and no process is already up.
private fun canStart(processState: OpenCodeState, isRunning: Boolean): Boolean =
    !isRunning && when (processState) {
        is OpenCodeState.NotInstalled, is OpenCodeState.Error, is OpenCodeState.Stopped -> true
        else -> false
    }

@Composable
private fun AgentEventRow(event: UiEvent) {
    val marker: String
    val text: String
    val color: androidx.compose.ui.graphics.Color
    val font: FontFamily
    when (event) {
        is UiEvent.Thinking -> { marker = "*"; text = event.text; color = MaterialTheme.colorScheme.onSurface; font = FontFamily.Default }
        is UiEvent.ToolStarted -> { marker = ">"; text = " ${event.name}(${event.args.take(80)})"; color = MaterialTheme.colorScheme.primary; font = FontFamily.Monospace }
        is UiEvent.ToolFinished -> {
            marker = "<"
            text = " ${event.name} ${if (event.success) "ok" else "FAIL"}"
            color = if (event.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error
            font = FontFamily.Monospace
        }
        is UiEvent.Iteration -> { marker = "*"; text = "iteration ${event.number} complete"; color = MaterialTheme.colorScheme.onSurface; font = FontFamily.Default }
        is UiEvent.Complete -> { marker = "+"; text = "done: ${event.summary}"; color = MaterialTheme.colorScheme.tertiary; font = FontFamily.Default }
        is UiEvent.Failed -> { marker = "!"; text = "failed: ${event.reason}"; color = MaterialTheme.colorScheme.error; font = FontFamily.Default }
    }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(marker, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(14.dp))
        Text(
            text = text,
            fontSize = 11.sp,
            color = color,
            fontFamily = font,
            lineHeight = 14.sp
        )
    }
}
