package com.retro99.ttsbench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

data class DeviceInfo(
    val model: String,
    val soc: String,
    val cores: Int,
    val hasDotProd: Boolean,
) {
    fun summary(): String = "$model, SoC=$soc, cores=$cores, dotprod=$hasDotProd"

    companion object {
        fun read(): DeviceInfo {
            val cpuInfo = runCatching { File("/proc/cpuinfo").readText() }.getOrDefault("")
            val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
            } else {
                Build.HARDWARE
            }
            return DeviceInfo(
                model = "${Build.MANUFACTURER} ${Build.MODEL}",
                soc = soc,
                cores = Runtime.getRuntime().availableProcessors(),
                hasDotProd = cpuInfo.lineSequence().any { line ->
                    line.startsWith("Features") && line.split(' ').contains("asimddp")
                },
            )
        }
    }
}

object DeviceState {

    fun thermalStatus(context: Context): Int {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return power.currentThermalStatus
    }

    fun isCharging(context: Context): Boolean {
        val battery = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }
}
