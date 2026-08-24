package com.agent.code.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.agent.code.core.path.VirtualPath
import com.agent.code.workspace.RealFileSystem
import com.agent.code.workspace.ShizukuFsProvider
import rikka.shizuku.Shizuku

/**
 * Shizuku status probe: shows whether Shizuku server is running, permission
 * granted, and binder reachable. No write ops — read-only status check.
 */
@Composable
fun ShizukuStatusDemo(baseDir: String) {
    var status by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = {
            val ping = Shizuku.pingBinder()
            val perm = try { Shizuku.checkSelfPermission() } catch (_: Exception) { -999 }
            val permStr = when (perm) {
                android.content.pm.PackageManager.PERMISSION_GRANTED -> "GRANTED"
                0 -> "DENIED"
                -999 -> "error (not running?)"
                else -> "code=$perm"
            }
            status = buildString {
                appendLine("Shizuku pingBinder: $ping")
                appendLine("Permission: $permStr")
                if (ping) {
                    appendLine("Binder alive — Shizuku server running")
                } else {
                    appendLine("Shizuku server NOT running or not installed")
                }
            }
        }) {
            Text("Check Shizuku Status")
        }
        status?.let {
            HorizontalDivider()
            Text("Shizuku", style = MaterialTheme.typography.titleSmall)
            Text(it)
        }
    }
}
