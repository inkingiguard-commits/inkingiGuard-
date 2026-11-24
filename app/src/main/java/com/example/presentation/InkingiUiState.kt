package com.example.presentation

import com.example.core.model.ConfidenceLevel
import com.example.core.model.DeviceHealthInfo
import com.example.core.model.DeviceManagementCapability
import com.example.core.model.DeviceProfile
import com.example.core.model.Incident
import com.example.core.model.LocationEvent
import com.example.core.model.MonitoringFrequency
import com.example.core.model.MotionEvent
import com.example.core.model.NetworkSecurityInfo
import com.example.core.model.RiskAssessment
import com.example.core.model.RiskLevel
import com.example.core.model.SecurityCommand
import com.example.core.model.SecurityEvent
import com.example.core.model.SecurityState
import com.example.core.model.SimSecurityInfo
import com.example.core.model.TamperEvent
import com.example.core.model.TheftReport
import com.example.core.model.TrustedZone
import com.example.domain.ai.InkingiAiIntelligenceEngine
import com.example.domain.alerts.AlertDispatchStatus
import com.example.domain.monitoring.AdaptiveMonitoringPolicy
import com.example.domain.sensor.SensorIntelligenceEngine
import com.example.domain.sync.QueueStatusSummary

data class InkingiUiState(
    val securityState: SecurityState = SecurityState.NORMAL,
    val riskAssessment: RiskAssessment = RiskAssessment(score = 0, level = RiskLevel.LOW, reasons = listOf("Sensors initialized in nominal baseline state.")),
    val confidenceLevel: ConfidenceLevel = ConfidenceLevel.LOW_CONFIDENCE,
    val aiAdvisory: InkingiAiIntelligenceEngine.AiAdvisoryReport? = null,
    val sensorMesh: SensorIntelligenceEngine.SensorMeshSnapshot = SensorIntelligenceEngine.SensorMeshSnapshot(emptyList()),
    val location: LocationEvent? = null,
    val deviceHealth: DeviceHealthInfo = DeviceHealthInfo(
        batteryPercentage = 85,
        isCharging = false,
        temperatureCelsius = 31f,
        uptimeMillis = 100000L,
        osVersion = "Android",
        deviceModel = "Device",
        manufacturer = "OEM"
    ),
    val networkInfo: NetworkSecurityInfo = NetworkSecurityInfo(
        isConnected = true,
        isWifi = true,
        isCellular = false,
        networkType = "SECURE_WIFI"
    ),
    val simInfo: SimSecurityInfo = SimSecurityInfo(
        simState = "READY",
        isSimPresent = true,
        carrierName = "Mobile Carrier",
        simOperatorNumeric = "00000"
    ),
    val recentEvents: List<SecurityEvent> = emptyList(),
    val trustedZones: List<TrustedZone> = emptyList(),
    val deviceProfile: DeviceProfile = DeviceProfile(
        deviceId = "IG-NODE",
        deviceName = "Android Node",
        ownerEmail = "",
        isRegistered = false,
        masterPinHash = ""
    ),
    val failedAuthAttempts: Int = 0,
    val isArmArmed: Boolean = false,
    val isAuthenticating: Boolean = false,
    val currentLanguage: String = "fr",

    // Phase 2 Incident Response Engine additions
    val activeIncident: Incident? = null,
    val allIncidents: List<Incident> = emptyList(),
    val latestTheftReport: TheftReport? = null,
    val deviceCapabilities: DeviceManagementCapability = DeviceManagementCapability(
        isDeviceOwner = false,
        isProfileOwner = false,
        isAdminActive = false,
        canLockDevice = false,
        canApplyManagedPolicies = false,
        canPerformAuthorizedRecoveryActions = false,
        provisioningRequirementNote = "Standard Application Mode"
    ),
    val pendingQueueCount: Int = 0,
    val monitoringStrategy: AdaptiveMonitoringPolicy.MonitoringStrategy = AdaptiveMonitoringPolicy.MonitoringStrategy(
        frequency = MonitoringFrequency.LOW,
        locationIntervalMillis = 300_000L,
        sensorSamplingDelayMicros = 200_000,
        shouldCollectHighFrequencyMotion = false,
        isBatteryConservationActive = false,
        policyDescription = "NOMINAL BASELINE: Passive sensor monitoring with relaxed battery consumption."
    ),
    val tamperAlerts: List<TamperEvent> = emptyList(),
    val isForegroundServiceActive: Boolean = false,
    val baselineProfile: com.example.core.brain.baseline.BaselineProfile = com.example.core.brain.baseline.BaselineProfile(),
    val learnedDays: Int = 0,
    val behaviorBaseline: com.example.domain.ai.BaselineProfile = com.example.domain.ai.BaselineProfile(),
    val isWithinBaseline: Boolean = true,
    val isLearningPaused: Boolean = false,
    val baselineStatusMessage: String = "En phase d'apprentissage (0/7 jours réels, 0 obs)",
    val totalBlockedScams: Int = 0,
    val totalLearnedScamPatterns: Int = 0,
    val isFindMyPhoneAlarmActive: Boolean = false,
    val currentUserRole: com.example.core.model.UserRole = com.example.core.model.UserRole.OWNER,
    val trustedPersons: List<com.example.core.model.TrustedPerson> = emptyList(),
    val threatState: com.example.core.model.CorrelationThreatState = com.example.core.model.CorrelationThreatState.NORMAL,
    val isGpsAvailable: Boolean = true,
    val correlatedSignals: List<String> = emptyList(),
    val recentLocationHistory: List<com.example.data.local.entity.LocationHistoryEntity> = emptyList(),
    val isAuthenticated: Boolean = false,
    val authenticatedUserEmail: String? = null,
    val authenticatedUserId: String? = null,
    val isEmailVerified: Boolean = false,
    val isFirebaseAvailable: Boolean = true,
    val isAuthLoading: Boolean = false,
    val authErrorMessage: String? = null,
    val authSuccessMessage: String? = null,
    val authVerificationSent: Boolean = false,
    val authResetEmailSent: Boolean = false,
    val showAuthDialog: Boolean = false,

    // Phase 2 Super Admin State
    val isSuperAdmin: Boolean = false,
    val isCheckingSuperAdmin: Boolean = false,
    val adminVerificationMessage: String? = null,
    val superAdminUserCount: Int? = null,

    // Persisted recovery contact & lock-screen message
    val recoveryContactPhone: String = "",
    val lockScreenRecoveryMessage: String = "",

    // Live GPS sync, date-filtered history & remote map tracking
    val locationSyncStatus: String = "IDLE",
    val lastSuccessfulLocationSyncAt: Long? = null,
    val unsyncedLocationCount: Int = 0,
    val selectedHistoryDateMillis: Long = 0L,
    val dateFilteredLocalHistory: List<com.example.data.local.entity.LocationHistoryEntity> = emptyList(),
    val ownerRemoteDevices: List<com.example.domain.recovery.RemoteTrackedDeviceSummary> = emptyList(),
    val selectedRemoteDeviceId: String? = null,
    val remoteTrackedTelemetry: com.example.domain.recovery.RemoteRecoveryTelemetry? = null,
    val remoteDeviceBreadcrumbs: List<com.example.domain.recovery.LocationBreadcrumbPoint> = emptyList(),
    val remoteSelectedDateMillis: Long = 0L,
    val isObservingRemoteDevice: Boolean = false,
    val remoteTrackingError: String? = null,

    // Real Police Evidence Report Submission Result State (Task D)
    val policeReportState: PoliceReportState = PoliceReportState(),

    // Truthful Security Event Queue & Trusted Contact Alert Dispatch Status (Correction 2)
    val queueStatusSummary: QueueStatusSummary = QueueStatusSummary(),
    val alertDispatchStatus: AlertDispatchStatus = AlertDispatchStatus(),

    // Truthful Runtime GPS Permission & Service Health (GPS Map Correction)
    val hasFineLocationPermission: Boolean = false,
    val hasCoarseLocationPermission: Boolean = false,
    val hasBackgroundLocationPermission: Boolean = false,
    val isLocationUpdatesActive: Boolean = false,
    val lastForegroundServiceError: String? = null,

    // Master PIN & Offline Vault Keystore Security Status
    val isPinConfigured: Boolean = false,
    val isVaultKeystoreHealthy: Boolean = true,
    val vaultErrorMessage: String? = null,
    val pinActionError: String? = null,
    val pinActionSuccess: String? = null,
    val pinLockoutRemainingMillis: Long = 0L
)

enum class PoliceReportSubmissionStatus {
    IDLE,
    SUBMITTING,
    SUBMITTED,
    PENDING,
    FAILED,
    UNAVAILABLE
}

data class PoliceReportState(
    val status: PoliceReportSubmissionStatus = PoliceReportSubmissionStatus.IDLE,
    val evidenceSyncStatus: String? = null,
    val emailRelayStatus: String? = null,
    val reportPreview: String? = null,
    val detailMessage: String? = null,
    val updatedAtMillis: Long? = null
)
