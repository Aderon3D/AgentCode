package com.agent.code.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agent.code.core.journal.TelemetryEngine
import com.agent.code.core.power.PowerGovernor
import com.agent.code.provider.HierarchicalModelRouter

/**
 * Debug tab: process status + log dump on top, milestone demo screens below in
 * collapsible sections. The demos are kept for regression eyeballing but stay
 * out of the way of the Agent/Files workflow.
 */
@Composable
fun DebugScreen(
    viewModel: AgentViewModel?,
    governor: PowerGovernor,
    telemetry: TelemetryEngine,
    router: HierarchicalModelRouter,
    probeDashboard: @Composable (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        ExpandableSection("Process & logs", defaultOpen = true) {
            ProcessAndLogs(viewModel)
        }
        ExpandableSection("M0.5 Spine demo") {
            com.agent.code.M05SpineDemo()
        }
        ExpandableSection("M3 Live Dashboard") {
            DashboardScreen(telemetry = telemetry, governor = governor, router = router)
        }
        probeDashboard?.invoke()
    }
}

@Composable
internal fun ExpandableSection(
    title: String,
    defaultOpen: Boolean = false,
    content: @Composable () -> Unit
) {
    var open by remember { mutableStateOf(defaultOpen) }
    Column(Modifier.fillMaxWidth()) {
        TextButton(onClick = { open = !open }) {
            Text(
                "${if (open) "-" else "+"} $title",
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
        }
        if (open) {
            content()
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ProcessAndLogs(viewModel: AgentViewModel?) {
    if (viewModel == null) {
        Text("No agent view model", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val state by viewModel.state.collectAsState()
    val clipboard = LocalClipboardManager.current

    ProcessStatusHeader(
        processState = state.processState,
        isRunning = state.isRunning,
        onStart = { viewModel.startOpenCode() },
        onStop = { viewModel.stopOpenCode() }
    )

    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { viewModel.fetchLog() }) { Text("Copy logs", fontSize = 12.sp) }
    }

    // A dump is also auto-copied so it can be pasted without a tap.
    LaunchedEffect(state.logDump) {
        state.logDump?.let { clipboard.setText(AnnotatedString(it)) }
    }

    state.logDump?.let { dump ->
        Text(
            // Full dump is ~200k chars — head (goal/state header) + tail
            // (server log + recent loader forensics) only.
            text = dump.take(600) + "\n...\n" + dump.takeLast(4500),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .verticalScroll(rememberScrollState())
                .background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    RoundedCornerShape(4.dp)
                ).padding(4.dp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { viewModel.clearLogDump() }) { Text("Close", fontSize = 12.sp) }
        }
    }

    state.error?.let {
        Text("Agent error: $it", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
    }
}
