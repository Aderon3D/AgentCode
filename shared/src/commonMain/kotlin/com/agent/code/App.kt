package com.agent.code

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.agent.code.bootstrap.MissionControlBootstrap
import com.agent.code.bootstrap.Timeline
import org.jetbrains.compose.resources.painterResource

import agentcode.shared.generated.resources.Res
import agentcode.shared.generated.resources.compose_multiplatform

@Composable
@Preview
fun App() {
    MaterialTheme {
        var showContent by remember { mutableStateOf(false) }
        var showSpine by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .safeContentPadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Button(onClick = { showContent = !showContent }) {
                Text("点击我！")
            }
            AnimatedVisibility(showContent) {
                val greeting = remember { Greeting().greet() }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Image(painterResource(Res.drawable.compose_multiplatform), null)
                    Text("Compose: $greeting")
                }
            }
            Button(onClick = { showSpine = !showSpine }) {
                Text("M0.5 Spine Demo")
            }
            AnimatedVisibility(showSpine) {
                M05SpineDemo()
            }
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
    var timeline by remember { mutableStateOf<Timeline?>(null) }
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
            Text(
                text = if (recovered) "WAL recovery: OK (identical)" else "WAL recovery: MISMATCH",
                fontWeight = FontWeight.Bold,
            )
            Text("Final state: ${tl.finalState}")
            Text("Recovered state: ${tl.recoveredState}")
            Text("Events: ${tl.events.size}  |  Telemetry frames: ${tl.telemetryFrames.size}")
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                tl.events.forEach { Text("• $it") }
            }
        }
    }
}