package com.agent.code.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fires on the watchdog alarm; re-launches the foreground service if it was
 * reclaimed by the system. The dataSync foreground-service type is permitted
 * to start from the background on API 31+.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        ResilientAgentForegroundService.start(context)
    }
}
