package com.example.core.brain.model

import com.example.core.model.EventSeverity
import com.example.core.model.IncidentSeverity
import com.example.core.model.IncidentStatus
import com.example.core.model.SecurityState
import java.security.MessageDigest

/**
 * Security Domain Ontology for InkingiGuard AI.
 * Explicitly structures relationships between Devices, Owners, Normalized Events,
 * Locations, and Correlated Security Incidents.
 */

enum class EventCategory {
    KINETIC_MOTION,
    GEOLOCATION_DISPLACEMENT,
    SIM_TELEPHONY,
    NETWORK_CONNECTIVITY,
    DEVICE_HEALTH,
    AUTHENTICATION_ACCESS,
    POLICY_TAMPER,
    SYSTEM_LIFECYCLE
}

data class NormalizedSecurityEvent(
    val eventId: String,
    val deviceId: String,
    val ownerId: String,
    val category: EventCategory,
    val source: String,
    val timestamp: Long = System.currentTimeMillis(),
    val severity: EventSeverity,
    val baseRiskImpact: Int,
    val title: String,
    val details: String,
    val attributes: Map<String, String> = emptyMap(),
    val deduplicationFingerprint: String = generateFingerprint(deviceId, category, title, timestamp)
) {
    companion object {
        fun generateFingerprint(deviceId: String, category: EventCategory, title: String, timestamp: Long): String {
            // Bucket by 30-second window to detect duplicate burst events
            val timeBucket = timestamp / 30_000L
            val raw = "$deviceId:${category.name}:$title:$timeBucket"
            val md = MessageDigest.getInstance("SHA-256")
            return md.digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}

data class OntologyDevice(
    val deviceId: String,
    val ownerId: String,
    val name: String,
    val model: String,
    val osVersion: String,
    val securityState: SecurityState = SecurityState.NORMAL,
    val enrolledTimestamp: Long = System.currentTimeMillis(),
    val lastHeartbeatTimestamp: Long = System.currentTimeMillis()
)

data class OntologyOwner(
    val ownerId: String,
    val email: String,
    val displayName: String,
    val contactPhone: String? = null,
    val enrolledDeviceIds: List<String> = emptyList()
)

data class OntologyLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long = System.currentTimeMillis(),
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val isInsideTrustedZone: Boolean = true,
    val zoneName: String? = null
)

data class OntologyIncident(
    val incidentId: String,
    val deviceId: String,
    val ownerId: String,
    val correlationClusterKey: String,
    val status: IncidentStatus,
    val severity: IncidentSeverity,
    val title: String,
    val riskScore: Int,
    val associatedEvents: List<NormalizedSecurityEvent>,
    val primaryFactors: List<String>,
    val evidenceSummary: String,
    val createdTimestamp: Long = System.currentTimeMillis(),
    val updatedTimestamp: Long = System.currentTimeMillis(),
    val resolvedTimestamp: Long? = null
)
