package com.agent.code.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One-line update state above the tabs: checking / up to date / available /
 * failed. Visible on purpose — on a device without adb this is the only way to
 * tell whether the updater ran at all.
 */
@Composable
fun UpdateBanner() {
    val status by Updater.status.collectAsState()
    val context = LocalContext.current
    val text = status ?: return
    Row(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        if (text.contains("failed")) {
            TextButton(onClick = { Updater.check(context) }) { Text("Retry", fontSize = 11.sp) }
        }
    }
}
