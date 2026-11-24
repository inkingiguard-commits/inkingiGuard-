package com.example.domain.monitoring

import com.example.core.model.MonitoringFrequency
import com.example.core.model.SecurityState

class AdaptiveMonitoringPolicy {

    data class MonitoringStrategy(
        val frequency: MonitoringFrequency,
        val locationIntervalMillis: Long,
        val sensorSamplingDelayMicros: Int,
        val shouldCollectHighFrequencyMotion: Boolean,
        val isBatteryConservationActive: Boolean,
        val policyDescription: String
    )

    fun determineStrategy(
        state: SecurityState,
        batteryPercentage: Int,
        isCharging: Boolean
    ): MonitoringStrategy {
        val isCriticalBattery = batteryPercentage < 15 && !isCharging

        if (isCriticalBattery) {
            return MonitoringStrategy(
                frequency = MonitoringFrequency.LOW,
                locationIntervalMillis = 60_000L, // 1 min heartbeats to save remaining power
                sensorSamplingDelayMicros = 200_000, // SensorManager.SENSOR_DELAY_NORMAL
                shouldCollectHighFrequencyMotion = false,
                isBatteryConservationActive = true,
                policyDescription = "CRITICAL BATTERY PRESERVATION (<15%): Prioritizing last known coordinates and emergency beacon over continuous sensor streams."
            )
        }

        return when (state) {
            SecurityState.NORMAL, SecurityState.RECOVERED -> MonitoringStrategy(
                frequency = MonitoringFrequency.LOW,
                locationIntervalMillis = 300_000L, // 5 minutes
                sensorSamplingDelayMicros = 200_000,
                shouldCollectHighFrequencyMotion = false,
                isBatteryConservationActive = false,
                policyDescription = "NOMINAL BASELINE: Passive sensor monitoring with relaxed battery consumption."
            )

            SecurityState.WARNING -> MonitoringStrategy(
                frequency = MonitoringFrequency.MODERATE,
                locationIntervalMillis = 60_000L, // 1 minute
                sensorSamplingDelayMicros = 60_000, // SensorManager.SENSOR_DELAY_UI
                shouldCollectHighFrequencyMotion = true,
                isBatteryConservationActive = false,
                policyDescription = "MODERATE VIGILANCE: Active motion anomaly surveillance and 60-second coordinate polling."
            )

            SecurityState.DANGER, SecurityState.SUSPECTED_LOST -> MonitoringStrategy(
                frequency = MonitoringFrequency.HIGH,
                locationIntervalMillis = 20_000L, // 20 seconds
                sensorSamplingDelayMicros = 20_000, // SensorManager.SENSOR_DELAY_GAME
                shouldCollectHighFrequencyMotion = true,
                isBatteryConservationActive = false,
                policyDescription = "HIGH DEFENSIVE FREQUENCY: Fast 20s location polling and multi-sensor correlation."
            )

            SecurityState.OWNER_CONFIRMED_STOLEN, SecurityState.STOLEN_MODE, SecurityState.RECOVERY_MODE -> MonitoringStrategy(
                frequency = MonitoringFrequency.CRITICAL,
                locationIntervalMillis = 10_000L, // 10 seconds
                sensorSamplingDelayMicros = 20_000,
                shouldCollectHighFrequencyMotion = true,
                isBatteryConservationActive = false,
                policyDescription = "CRITICAL STOLEN MODE: Rapid recovery beacons and continuous telemetry within Android runtime limits."
            )
        }
    }
}
