package com.example.core.brain.gateways

import android.content.Context
import androidx.core.content.ContextCompat
import com.example.core.brain.model.NormalizedSecurityEvent
import com.example.core.brain.model.OntologyIncident
import com.example.core.brain.model.OntologyLocation
import com.example.core.model.QueuePriority
import com.example.data.local.dao.IncidentDao
import com.example.data.local.dao.SecurityEventDao
import com.example.data.local.entity.IncidentEntity
import com.example.data.local.entity.SecurityEventEntity
import com.example.domain.alerts.AlertNotificationManager
import com.example.domain.location.LocationIntelligenceEngine
import com.example.domain.security.OfflineSecurityVault
import com.example.domain.sensor.SensorIntelligenceEngine
import com.example.domain.sync.SecurityEventQueue

/**
 * Production Android Gateway implementations binding the SecurityBrain
 * directly to native device telemetry, sensors, local Room databases,
 * hardware KeyStore vaults, and notifications.
 */

class AndroidGpsLocationGateway(
    private val locationEngine: LocationIntelligenceEngine,
    private val context: Context
) : GpsLocationGateway {

    override suspend fun getCurrentLocation(): OntologyLocation? {
        val last = locationEngine.lastLocation.value ?: return null
        return OntologyLocation(
            latitude = last.latitude,
            longitude = last.longitude,
            accuracy = last.accuracy,
            timestamp = last.timestamp,
            speed = last.speed,
            bearing = last.bearing,
            isInsideTrustedZone = last.isInsideTrustedZone,
            zoneName = last.closestZoneName
        )
    }

    override fun isLocationPermissionGranted(): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return hasFine || hasCoarse
    }
}

class AndroidSensorTelemetryGateway(
    private val sensorEngine: SensorIntelligenceEngine
) : SensorTelemetryGateway {

    override fun getAvailableSensorTypes(): List<String> {
        return sensorEngine.meshSnapshot.value.availableSensors
            .filter { it.isAvailable }
            .map { it.displayName }
    }

    override fun getLatestKineticMagnitude(): Float {
        return sensorEngine.meshSnapshot.value.currentAccelerationMagnitude
    }
}

class AndroidCloudSyncGateway(
    private val eventQueue: SecurityEventQueue,
    private val securityEventDao: SecurityEventDao,
    private val incidentDao: IncidentDao
) : CloudSyncGateway {

    private var transmittedIncidents = 0
    private var transmittedEvents = 0

    override suspend fun pushIncidentUpdate(incident: OntologyIncident): Boolean {
        // Persist unified SecurityBrain incident in Room so incident history stays synchronized
        try {
            val domainIncident = com.example.core.model.Incident(
                incidentId = incident.incidentId,
                deviceId = incident.deviceId,
                ownerId = incident.ownerId,
                title = incident.title,
                status = incident.status,
                severity = incident.severity,
                riskScore = incident.riskScore,
                triggeringFactors = incident.primaryFactors,
                createdTimestamp = incident.createdTimestamp,
                resolvedTimestamp = incident.resolvedTimestamp
            )
            incidentDao.insertIncident(IncidentEntity.fromDomain(domainIncident))
        } catch (_: Exception) {}

        // Enqueue into persistent offline-first queue with safe JSON encoding & idempotency key
        val incidentJson = org.json.JSONObject().apply {
            put("id", incident.incidentId)
            put("deviceId", incident.deviceId)
            put("title", incident.title)
            put("score", incident.riskScore)
            put("status", incident.status.name)
        }.toString()
        eventQueue.enqueue(
            eventType = "INCIDENT_UPDATE",
            payload = incidentJson,
            priority = QueuePriority.HIGH,
            idempotencyKey = "incident_${incident.incidentId}_${incident.status.name}"
        )
        transmittedIncidents++
        return true
    }

    override suspend fun pushSecurityEvent(event: NormalizedSecurityEvent): Boolean {
        // Store locally in Room SecurityEventDao and enqueue for synchronization
        val entity = SecurityEventEntity(
            id = event.eventId,
            timestamp = event.timestamp,
            severity = event.severity.name,
            title = event.title,
            description = event.details,
            source = event.source,
            riskContribution = event.baseRiskImpact
        )
        securityEventDao.insertEvent(entity)

        val eventJson = org.json.JSONObject().apply {
            put("id", event.eventId)
            put("deviceId", event.deviceId)
            put("title", event.title)
            put("category", event.category.name)
            put("risk", event.baseRiskImpact)
        }.toString()
        eventQueue.enqueue(
            eventType = "NORMALIZED_SECURITY_EVENT",
            payload = eventJson,
            priority = QueuePriority.NORMAL,
            idempotencyKey = "sec_event_${event.eventId}"
        )
        transmittedEvents++
        return true
    }

    override fun isCloudConnected(): Boolean = true
    override val transmittedIncidentsCount: Int get() = transmittedIncidents
    override val transmittedEventsCount: Int get() = transmittedEvents
}

class AndroidNotificationAlertGateway(
    private val alertManager: AlertNotificationManager
) : NotificationAlertGateway {

    private var dispatched = 0

    override suspend fun sendEmergencyAlert(title: String, message: String, isCritical: Boolean): Boolean {
        alertManager.dispatchAlert(title = title, body = message, isCritical = isCritical)
        dispatched++
        return true
    }

    override val dispatchedAlertsCount: Int get() = dispatched
}

class AndroidEvidenceStorageGateway(
    private val vault: OfflineSecurityVault
) : EvidenceStorageGateway {

    override suspend fun storeEncryptedPayload(key: String, payload: String): Boolean {
        return vault.encryptAndStore(key, payload)
    }

    override suspend fun retrieveEncryptedPayload(key: String): String? {
        return vault.retrieveAndDecrypt(key)
    }
}
