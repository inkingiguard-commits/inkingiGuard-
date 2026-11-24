package com.example

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.example.data.local.InkingiDatabase
import com.example.di.InkingiContainer
import com.example.domain.service.RecoveryMonitoringService
import com.example.domain.alerts.AlertNotificationManager

class InkingiGuardApplication : Application() {

    lateinit var container: InkingiContainer
        private set

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        try {
            val app = com.google.firebase.FirebaseApp.initializeApp(this)
            if (app != null) {
                val projectId = app.options.projectId
                android.util.Log.i("InkingiGuardApp", "FirebaseApp initialized with projectId: $projectId")
            } else {
                android.util.Log.w("InkingiGuardApp", "FirebaseApp.initializeApp returned null")
            }
        } catch (e: Exception) {
            android.util.Log.e("InkingiGuardApp", "Error during FirebaseApp.initializeApp: ${e.message}", e)
        }
        val database = InkingiDatabase.getInstance(this)
        container = InkingiContainer(this, database)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            
            val recoveryChannel = NotificationChannel(
                RecoveryMonitoringService.CHANNEL_ID,
                "InkingiGuard Recovery Protocol",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground telemetry tracking active during suspected loss or stolen mode."
            }

            val alertsChannel = NotificationChannel(
                AlertNotificationManager.ALERT_CHANNEL_ID,
                "InkingiGuard Security Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts for SIM changes, theft mode, and perimeter violations."
            }

            manager.createNotificationChannel(recoveryChannel)
            manager.createNotificationChannel(alertsChannel)
        }
    }
}
