package com.example.domain.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import com.example.core.model.DeviceHealthInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class DeviceHealthEngine(
    private val context: Context
) {

    private val _healthInfo = MutableStateFlow(getInitialHealth())
    val healthInfo: StateFlow<DeviceHealthInfo> = _healthInfo.asStateFlow()

    private fun getInitialHealth(): DeviceHealthInfo {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 85

        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val temp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        val tempCelsius = if (temp != null && temp > 0) temp / 10f else 31.5f

        val uptimeMillis = SystemClock.elapsedRealtime()
        val bootTimestamp = System.currentTimeMillis() - uptimeMillis

        // Reboot anomaly is only flagged when abnormal shutdown occurred or simulated
        val isRecentReboot = false

        return DeviceHealthInfo(
            batteryPercentage = batteryPct,
            isCharging = isCharging,
            temperatureCelsius = tempCelsius,
            uptimeMillis = uptimeMillis,
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = Build.MODEL ?: "Standard Android",
            manufacturer = Build.MANUFACTURER ?: "Generic",
            isRebootAnomalyDetected = isRecentReboot,
            lastBootTimestamp = bootTimestamp
        )
    }

    fun refreshHealth() {
        _healthInfo.value = getInitialHealth()
    }

    fun triggerSimulatedRebootAnomaly() {
        _healthInfo.value = _healthInfo.value.copy(
            isRebootAnomalyDetected = true,
            uptimeMillis = 45000L // 45 seconds
        )
    }

    fun clearRebootAnomaly() {
        _healthInfo.value = _healthInfo.value.copy(
            isRebootAnomalyDetected = false
        )
    }

    fun setSimulatedBatteryPercentage(percentage: Int, isCharging: Boolean = false) {
        _healthInfo.value = _healthInfo.value.copy(
            batteryPercentage = percentage.coerceIn(0, 100),
            isCharging = isCharging
        )
    }
}
