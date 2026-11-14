package com.agent.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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

/**
 * Conversation view: the feed reads like a chat instead of a debug log.
 *
 * - the goal the user typed and the agent's final answer are message bubbles,
 * - tool activity is one dim monospace line per call (finish/iteration rows are
 *   protocol noise and stay out of the transcript),
 * - process state is a single status line — diagnostics live in the Debug tab.
 */
@Composable
fun AgentPanel(
    viewModel: AgentViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Only conversation-shaped events make the transcript; stage markers
    // ("run model ...", iteration counters, tool-finish pairs) are noise here.
    val chat = state.events.filter {
        it is UiEvent.UserMessage || it is UiEvent.Complete ||
            it is UiEvent.Failed || it is UiEvent.ToolStarted
    }
    val itemCount = chat.size + if (state.isRunning) 1 else 0

    LaunchedEffect(itemCount) {
        if (itemCount > 0) listState.animateScrollToItem(itemCount - 1)
    }

    Column(modifier = modifier.fillMaxSize()) {
        StatusBar(
            state = state,
            onStart = { viewModel.startOpenCode() },
            onNew = { viewModel.newChat() },
        )
        HorizontalDivider()

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (chat.isEmpty() && !state.isRunning) {
                item {
                    Text(
                        "Give the agent a task — it works in the app's workspace.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(chat) { event -> ChatRow(event) }
            if (state.isRunning) {
                item { WorkingRow() }
            }
        }

        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        Composer(
            text = inputText,
            onTextChange = { inputText = it },
            running = state.isRunning,
            notInstalled = state.processState is OpenCodeState.NotInstalled,
            onSend = {
                val goal = inputText.trim()
                if (goal.isNotEmpty()) {
                    viewModel.executeTask(goal)
                    inputText = ""
                }
            },
            onStop = { viewModel.cancelTask() },
        )
    }
}

/** One-line process state; Start only appears when opencode is missing or errored. */
@Composable
private fun StatusBar(
    state: AgentUiState,
    onStart: () -> Unit,
    onNew: () -> Unit,
) {
    val (label, color) = statusOf(state)
    val busy = state.isRunning || state.processState is OpenCodeState.Starting ||
        state.processState is OpenCodeState.Installing
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = color)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            label,
            fontSize = 12.sp,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (!state.isRunning && state.events.isNotEmpty()) {
            TextButton(onClick = onNew) { Text("New", fontSize = 12.sp) }
        }
        if (state.processState is OpenCodeState.NotInstalled) {
            TextButton(onClick = onStart) { Text("Start", fontSize = 12.sp) }
        }
    }
    if (state.processState is OpenCodeState.Installing) {
        LinearProgressIndicator(
            progress = { (state.processState as OpenCodeState.Installing).progress },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun statusOf(state: AgentUiState): Pair<String, androidx.compose.ui.graphics.Color> {
    if (state.isRunning) return "Working…" to MaterialTheme.colorScheme.tertiary
    return when (val s = state.processState) {
        is OpenCodeState.NotInstalled -> "opencode not installed" to MaterialTheme.colorScheme.error
        is OpenCodeState.Installing -> "Installing ${(s.progress * 100).toInt()}%" to MaterialTheme.colorScheme.primary
        is OpenCodeState.Starting -> "Starting…" to MaterialTheme.colorScheme.primary
        is OpenCodeState.Running -> "Ready" to MaterialTheme.colorScheme.tertiary
        is OpenCodeState.Error -> "Error: ${s.message}" to MaterialTheme.colorScheme.error
        is OpenCodeState.Stopped -> "Ready" to MaterialTheme.colorScheme.onSurfaceVariant
    }
}

@Composable
private fun ChatRow(event: UiEvent) {
    when (event) {
        is UiEvent.UserMessage -> Bubble(event.text, mine = true)
        is UiEvent.Complete -> Bubble(event.summary, mine = false)
        is UiEvent.Failed -> Bubble("Failed: ${event.reason}", mine = false, error = true)
        is UiEvent.ToolStarted -> ToolRow(event.name, event.args)
        else -> Unit
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean, error: Boolean = false) {
    val bg = when {
        error -> MaterialTheme.colorScheme.errorContainer
        mine -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        error -> MaterialTheme.colorScheme.onErrorContainer
        mine -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        SelectionContainer {
            Text(
                text,
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .background(bg, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                fontSize = 13.sp,
                lineHeight = 18.sp,
                color = fg,
            )
        }
    }
}

@Composable
private fun ToolRow(name: String, args: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            "·",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(10.dp),
        )
        Text(
            "$name ${args.take(100)}",
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 14.sp,
        )
    }
}

@Composable
private fun WorkingRow() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(10.dp), strokeWidth = 1.5.dp)
        Spacer(Modifier.width(6.dp))
        Text(
            "working…",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    running: Boolean,
    notInstalled: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = onTextChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Ask or give a task…") },
            enabled = !running && !notInstalled,
            maxLines = 4,
            shape = RoundedCornerShape(12.dp),
        )
        Spacer(Modifier.width(6.dp))
        if (running) {
            FilledTonalButton(onClick = onStop) { Text("Stop") }
        } else {
            Button(onClick = onSend, enabled = text.isNotBlank() && !notInstalled) { Text("Send") }
        }
    }
}

/**
 * Compact process state for the Debug tab. Kept separate from the chat's
 * [StatusBar]: this one names ports, errors and offers Start/Stop.
 */
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
