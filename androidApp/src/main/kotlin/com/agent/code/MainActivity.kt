package com.agent.code

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.agent.code.core.path.VirtualPath
import com.agent.code.core.power.AndroidPowerGovernor
import com.agent.code.service.ResilientAgentForegroundService
import com.agent.code.ui.AgentViewModel
import com.agent.code.workspace.RealFileSystem
import com.agent.code.workspace.GitProcessRunner

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val governor = AndroidPowerGovernor(applicationContext)
        ResilientAgentForegroundService.start(this)

        val workspaceRoot = VirtualPath.of(filesDir.absolutePath)

        setContent {
            val scope = rememberCoroutineScope()
            val viewModel = remember {
                AgentViewModel.create(
                    scope = scope,
                    fileSystem = RealFileSystem(workspaceRoot),
                    processRunner = GitProcessRunner(),
                    workspaceRoot = workspaceRoot
                )
            }
            App(
                agentViewModel = viewModel,
            )
        }
    }
}
