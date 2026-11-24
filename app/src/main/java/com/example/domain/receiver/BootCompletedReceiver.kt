package com.example.domain.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.data.repository.DeviceSettingsRepository
import com.example.domain.alerts.AlertNotificationManager
import com.example.domain.management.DeviceLockManager
import com.example.domain.service.RecoveryMonitoringService
import com.example.domain.sync.LocationSyncWorker

/**
 * BootCompletedReceiver:
 * Ensures STOLEN_MODE / Suivi Vol foreground tracking, deferred WorkManager sync,
 * and owner lock-screen recovery message resume automatically after device reboot.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            Log.i("BootCompletedReceiver", "Device reboot/restart detected: verifying anti-theft service state.")
            val isStolen = DeviceSettingsRepository.isStolenTrackingActive(context)
            if (isStolen) {
                Log.w("BootCompletedReceiver", "Stolen mode active: restarting active GPS tracking & WorkManager sync.")
                val savedContact = DeviceSettingsRepository.getSavedRecoveryContact(context)
                val savedMessage = DeviceSettingsRepository.getSavedLockScreenMessage(context)

                // Restarts foreground tracking service which registers fresh LocationManager/Fused updates
                RecoveryMonitoringService.startService(
                    context = context,
                    contactNumber = savedContact.ifBlank { null },
                    intervalMillis = RecoveryMonitoringService.DEFAULT_THEFT_INTERVAL_MILLIS
                )

                // Enqueue deferred sync so buffered offline locations upload once connectivity returns
                LocationSyncWorker.enqueue(context)

                if (savedMessage.isNotBlank()) {
                    try {
                        val dpmText = if (savedContact.isNotBlank()) {
                            "$savedMessage — Contact: $savedContact"
                        } else {
                            savedMessage
                        }
                        DeviceLockManager(context).setLockScreenRecoveryMessage(dpmText)
                        AlertNotificationManager(context).showLockScreenRecoveryNotification(
                            recoveryMessage = savedMessage,
                            emergencyContact = savedContact
                        )
                    } catch (e: Exception) {
                        Log.w("BootCompletedReceiver", "Could not re-apply lock screen recovery message on boot: ${e.message}")
                    }
                }
            }
        }
    }
}
