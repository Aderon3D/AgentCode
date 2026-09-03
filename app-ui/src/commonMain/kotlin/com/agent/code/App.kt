package com.agent.code

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.agent.code.ui.AgentPanel
import com.agent.code.ui.AgentViewModel

@Composable
fun App(
    agentViewModel: AgentViewModel? = null,
) {
    MaterialTheme {
        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.primaryContainer)
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (agentViewModel != null) {
                AgentPanel(viewModel = agentViewModel)
            }
        }
    }
}
