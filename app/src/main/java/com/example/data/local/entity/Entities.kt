package com.example.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.core.model.EventSeverity
import com.example.core.model.SecurityEvent

@Entity(tableName = "security_events")
data class SecurityEventEntity(
    @PrimaryKey
    val id: String,
    val timestamp: Long,
    val severity: String,
    val title: String,
    val description: String,
    val source: String,
    val riskContribution: Int
) {
    fun toDomain(): SecurityEvent = SecurityEvent(
        id = id,
        timestamp = timestamp,
        severity = try { EventSeverity.valueOf(severity) } catch (_: Exception) { EventSeverity.INFO },
        title = title,
        description = description,
        source = source,
        riskContribution = riskContribution
    )

    companion object {
        fun fromDomain(event: SecurityEvent): SecurityEventEntity = SecurityEventEntity(
            id = event.id,
            timestamp = event.timestamp,
            severity = event.severity.name,
            title = event.title,
            description = event.description,
            source = event.source,
            riskContribution = event.riskContribution
        )
    }
}

@Entity(tableName = "location_history")
data class LocationHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double,
    val speed: Float,
    val bearing: Float,
    val timestamp: Long,
    val isInsideTrustedZone: Boolean,
    val syncedWithBackend: Boolean = false,
    val provider: String = "gps",
    val isFreshFix: Boolean = false,
    val batteryLevel: Int = -1,
    val deviceId: String = ""
)

@Entity(tableName = "trusted_zones")
data class TrustedZoneEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val isEnabled: Boolean,
    val createdAt: Long
)

@Entity(tableName = "event_fingerprints")
data class EventFingerprintEntity(
    @PrimaryKey
    val fingerprint: String,
    val timestamp: Long
)

