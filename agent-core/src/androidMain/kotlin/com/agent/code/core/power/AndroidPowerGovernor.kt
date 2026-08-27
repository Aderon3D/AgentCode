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
class AndroidPowerGovernor(private val context: Context) : PowerGovernor {

    private val _currentProfile = MutableStateFlow(OperatingProfile.BALANCED_BATTERY)
    override val currentProfile: StateFlow<OperatingProfile> = _currentProfile

    private var thermalStatus = PowerManager.THERMAL_STATUS_NONE
    private var isPluggedIn = false
    private var batteryLevelPercent = 100
    private var maxTemperatureCelsius = 0f
    var temperatureWarning: String? = null
        private set

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private var thermalListener: PowerManager.OnThermalStatusChangedListener? = null

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

        // ponytail: thermal-only transitions (no battery broadcast) must still
        // refresh the profile, so register the API29+ thermal listener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
                thermalStatus = status
                evaluateProfile()
            }
            powerManager.addThermalStatusListener(thermalListener!!)
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
            var maxType = ""
            thermalDir.listFiles()?.filter { it.name.startsWith("thermal_zone") }?.forEach { zone ->
                val type = try {
                    File(zone, "type").readText().trim().lowercase()
                } catch (_: Exception) { "" }
                val raw = try {
                    File(zone, "temp").readText().trim().toFloatOrNull() ?: return@forEach
                } catch (_: Exception) { return@forEach }
                if (!isRealTempSensor(type) || raw <= 0f) return@forEach
                val celsius = when {
                    raw >= 10000f -> raw / 1000f
                    raw >= 100f -> raw / 10f
                    raw >= 20f -> raw
                    else -> return@forEach
                }
                if (celsius > max) {
                    max = celsius
                    maxType = type
                }
            }
            maxTemperatureCelsius = max
            temperatureWarning = if (max > 70f) "WARNING: ${max.toInt()}°C from '$maxType'" else null
        } catch (_: Exception) {
            // sysfs may not be readable on all devices
        }
    }

    private fun isRealTempSensor(type: String): Boolean {
        if (type.isEmpty()) return false
        val knownBad = listOf("trip", "bcl", "ibat", "vbat", "socd", "usb")
        return knownBad.none { type.contains(it) }
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

    /** Idempotent teardown: unregister receivers/listeners. Call from owner lifecycle. */
    fun close() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
            // already unregistered
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalListener?.let { powerManager.removeThermalStatusListener(it) }
            thermalListener = null
        }
    }

    /** Expose for probe display. */
    fun snapshot(): GovernorSnapshot = GovernorSnapshot(
        profile = currentProfile.value,
        batteryPercent = batteryLevelPercent,
        thermalStatus = thermalStatus,
        temperatureCelsius = maxTemperatureCelsius,
        temperatureWarning = temperatureWarning,
        pluggedIn = isPluggedIn
    )
}

data class GovernorSnapshot(
    val profile: OperatingProfile,
    val batteryPercent: Int,
    val thermalStatus: Int,
    val temperatureCelsius: Float,
    val temperatureWarning: String? = null,
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
