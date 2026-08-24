package com.agent.code.ui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.view.WindowManager
import java.io.File
import java.util.Locale

/**
 * Collects device hardware and performance stats for calibration.
 * All reads are synchronous — call from IO dispatcher.
 */
class DeviceStatsCollector(private val context: Context) {
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    fun collect(): DeviceStats = DeviceStats(
        device = collectDevice(),
        cpu = collectCpu(),
        ram = collectRam(),
        heap = collectHeap(),
        storage = collectStorage(),
        gpu = collectGpu(),
        display = collectDisplay(),
        battery = collectBattery(),
        thermalZones = collectThermalZones(),
    )

    private fun collectDevice() = DeviceStats.Device(
        manufacturer = Build.MANUFACTURER,
        model = Build.MODEL,
        hardware = Build.HARDWARE,
        board = Build.BOARD,
        abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
        sdk = Build.VERSION.SDK_INT,
        release = Build.VERSION.RELEASE,
        securityPatch = Build.VERSION.SECURITY_PATCH,
    )

    private fun collectCpu(): DeviceStats.Cpu {
        val coreCount = Runtime.getRuntime().availableProcessors()
        val model = parseCpuInfo("/proc/cpuinfo", "Hardware")
            ?: parseCpuInfo("/proc/cpuinfo", "model name")
            ?: parseCpuInfo("/proc/cpuinfo", "Processor")
            ?: parseCpuInfo("/proc/cpuinfo", "CPU implementer")
            ?: Build.HARDWARE
        val maxFreqKhz = readSysfsInt("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq")
        val curFreqKhz = (0 until coreCount).mapNotNull { i ->
            readSysfsInt("/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq")
        }
        val minFreqKhz = readSysfsInt("/sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_min_freq")
        val gov = readSysfs("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor")
        return DeviceStats.Cpu(
            model = model,
            coreCount = coreCount,
            maxFreqMhz = maxFreqKhz / 1000,
            currentFreqMhz = curFreqKhz.map { it / 1000 },
            minFreqMhz = minFreqKhz / 1000,
            governor = gov,
        )
    }

    private fun collectRam(): DeviceStats.Ram {
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return DeviceStats.Ram(
            totalMB = mi.totalMem / 1024 / 1024,
            availableMB = mi.availMem / 1024 / 1024,
            lowMemory = mi.lowMemory,
            thresholdMB = mi.threshold / 1024 / 1024,
        )
    }

    private fun collectHeap(): DeviceStats.Heap {
        val rt = Runtime.getRuntime()
        return DeviceStats.Heap(
            maxMB = rt.maxMemory() / 1024 / 1024,
            totalMB = rt.totalMemory() / 1024 / 1024,
            freeMB = rt.freeMemory() / 1024 / 1024,
        )
    }

    private fun collectStorage(): DeviceStats.Storage {
        val ext = Environment.getDataDirectory()
        val stat = StatFs(ext.path)
        val blockSize = stat.blockSizeLong
        return DeviceStats.Storage(
            totalGB = (stat.blockCountLong * blockSize) / 1024 / 1024 / 1024,
            availableGB = (stat.availableBlocksLong * blockSize) / 1024 / 1024 / 1024,
            path = ext.absolutePath,
        )
    }

    private fun collectGpu(): DeviceStats.Gpu = try {
        val configInfo = am.deviceConfigurationInfo
        val glueVersion = configInfo?.glEsVersion ?: "n/a"
        DeviceStats.Gpu(
            vendor = Build.HARDWARE,
            renderer = configInfo?.reqGlEsVersion?.let { "ES $glueVersion" } ?: "n/a",
            glVersion = glueVersion,
            extensionCount = 0,
        )
    } catch (_: Exception) {
        DeviceStats.Gpu("n/a", "n/a", "n/a", 0)
    }

    private fun collectDisplay(): DeviceStats.Display {
        val dm = context.resources.displayMetrics
        var w = dm.widthPixels
        var h = dm.heightPixels
        var rr = 0f
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val bounds = wm.currentWindowMetrics.bounds
                val bw = bounds.width()
                val bh = bounds.height()
                if (bw > 0 && bh > 0) { w = bw; h = bh }
            }
            rr = @Suppress("DEPRECATION") wm.defaultDisplay.refreshRate
        } catch (_: Exception) { /* fallback to displayMetrics */ }

        return DeviceStats.Display(
            widthPx = w,
            heightPx = h,
            densityDpi = dm.densityDpi,
            density = dm.density,
            refreshRateHz = rr,
            physicalSizeInches = run {
                val xdpi = dm.xdpi
                val ydpi = dm.ydpi
                if (xdpi > 0 && ydpi > 0) {
                    val wIn = w.toDouble() / xdpi
                    val hIn = h.toDouble() / ydpi
                    String.format(Locale.US, "%.1f", kotlin.math.sqrt(wIn * wIn + hIn * hIn)).toDouble()
                } else 0.0
            },
        )
    }

    private fun collectBattery(): DeviceStats.Battery {
        // All detailed battery info comes from ACTION_BATTERY_CHANGED sticky intent
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return DeviceStats.Battery(
            levelPercent = pct,
            status = when (status) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
                BatteryManager.BATTERY_STATUS_FULL -> "Full"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
                else -> "Unknown($status)"
            },
            voltageMv = voltage,
            temperatureC = temp / 10.0,
        )
    }

    private fun collectThermalZones(): List<DeviceStats.ThermalZone> {
        val dir = File("/sys/class/thermal")
        if (!dir.exists()) return emptyList()
        return dir.listFiles()?.filter { it.name.startsWith("thermal_zone") }?.mapNotNull { zone ->
            val type = readSysfs("${zone.absolutePath}/type") ?: return@mapNotNull null
            val temp = readSysfsIntOrNull("${zone.absolutePath}/temp") ?: return@mapNotNull null
            DeviceStats.ThermalZone(type = type, milliDegrees = temp)
        }?.sortedBy { it.type } ?: emptyList()
    }

    private fun parseCpuInfo(path: String, key: String): String? {
        return try {
            File(path).readLines().firstOrNull { it.startsWith(key) }
                ?.substringAfter(":")?.trim()
        } catch (_: Exception) { null }
    }

    private fun readSysfs(path: String): String? {
        return try { File(path).readText().trim().ifBlank { null } } catch (_: Exception) { null }
    }

    private fun readSysfsInt(path: String): Int {
        return readSysfs(path)?.toIntOrNull() ?: 0
    }

    private fun readSysfsIntOrNull(path: String): Int? {
        return readSysfs(path)?.toIntOrNull()
    }
}

