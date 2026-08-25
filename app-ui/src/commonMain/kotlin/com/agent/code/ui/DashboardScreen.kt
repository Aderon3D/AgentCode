package com.agent.code.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agent.code.core.journal.LogEntry
import com.agent.code.core.journal.TelemetryEngine
import com.agent.code.core.power.PowerGovernor
import com.agent.code.provider.HierarchicalModelRouter

/**
 * M3 (§0.1) live dashboard shell. Surfaces the existing 50ms-conflated
 * [TelemetryEngine] stream, the [PowerGovernor] profile, and the
 * [HierarchicalModelRouter] cost-routing tiers. Pure CMP, no Android-specific
 * deps — renders identically on host and device.
 */
@Composable
fun DashboardScreen(
    telemetry: TelemetryEngine,
    governor: PowerGovernor,
    router: HierarchicalModelRouter,
) {
    val profile by governor.currentProfile.collectAsState()
    var latestFrame by remember { mutableStateOf<List<LogEntry>>(emptyList()) }
    LaunchedEffect(telemetry) {
        telemetry.frames.collect { latestFrame = it }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Mission Control — Live", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        AssistChip(onClick = {}, label = { Text("Power: ${profile.name}") })
        Spacer(Modifier.height(8.dp))
        Text(
            "Telemetry — last 50ms frame: ${latestFrame.size} events",
            fontWeight = FontWeight.Bold,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            for (e in latestFrame) {
                Text("• ${logLine(e)}")
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        CostRoutingPanel(router)
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        MissionControlPanel()
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        StreamingJsonPanel()
    }
}

private fun logLine(e: LogEntry): String {
    val body = when (e) {
        is LogEntry.AgentThought -> e.markdown
        is LogEntry.ToolCallStarted -> "tool=${e.toolName} args=${e.args}"
        is LogEntry.TerminalStream -> "${if (e.isError) "ERR " else ""}${e.line}"
        is LogEntry.SystemWarning -> e.message
    }
    return "[${e.timestampMs}] $body"
}
