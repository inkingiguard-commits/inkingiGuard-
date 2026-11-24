package com.example.core.brain.gateways

import com.example.core.brain.model.NormalizedSecurityEvent
import com.example.core.brain.model.OntologyIncident
import com.example.core.brain.model.OntologyLocation

/**
 * Gateways abstraction layer connecting the Security Brain to underlying subsystems.
 * Production implementations bind to real Android hardware and Firebase,
 * while Mock implementations enable robust local testing without external side effects.
 */

interface GpsLocationGateway {
    suspend fun getCurrentLocation(): OntologyLocation?
    fun isLocationPermissionGranted(): Boolean
}

class MockGpsLocationGateway(
    var simulatedLocation: OntologyLocation? = OntologyLocation(
        latitude = -1.9441,
        longitude = 30.0619,
        accuracy = 8.5f,
        isInsideTrustedZone = true,
        zoneName = "HQ Safe Zone"
    ),
    var hasPermission: Boolean = true
) : GpsLocationGateway {
    override suspend fun getCurrentLocation(): OntologyLocation? = simulatedLocation
    override fun isLocationPermissionGranted(): Boolean = hasPermission
}

interface SensorTelemetryGateway {
    fun getAvailableSensorTypes(): List<String>
    fun getLatestKineticMagnitude(): Float
}

class MockSensorTelemetryGateway(
    var availableSensors: List<String> = listOf("ACCELEROMETER", "GYROSCOPE", "PROXIMITY", "LIGHT"),
    var simulatedKineticMagnitude: Float = 9.81f
) : SensorTelemetryGateway {
    override fun getAvailableSensorTypes(): List<String> = availableSensors
    override fun getLatestKineticMagnitude(): Float = simulatedKineticMagnitude
}

interface CloudSyncGateway {
    suspend fun pushIncidentUpdate(incident: OntologyIncident): Boolean
    suspend fun pushSecurityEvent(event: NormalizedSecurityEvent): Boolean
    fun isCloudConnected(): Boolean
    val transmittedIncidentsCount: Int
    val transmittedEventsCount: Int
}

class MockCloudSyncGateway(
    var isConnected: Boolean = true
) : CloudSyncGateway {
    private val _storedIncidents = mutableListOf<OntologyIncident>()
    private val _storedEvents = mutableListOf<NormalizedSecurityEvent>()

    override suspend fun pushIncidentUpdate(incident: OntologyIncident): Boolean {
        if (!isConnected) return false
        _storedIncidents.add(incident)
        return true
    }

    override suspend fun pushSecurityEvent(event: NormalizedSecurityEvent): Boolean {
        if (!isConnected) return false
        _storedEvents.add(event)
        return true
    }

    override fun isCloudConnected(): Boolean = isConnected
    override val transmittedIncidentsCount: Int get() = _storedIncidents.size
    override val transmittedEventsCount: Int get() = _storedEvents.size

    fun getLocalTestIncidents(): List<OntologyIncident> = _storedIncidents.toList()
    fun getLocalTestEvents(): List<NormalizedSecurityEvent> = _storedEvents.toList()
}

interface NotificationAlertGateway {
    suspend fun sendEmergencyAlert(title: String, message: String, isCritical: Boolean): Boolean
    val dispatchedAlertsCount: Int
}

class MockNotificationAlertGateway : NotificationAlertGateway {
    private val _dispatched = mutableListOf<Triple<String, String, Boolean>>()

    override suspend fun sendEmergencyAlert(title: String, message: String, isCritical: Boolean): Boolean {
        _dispatched.add(Triple(title, message, isCritical))
        return true
    }

    override val dispatchedAlertsCount: Int get() = _dispatched.size
    fun getLastAlert(): Triple<String, String, Boolean>? = _dispatched.lastOrNull()
}

interface EvidenceStorageGateway {
    suspend fun storeEncryptedPayload(key: String, payload: String): Boolean
    suspend fun retrieveEncryptedPayload(key: String): String?
}

class MockEvidenceStorageGateway : EvidenceStorageGateway {
    private val storage = mutableMapOf<String, String>()

    override suspend fun storeEncryptedPayload(key: String, payload: String): Boolean {
        storage[key] = payload
        return true
    }

    override suspend fun retrieveEncryptedPayload(key: String): String? = storage[key]
}
