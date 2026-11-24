package com.example.domain.device

import android.content.ContentResolver
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.example.R
import com.example.domain.alerts.AlertNotificationManager

/**
 * Equilibrium modes determining defense telemetry cadence vs. advisory power conservation.
 */
enum class EquilibriumMode {
    WARRIOR,
    BALANCE,
    LOW,
    SURVIVAL
}

/**
 * Safe, non-destructive equilibrium state returned by [BatteryEquilibriumManager.maintainEquilibre].
 *
 * Guarantees:
 * - Never shuts down mobile data, Wi-Fi, GPS, or essential system connectivity.
 * - Never terminates background/foreground services or hibernates the device.
 * - Uses advisory-only power recommendations where Android does not permit safe automated control.
 */
data class EquilibriumState(
    val mode: EquilibriumMode,
    val gpsIntervalMinutes: Int,
    val allowCamera: Boolean,
    val actions: List<String>,
    val isAdvisoryOnly: Boolean = true,
    val keepsConnectivityActive: Boolean = true,
    val keepsRecoveryServiceActive: Boolean = true
)

/**
 * Safe Battery Equilibrium Engine for InkingiGuard AI.
 *
 * Core Safety Contract:
 * 1. NEVER makes the phone unusable, NEVER hibernates the phone, and NEVER locks the device for low battery.
 * 2. NEVER shuts down mobile data, Wi-Fi, GPS, emergency calls, or messaging.
 * 3. NEVER kills application processes or stops [com.example.domain.service.RecoveryMonitoringService].
 * 4. Keeps GPS recovery active across all battery levels (adapting cadence rather than disabling tracking).
 * 5. Emits safe, non-destructive advisory recommendations when OS optimization cannot be safely automated.
 */
