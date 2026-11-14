package com.agent.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
    // ("run model ...", iteration counters) are noise here.
    val chat = remember(state.events) { chatItems(state.events) }
    // Expanded tool calls / reasoning blocks, keyed by row index (append-only).
    val expanded = remember { mutableStateMapOf<Int, Boolean>() }
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
            itemsIndexed(chat) { index, item ->
                ChatRow(
                    item = item,
                    expanded = expanded[index] == true,
                    onToggle = { expanded[index] = !(expanded[index] ?: false) },
                )
            }
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
        // Which APK is actually running — the first thing to check when an
        // update or a fix "didn't take".
        Text(
            com.agent.code.opencode.OpenCodeClient.APP_BUILD,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.width(6.dp))
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
private fun ChatRow(item: ChatItem, expanded: Boolean, onToggle: () -> Unit) {
    when (item) {
        is ChatItem.User -> Bubble(item.text, mine = true)
        is ChatItem.Agent -> AgentBubble(item.text)
        is ChatItem.Reasoning -> ReasoningRow(item.text, expanded, onToggle)
        is ChatItem.Tool -> ToolCallRow(item, expanded, onToggle)
        is ChatItem.Note -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                item.text,
                fontSize = 10.sp,
                color = if (item.error) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The agent's reply: markdown, not a raw Text dump. */
@Composable
private fun AgentBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        SelectionContainer {
            Column(
                Modifier
                    .widthIn(max = 360.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                MarkdownText(text, MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}

@Composable
private fun ReasoningRow(text: String, expanded: Boolean, onToggle: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (expanded) "▾" else "▸",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = dim,
                modifier = Modifier.width(12.dp),
            )
            Text(
                "thinking · ${text.length} chars",
                fontSize = 10.sp,
                color = dim,
            )
        }
        if (expanded) {
            SelectionContainer {
                Text(
                    capText(text, 6000),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = dim,
                    modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                )
            }
        }
    }
}

/** One tool call, collapsed to a line; expand for full args + output. */
@Composable
private fun ToolCallRow(item: ChatItem.Tool, expanded: Boolean, onToggle: () -> Unit) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val mono = FontFamily.Monospace
    val nameColor = when (item.ok) {
        true -> dim
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.tertiary
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (expanded) "▾" else "▸",
                fontSize = 10.sp,
                fontFamily = mono,
                color = dim,
                modifier = Modifier.width(12.dp),
            )
            Text(item.name, fontSize = 11.sp, fontFamily = mono, fontWeight = FontWeight.SemiBold, color = nameColor)
            Spacer(Modifier.width(6.dp))
            Text(
                toolPreview(item),
                fontSize = 11.sp,
                fontFamily = mono,
                color = dim,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (item.ok == null) {
                CircularProgressIndicator(Modifier.size(9.dp), strokeWidth = 1.2.dp)
            } else if (!item.ok) {
                Text("failed", fontSize = 9.sp, color = MaterialTheme.colorScheme.error)
            }
        }
        if (expanded) {
            if (item.args.isNotBlank()) CodeBox(capText(item.args, 6000))
            if (item.output.isNotBlank()) CodeBox(capText(item.output, 12000))
        }
    }
}

private fun toolPreview(item: ChatItem.Tool): String =
    (item.args.ifBlank { item.output })
        .lineSequence().firstOrNull()?.trim()?.take(120) ?: ""

private fun capText(text: String, max: Int): String =
    if (text.length <= max) text else text.take(max) + "\n… (${text.length - max} chars cut)"

@Composable
private fun CodeBox(text: String) {
    SelectionContainer {
        Text(
            text,
            fontSize = 11.sp,
            lineHeight = 15.sp,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp))
                .padding(6.dp),
        )
    }
}

@Composable
private fun Bubble(text: String, mine: Boolean) {
    val bg = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
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
