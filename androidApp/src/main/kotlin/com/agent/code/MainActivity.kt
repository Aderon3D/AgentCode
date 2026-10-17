package com.agent.code

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.agent.code.core.path.VirtualPath
import com.agent.code.core.power.AndroidPowerGovernor
import com.agent.code.service.ResilientAgentForegroundService
import com.agent.code.ui.AgentViewModel
import com.agent.code.security.AuditLog
import com.agent.code.workspace.RealFileSystem
import com.agent.code.workspace.GitProcessRunner

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val governor = AndroidPowerGovernor(applicationContext)
        ResilientAgentForegroundService.start(this)

        // Install/runtime FS: opencode binary + loader sentinel + .local log all
        // live under files/opencode — outside the workspace, so the manager needs
        // a root at filesDir or install checks read "path escapes workspace root".
        val runtimeFs = RealFileSystem(VirtualPath.of(filesDir.absolutePath))
        // Agent workspace is a clean subdir so the Files tab and opencode sessions
        // don't see the opencode install dir (binary, glibc, .local).
        val workspaceDir = java.io.File(filesDir, "workspace").apply { mkdirs() }
        val workspaceRoot = VirtualPath.of(workspaceDir.absolutePath)
        val fileSystem = RealFileSystem(workspaceRoot)
        AuditLog.init(fileSystem, workspaceRoot)

        setContent {
            val scope = rememberCoroutineScope()
            val viewModel = remember {
                AgentViewModel.create(
                    scope = scope,
                    fileSystem = runtimeFs,
                    processRunner = GitProcessRunner(),
                    workspaceRoot = workspaceRoot,
                    nativeLibraryDir = applicationInfo.nativeLibraryDir
                )
            }
            DisposableEffect(Unit) {
                onDispose { viewModel.destroy() }
            }
            LaunchedEffect(Unit) {
                viewModel.startOpenCode()
            }
            App(
                agentViewModel = viewModel,
                governor = governor,
                fileSystem = fileSystem,
                workspaceRoot = workspaceRoot,
            )
        }
    }
}