class BatteryEquilibriumManager(
    private val context: Context
) {

    companion object {
        private const val TAG = "BatteryEquilibriumMgr"
        private const val BALANCE_NOTIF_ID = 5020
    }

    private var lastNotifiedMode: EquilibriumMode? = null

    /**
     * Evaluates current battery status and returns a safe, non-destructive [EquilibriumState].
     */
    fun maintainEquilibre(
        batteryLevel: Int,
        isCharging: Boolean,
        isTheftDetected: Boolean = false
    ): EquilibriumState {
        val clampedLevel = batteryLevel.coerceIn(0, 100)

        if (isCharging) {
            return EquilibriumState(
                mode = EquilibriumMode.WARRIOR,
                gpsIntervalMinutes = 15,
                allowCamera = false,
                actions = buildList {
                    add("full_defense")
                    add("gps_15min")
                    add("normal_performance")
                    add("charging_active")
                    if (isTheftDetected) add("keep_recovery_service_active")
                },
                isAdvisoryOnly = true,
                keepsConnectivityActive = true,
                keepsRecoveryServiceActive = true
            )
        }

        return when {
            clampedLevel >= 41 -> {
                EquilibriumState(
                    mode = EquilibriumMode.WARRIOR,
                    gpsIntervalMinutes = 15,
                    allowCamera = false,
                    actions = buildList {
                        add("full_defense")
                        add("gps_15min")
                        add("normal_performance")
                        if (isTheftDetected) add("keep_recovery_service_active")
                    },
                    isAdvisoryOnly = true,
                    keepsConnectivityActive = true,
                    keepsRecoveryServiceActive = true
                )
            }

            clampedLevel in 15..40 -> {
                // BALANCE MODE: User retains full phone usability; recommendations are strictly advisory.
                val advisories = executeBalanceMode()
                notifyBalanceModeActive(clampedLevel)

                EquilibriumState(
                    mode = EquilibriumMode.BALANCE,
                    gpsIntervalMinutes = 15,
                    allowCamera = false,
                    actions = advisories + listOf(
                        "keep_defense_active",
                        "keep_connectivity_active",
                        "keep_recovery_service_active",
                        "advisory_only_no_process_kill"
                    ),
                    isAdvisoryOnly = true,
                    keepsConnectivityActive = true,
                    keepsRecoveryServiceActive = true
                )
            }

            clampedLevel in 5..14 -> {
                // LOW MODE: Preserve usability (calls, messaging, Wi-Fi/cellular) and keep GPS recovery active.
                val advisories = executeLowPowerMode()

                EquilibriumState(
                    mode = EquilibriumMode.LOW,
                    gpsIntervalMinutes = if (isTheftDetected) 1 else 30,
                    allowCamera = false,
                    actions = advisories + listOf(
                        if (isTheftDetected) "gps_recovery_active_1min" else "gps_30min",
                        "preserve_phone_usability",
                        "keep_connectivity_active",
                        "keep_recovery_service_active",
                        "allow_emergency_calls",
                        "advisory_only_no_process_kill"
                    ),
                    isAdvisoryOnly = true,
                    keepsConnectivityActive = true,
                    keepsRecoveryServiceActive = true
                )
            }

            else -> {
                // < 5% SURVIVAL MODE: Never hibernate, never lock, never cut network/GPS; best-effort advisory posture.
                val advisories = executeSurvivalMode()

                EquilibriumState(
                    mode = EquilibriumMode.SURVIVAL,
                    gpsIntervalMinutes = if (isTheftDetected) 1 else 60,
                    allowCamera = false,
                    actions = advisories + listOf(
                        "preserve_phone_usability",
                        "best_effort_gps_preservation",
                        "keep_connectivity_active",
                        "keep_recovery_service_active",
                        "allow_emergency_calls",
                        "no_hibernation_user_active",
                        "advisory_only_no_process_kill"
                    ),
                    isAdvisoryOnly = true,
                    keepsConnectivityActive = true,
                    keepsRecoveryServiceActive = true
                )
            }
        }
    }

    /**
     * Generates safe, advisory-only power recommendations for BALANCE mode (15%-40%).
     * Does NOT kill processes, revoke permissions, mutate system brightness, or toggle radios.
     */
    fun executeBalanceMode(): List<String> {
        val advisories = mutableListOf(
            "advisory_reduce_brightness_50_percent",
            "advisory_prefer_battery_friendly_network"
        )
        if (isMasterAutoSyncEnabledSafely()) {
            advisories.add("advisory_reduce_non_essential_autosync")
        }
        Log.d(TAG, "BalanceMode advisory evaluation complete (non-destructive, connectivity preserved).")
        return advisories
    }

    /**
     * Generates safe, advisory-only power recommendations for LOW mode (5%-14%).
     * Explicitly avoids killing processes or interrupting communications / recovery telemetry.
     */
    private fun executeLowPowerMode(): List<String> {
        Log.d(TAG, "LowPowerMode advisory evaluation complete (recovery GPS and user apps preserved).")
        return listOf(
            "advisory_enable_android_battery_saver",
            "advisory_reduce_screen_brightness",
            "advisory_limit_non_essential_background_drain"
        )
    }

    /**
     * Prepares non-destructive SURVIVAL mode (<5%).
     * Never stops foreground recovery services, never kills processes, never disables connectivity,
     * and never forces device locking or hibernation.
     */
    private fun executeSurvivalMode(): List<String> {
        Log.w(TAG, "Survival Mode advisory posture (<5%): preserving usability, connectivity, and best-effort GPS.")
        return listOf(
            "advisory_connect_charger_prompt",
            "advisory_preserve_critical_recovery_beacon"
        )
    }

    private fun isMasterAutoSyncEnabledSafely(): Boolean {
        return try {
            ContentResolver.getMasterSyncAutomatically()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Informs the owner safely without disrupting active apps or essential services.
     */
    private fun notifyBalanceModeActive(level: Int) {
        if (lastNotifiedMode == EquilibriumMode.BALANCE) return
        lastNotifiedMode = EquilibriumMode.BALANCE

        try {
            val notification = NotificationCompat.Builder(
                context,
                AlertNotificationManager.ALERT_CHANNEL_ID
            )
                .setSmallIcon(R.drawable.ic_stat_security)
                .setContentTitle("Battery Equilibrium: $level%")
                .setContentText("Battery $level% — Advisory power saving active while keeping protection & apps running")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "Battery $level% — InkingiGuard switched to advisory power-conservation cadence while keeping calls, connectivity, and GPS recovery active."
                    )
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()

            val manager = NotificationManagerCompat.from(context)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                manager.notify(BALANCE_NOTIF_ID, notification)
            }
        } catch (_: Exception) {}
    }
}
