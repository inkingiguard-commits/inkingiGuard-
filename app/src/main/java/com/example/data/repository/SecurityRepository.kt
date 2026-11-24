package com.example.data.repository

import com.example.core.model.EventSeverity
import com.example.core.model.SecurityEvent
import com.example.core.model.TrustedZone
import com.example.data.local.dao.LocationDao
import com.example.data.local.dao.SecurityEventDao
import com.example.data.local.dao.TrustedZoneDao
import com.example.data.local.entity.SecurityEventEntity
import com.example.data.local.entity.TrustedZoneEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class SecurityRepository(
    private val securityEventDao: SecurityEventDao,
    private val locationDao: LocationDao,
    private val trustedZoneDao: TrustedZoneDao
) {

    val allSecurityEvents: Flow<List<SecurityEvent>> =
        securityEventDao.getAllEvents().map { entities ->
            entities.map { it.toDomain() }
        }

    val activeTrustedZones: Flow<List<TrustedZone>> =
        trustedZoneDao.getActiveZones().map { entities ->
            entities.map { entity ->
                TrustedZone(
                    id = entity.id,
                    name = entity.name,
                    latitude = entity.latitude,
                    longitude = entity.longitude,
                    radiusMeters = entity.radiusMeters,
                    isEnabled = entity.isEnabled,
                    createdAt = entity.createdAt
                )
            }
        }

    suspend fun recordEvent(event: SecurityEvent) {
        securityEventDao.insertEvent(SecurityEventEntity.fromDomain(event))
    }

    suspend fun addTrustedZone(name: String, latitude: Double, longitude: Double, radiusMeters: Float) {
        val zone = TrustedZoneEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            latitude = latitude,
            longitude = longitude,
            radiusMeters = radiusMeters,
            isEnabled = true,
            createdAt = System.currentTimeMillis()
        )
        trustedZoneDao.insertZone(zone)

        recordEvent(
            SecurityEvent(
                id = UUID.randomUUID().toString(),
                timestamp = System.currentTimeMillis(),
                severity = EventSeverity.INFO,
                title = "Trusted Zone Created",
                description = "Geofenced perimeter '$name' established with radius ${radiusMeters.toInt()}m",
                source = "GeofenceEngine",
                riskContribution = 0
            )
        )
    }

    suspend fun removeTrustedZone(id: String) {
        trustedZoneDao.deleteZone(id)
    }

    suspend fun clearAllEvents() {
        securityEventDao.clearAll()
    }

    suspend fun seedDefaultDataIfEmpty() {
        // Production ledger: starts empty until real events occur
    }
}
