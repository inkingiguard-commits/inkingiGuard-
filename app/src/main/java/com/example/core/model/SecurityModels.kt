package com.example.core.model

data class RiskAssessment(
    val score: Int,
    val level: RiskLevel,
    val reasons: List<String>,
    val timestamp: Long = System.currentTimeMillis(),
    val aiAdvisoryExplanation: String? = null
)

data class SecurityEvent(
    val id: String,
    val timestamp: Long,
    val severity: EventSeverity,
    val title: String,
    val description: String,
    val source: String,
    val riskContribution: Int
)

data class MotionEvent(
    val eventId: String,
    val deviceId: String,
    val sensorType: String,
    val movementType: MovementType,
    val confidence: ConfidenceLevel,
    val timestamp: Long,
    val accelerationMagnitude: Float,
    val rotationRate: Float,
    val details: String = ""
)

data class LocationEvent(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val altitude: Double = 0.0,
    val speed: Float = 0.0f,
    val bearing: Float = 0.0f,
    val timestamp: Long = System.currentTimeMillis(),
    val provider: String = "gps",
    val isInsideTrustedZone: Boolean = true,
    val closestZoneName: String? = null,
    val distanceToClosestZoneMeters: Float = 0f,
    val isFreshFix: Boolean = false,
    val locationSource: String = "SYSTEM"
)

data class TrustedZone(
    val id: String,
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val radiusMeters: Float,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

data class TrustedPerson(
    val id: String,
    val name: String,
    val phoneNumber: String,
    val relationship: String = "Famille / Proche",
    val canReceiveTheftAlerts: Boolean = true,
    val addedTimestamp: Long = System.currentTimeMillis()
)

data class DeviceHealthInfo(
    val batteryPercentage: Int,
    val isCharging: Boolean,
    val temperatureCelsius: Float?,
    val uptimeMillis: Long,
    val osVersion: String,
    val deviceModel: String,
    val manufacturer: String,
    val isRebootAnomalyDetected: Boolean = false,
    val lastBootTimestamp: Long = 0L
)

data class NetworkSecurityInfo(
    val isConnected: Boolean,
    val isWifi: Boolean,
    val isCellular: Boolean,
    val networkType: String,
    val operatorName: String? = null,
    val isVpnActive: Boolean = false
)

data class SimSecurityInfo(
    val simState: String,
    val isSimPresent: Boolean,
    val carrierName: String,
    val simOperatorNumeric: String,
    val hasSimChanged: Boolean = false,
    val lastChecked: Long = System.currentTimeMillis()
)

data class DeviceProfile(
    val deviceId: String,
    val deviceName: String,
    val ownerEmail: String,
    val isRegistered: Boolean,
    val masterPinHash: String,
    val biometricEnabled: Boolean = true,
    val registrationTimestamp: Long = System.currentTimeMillis()
)

// =======================================================
// Phase 2 Incident Response & Command Center Data Models
// =======================================================

data class Incident(
    val incidentId: String,
    val deviceId: String,
    val ownerId: String,
    val title: String,
    val status: IncidentStatus,
    val severity: IncidentSeverity,
    val riskScore: Int,
    val triggeringFactors: List<String>,
    val createdTimestamp: Long = System.currentTimeMillis(),
    val resolvedTimestamp: Long? = null,
    val theftReportId: String? = null
)

data class TheftReport(
    val reportId: String,
    val deviceId: String,
    val ownerId: String,
    val incidentId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val lastKnownLatitude: Double,
    val lastKnownLongitude: Double,
    val carrierName: String,
    val simState: String,
    val batteryPercentage: Int,
    val cryptographicSignature: String
)

data class SecurityCommand(
    val commandId: String,
    val deviceId: String,
    val ownerId: String,
    val commandType: CommandType,
    val createdTimestamp: Long = System.currentTimeMillis(),
    val expirationTimestamp: Long = System.currentTimeMillis() + (1000 * 60 * 60 * 24), // 24h
    val status: CommandStatus = CommandStatus.PENDING,
    val authorizationResult: String = "PENDING_VERIFICATION",
    val payload: String = ""
)

data class AuditEvent(
    val auditId: String,
    val incidentId: String?,
    val deviceId: String,
    val stateBefore: SecurityState,
    val stateAfter: SecurityState,
    val reason: String,
    val riskScore: Int,
    val actor: String,
    val timestamp: Long = System.currentTimeMillis(),
    val authorizationResult: String
)

data class TamperEvent(
    val id: String,
    val timestamp: Long = System.currentTimeMillis(),
    val tamperType: TamperType,
    val description: String,
    val severity: EventSeverity
)

data class DeviceManagementCapability(
    val isDeviceOwner: Boolean,
    val isProfileOwner: Boolean,
    val isAdminActive: Boolean,
    val canLockDevice: Boolean,
    val canApplyManagedPolicies: Boolean,
    val canPerformAuthorizedRecoveryActions: Boolean,
    val provisioningRequirementNote: String
)

data class QueueItem(
    val id: String,
    val eventType: String,
    val timestamp: Long = System.currentTimeMillis(),
    val priority: QueuePriority,
    val payloadJson: String,
    val retryCount: Int = 0,
    val syncStatus: SyncStatus = SyncStatus.PENDING
)
