package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Table 1: events
 * Logs {heure, zone_nom, action} and timestamp each time the app is opened or evaluated.
 */
@Entity(tableName = "events")
data class BehaviorEventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val heure: Int, // 0 - 23
    val zone_nom: String, // e.g. "Maison", "Marché", "Bureau", "Zone Inconnue"
    val action: String = "APP_OPEN", // "APP_OPEN", "TELEMETRY_CHECK"
    val device_id: String = ""
)

/**
 * Table 2: arnaques_apprises
 * Persists detected scam patterns and frequency count for local adaptive learning.
 */
@Entity(tableName = "arnaques_apprises")
data class ArnaqueAppriseEntity(
    @PrimaryKey
    val pattern: String,
    val count: Int = 1,
    val lastDetectedTimestamp: Long = System.currentTimeMillis(),
    val category: String = "SMS_OU_LIEN"
)
