package com.agent.code

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.agent.code.core.power.AndroidPowerGovernor
import com.agent.code.service.ResilientAgentForegroundService
import com.agent.code.ui.ProbeDashboard

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val governor = AndroidPowerGovernor(applicationContext)

        ResilientAgentForegroundService.start(this)

        setContent {
            App(
                probeDashboard = {
                    ProbeDashboard(baseDir = filesDir.absolutePath, governor = governor)
                },
                governor = governor,
            )
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