data class DeviceStats(
    val device: Device,
    val cpu: Cpu,
    val ram: Ram,
    val heap: Heap,
    val storage: Storage,
    val gpu: Gpu,
    val display: Display,
    val battery: Battery,
    val thermalZones: List<ThermalZone>,
) {
    data class Device(
        val manufacturer: String,
        val model: String,
        val hardware: String,
        val board: String,
        val abi: String,
        val sdk: Int,
        val release: String,
        val securityPatch: String,
    )

    data class Cpu(
        val model: String,
        val coreCount: Int,
        val maxFreqMhz: Int,
        val currentFreqMhz: List<Int>,
        val minFreqMhz: Int,
        val governor: String?,
    )

    data class Ram(
        val totalMB: Long,
        val availableMB: Long,
        val lowMemory: Boolean,
        val thresholdMB: Long,
    )

    data class Heap(
        val maxMB: Long,
        val totalMB: Long,
        val freeMB: Long,
    )

    data class Storage(
        val totalGB: Long,
        val availableGB: Long,
        val path: String,
    )

    data class Gpu(
        val vendor: String,
        val renderer: String,
        val glVersion: String,
        val extensionCount: Int,
    )

    data class Display(
        val widthPx: Int,
        val heightPx: Int,
        val densityDpi: Int,
        val density: Float,
        val refreshRateHz: Float,
        val physicalSizeInches: Double,
    )

    data class Battery(
        val levelPercent: Int,
        val status: String,
        val voltageMv: Int,
        val temperatureC: Double,
    )

    data class ThermalZone(
        val type: String,
        val milliDegrees: Int,
    ) {
        val temperatureC: Double get() = milliDegrees / 1000.0
    }

    fun format(): String = buildString {
        appendLine("== Device ==")
        appendLine("  ${device.manufacturer} ${device.model} (${device.hardware})")
        appendLine("  Board: ${device.board} | ABI: ${device.abi}")
        appendLine("  Android ${device.release} (SDK ${device.sdk}) | Patch: ${device.securityPatch}")
        appendLine()
        appendLine("== CPU ==")
        appendLine("  ${cpu.model}")
        appendLine("  Cores: ${cpu.coreCount} | Gov: ${cpu.governor ?: "N/A"}")
        appendLine("  Freq: ${cpu.minFreqMhz}–${cpu.maxFreqMhz} MHz")
        if (cpu.currentFreqMhz.isNotEmpty()) {
            appendLine("  Current: ${cpu.currentFreqMhz.joinToString(", ")} MHz")
        }
        appendLine()
        appendLine("== RAM ==")
        appendLine("  Total: ${ram.totalMB} MB | Available: ${ram.availableMB} MB | Low: ${ram.lowMemory}")
        appendLine("  Threshold: ${ram.thresholdMB} MB")
        appendLine()
        appendLine("== Heap (JVM) ==")
        appendLine("  Max: ${heap.maxMB} MB | Used: ${heap.totalMB - heap.freeMB} MB | Free: ${heap.freeMB} MB")
        appendLine()
        appendLine("== Storage ==")
        appendLine("  Total: ${storage.totalGB} GB | Free: ${storage.availableGB} GB @ ${storage.path}")
        appendLine()
        appendLine("== GPU ==")
        appendLine("  ${gpu.renderer} (${gpu.vendor})")
        appendLine("  GL ${gpu.glVersion} | Extensions: ${gpu.extensionCount}")
        appendLine()
        appendLine("== Display ==")
        appendLine("  ${display.widthPx}×${display.heightPx} @ ${display.refreshRateHz.toInt()} Hz")
        appendLine("  Density: ${display.densityDpi} dpi (${display.density}x) | ${display.physicalSizeInches}\"")
        appendLine()
        appendLine("== Battery ==")
        appendLine("  ${battery.levelPercent}% ${battery.status} | ${battery.voltageMv} mV | ${battery.temperatureC}°C")
        if (thermalZones.isNotEmpty()) {
            appendLine()
            appendLine("== Thermal Zones ==")
            thermalZones.forEach { tz ->
                appendLine("  ${tz.type}: ${String.format(Locale.US, "%.1f", tz.temperatureC)}°C")
            }
        }
    }
}
