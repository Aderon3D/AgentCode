package com.agent.code.core.elevation

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.content.pm.PackageManager
import java.lang.reflect.Method
import rikka.shizuku.Shizuku

enum class ElevationStatus { StandardUserSpace, PrivilegedAdbUncapped }

class PrivilegedElevationManager(private val context: Context) {

    fun applyZeroRootOptimizations(): Result<ElevationStatus> {
        if (!isShizukuAvailable()) {
            return Result.success(ElevationStatus.StandardUserSpace)
        }
        return try {
            executeAdbCommand("device_config put activity_manager max_phantom_processes 2147483647")
            executeAdbCommand("settings put global settings_enable_monitor_phantom_procs false")
            Result.success(ElevationStatus.PrivilegedAdbUncapped)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun requestDirectBatteryOptimizationExemption() {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }

    private fun isShizukuAvailable(): Boolean = try {
        Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private val newProcessMethod: Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        } catch (_: Throwable) { null }
    }

    private fun executeAdbCommand(command: String) {
        val m = newProcessMethod ?: throw UnsupportedOperationException("Shizuku.newProcess unavailable")
        val process = m.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
            ?: throw UnsupportedOperationException("Shizuku.newProcess returned null")
        val rc = process.waitFor()
        if (rc != 0) {
            val err = process.errorStream.bufferedReader().readText()
            throw RuntimeException("ADB command failed (exit $rc): $err")
        }
    }
}
