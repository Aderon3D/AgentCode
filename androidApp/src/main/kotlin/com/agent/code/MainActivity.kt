package com.agent.code

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.agent.code.ui.M1RealIoDemo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App(m1Demo = { M1RealIoDemo(baseDir = filesDir.absolutePath) })
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}