package com.agent.code.core.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.agent.code.core.fsm.OperatingProfile
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android AdaptivePowerGovernor (§2.3 Development_Doc.md).
 *
 * Monitors:
 * - Battery level via ACTION_BATTERY_CHANGED BroadcastReceiver
 * - Thermal status via PowerManager.getCurrentThermalStatus() (API 29+)
 * - Actual temperature via /sys/class/thermal/thermal_zoneN/temp (millidegrees C)
 *
 * Emits [OperatingProfile] changes via [currentProfile] StateFlow.
 */
class AndroidPowerGovernor(context: Context) : PowerGovernor {

    private val _currentProfile = MutableStateFlow(OperatingProfile.BALANCED_BATTERY)
    override val currentProfile: StateFlow<OperatingProfile> = _currentProfile

    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var isPluggedIn = false
    private var batteryLevelPercent = 100
    private var maxTemperatureCelsius = 0f

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent?.let {
                val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                isPluggedIn = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                batteryLevelPercent = if (scale > 0) (level * 100) / scale else 100
                pollThermalStatus()
                readTemperatures()
                evaluateProfile()
            }
        }
    }

    init {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)

        // Read initial battery state from sticky broadcast
        val initialBattery = context.registerReceiver(null, filter)
        initialBattery?.let {
            val status = it.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            isPluggedIn = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryLevelPercent = if (scale > 0) (level * 100) / scale else 100
            pollThermalStatus()
            readTemperatures()
            evaluateProfile()
        }
    }

    private fun pollThermalStatus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalStatus = powerManager.getCurrentThermalStatus()
        }
    }

    /**
     * Read actual CPU/battery temperatures from sysfs thermal zones.
     * Values are in millidegrees Celsius (e.g. 35000 = 35°C).
     * Reports the maximum across all zones.
     */
    private fun readTemperatures() {
        try {
            val thermalDir = File("/sys/class/thermal")
            if (!thermalDir.exists()) return
            var max = 0f
            thermalDir.listFiles()?.filter { it.name.startsWith("thermal_zone") }?.forEach { zone ->
                val tempFile = File(zone, "temp")
                if (tempFile.exists()) {
                    val raw = tempFile.readText().trim().toFloatOrNull() ?: return@forEach
                    val celsius = if (raw > 1000) raw / 1000f else raw
                    if (celsius > max) max = celsius
                }
            }
            maxTemperatureCelsius = max
        } catch (_: Exception) {
            // sysfs may not be readable on all devices
        }
    }

    private fun evaluateProfile() {
        _currentProfile.value = when {
            thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE || batteryLevelPercent < 20 ->
                OperatingProfile.ECO_PRESERVATION
            isPluggedIn && thermalStatus <= PowerManager.THERMAL_STATUS_MODERATE ->
                OperatingProfile.TURBO_PLUGGED
            else ->
                OperatingProfile.BALANCED_BATTERY
        }
    }

    /** Expose for probe display. */
    fun snapshot(): GovernorSnapshot = GovernorSnapshot(
        profile = currentProfile.value,
        batteryPercent = batteryLevelPercent,
        thermalStatus = thermalStatus,
        temperatureCelsius = maxTemperatureCelsius,
        pluggedIn = isPluggedIn
    )
}

data class GovernorSnapshot(
    val profile: OperatingProfile,
    val batteryPercent: Int,
    val thermalStatus: Int,
    val temperatureCelsius: Float,
    val pluggedIn: Boolean
) {
    val thermalLabel: String get() = when (thermalStatus) {
        PowerManager.THERMAL_STATUS_NONE -> "NONE"
        PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
        PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
        PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
        PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
        else -> "UNKNOWN($thermalStatus)"
    }
}
