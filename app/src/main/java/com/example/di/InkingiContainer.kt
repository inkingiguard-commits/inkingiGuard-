package com.example.di

import android.content.Context
import com.example.core.brain.gateways.AndroidCloudSyncGateway
import com.example.core.brain.gateways.AndroidEvidenceStorageGateway
import com.example.core.brain.gateways.AndroidGpsLocationGateway
import com.example.core.brain.gateways.AndroidNotificationAlertGateway
import com.example.core.brain.gateways.AndroidSensorTelemetryGateway
import com.example.core.brain.model.OntologyDevice
import com.example.core.brain.model.OntologyOwner
import com.example.core.brain.correlation.EventCorrelationEngine
import com.example.core.brain.orchestration.SecurityBrain
import com.example.core.security.RiskEngine
import com.example.core.security.SecurityStateManager
import com.example.core.security.SignalCorrelationEngine
import com.example.data.local.InkingiDatabase
import com.example.data.repository.DeviceSettingsRepository
import com.example.data.repository.SecurityRepository
import com.example.domain.ai.InkingiAiIntelligenceEngine
import com.example.domain.alerts.AlertNotificationManager
import com.example.domain.command.SecurityCommandProcessor
import com.example.domain.device.DeviceHealthEngine
import com.example.domain.device.NetworkIntelligenceEngine
import com.example.domain.device.SimSecurityEngine
import com.example.domain.incident.IncidentResponseEngine
import com.example.domain.location.LocationIntelligenceEngine
import com.example.domain.management.DeviceLockManager
import com.example.domain.management.EmergencyResetManager
import com.example.domain.monitoring.AdaptiveMonitoringPolicy
import com.example.domain.security.OfflineSecurityVault
import com.example.domain.sensor.SensorIntelligenceEngine
import com.example.domain.sync.SecurityEventQueue
import com.example.domain.tamper.TamperDetectionEngine

class InkingiContainer(
    val context: Context,
    val database: InkingiDatabase,
    customAuthRepository: com.example.data.repository.AuthRepository? = null,
    customEvidenceGateway: com.example.domain.evidence.PoliceEvidenceCloudGateway? = null,
    customEmailRelayGateway: com.example.domain.evidence.EmailRelayGateway? = null,
    customEventBackendGateway: com.example.domain.sync.SecurityEventBackendGateway? = null,
    customTrustedContactGateway: com.example.domain.sync.TrustedContactDispatchBackend? = null
) {
    val offlineVault = OfflineSecurityVault(context)
    val deviceSettingsRepository = DeviceSettingsRepository(context, offlineVault)
    val authRepository: com.example.data.repository.AuthRepository =
        customAuthRepository ?: com.example.data.repository.FirebaseAuthRepository(context)
    val superAdminRepository: com.example.data.repository.SuperAdminRepository = com.example.data.repository.FirebaseSuperAdminRepository(context)
    val securityRepository = SecurityRepository(
        securityEventDao = database.securityEventDao(),
        locationDao = database.locationDao(),
        trustedZoneDao = database.trustedZoneDao()
    )

    val riskEngine = RiskEngine()
    val securityStateManager = SecurityStateManager(
        securityEventDao = database.securityEventDao(),
        auditEventDao = database.auditEventDao(),
        deviceIdProvider = { deviceSettingsRepository.deviceProfile.value.deviceId }
    )
    val signalCorrelationEngine = SignalCorrelationEngine(riskEngine)

    val sensorEngine = SensorIntelligenceEngine(
        context = context,
        deviceId = deviceSettingsRepository.deviceProfile.value.deviceId
    )

    val deviceHealthEngine = DeviceHealthEngine(context)
    val locationEngine = LocationIntelligenceEngine(
        context = context,
        locationDao = database.locationDao(),
        batteryPercentageProvider = { deviceHealthEngine.healthInfo.value.batteryPercentage },
        deviceIdProvider = { deviceSettingsRepository.deviceProfile.value.deviceId }
    )
    val batteryEquilibriumManager = com.example.domain.device.BatteryEquilibriumManager(context)
    val networkEngine = NetworkIntelligenceEngine(context)
    val simEngine = SimSecurityEngine(context)
    val aiEngine = InkingiAiIntelligenceEngine()

    // Phase 2 Incident Response & Management Engines
    val deviceLockManager = DeviceLockManager(context)
    val eventBackendGateway: com.example.domain.sync.SecurityEventBackendGateway =
        customEventBackendGateway ?: com.example.domain.sync.FirestoreSecurityEventGateway(
            context = context,
            trustedContactDispatchBackend = customTrustedContactGateway,
            ownerUidProvider = { authRepository.currentUser?.uid },
            deviceIdProvider = {
                DeviceSettingsRepository.getDeviceId(context)
                    .ifBlank { deviceSettingsRepository.deviceProfile.value.deviceId }
            }
        )
    val securityEventQueue = SecurityEventQueue(
        queueItemDao = database.queueItemDao(),
        vault = offlineVault,
        backendGateway = eventBackendGateway,
        retryMetadataStore = com.example.domain.sync.QueueRetryMetadataStore(
            context.getSharedPreferences("inkingi_queue_retry_prefs", Context.MODE_PRIVATE)
        )
    )
    val adaptiveMonitoringPolicy = AdaptiveMonitoringPolicy()
    val tamperEngine = TamperDetectionEngine(context, deviceLockManager)
    val evidenceRepository = com.example.domain.evidence.EvidenceRepository(
        eventQueue = securityEventQueue,
        cloudGateway = customEvidenceGateway ?: com.example.domain.evidence.FirebasePoliceEvidenceGateway()
    )
    val emailRelayGateway: com.example.domain.evidence.EmailRelayGateway =
        customEmailRelayGateway ?: com.example.domain.evidence.FirestoreEmailRelayGateway()
    val behaviorLearningManager = com.example.domain.ai.BehaviorLearningManager(
        database.behaviorEventDao(),
        deviceSettingsRepository.deviceProfile.value.deviceId
    )
    val emergencyResetManager = EmergencyResetManager(context, deviceLockManager)
    val alertManager = AlertNotificationManager(
        context = context,
        eventQueue = securityEventQueue,
        queueItemDao = database.queueItemDao(),
        ownerUidProvider = { authRepository.currentUser?.uid },
        deviceIdProvider = {
            DeviceSettingsRepository.getDeviceId(context)
                .ifBlank { deviceSettingsRepository.deviceProfile.value.deviceId }
        },
        authorizedContactsProvider = { deviceSettingsRepository.trustedPersons.value },
        isNetworkAvailableProvider = { networkEngine.networkInfo.value.isConnected },
        isExternalContactBackendConfiguredProvider = {
            customTrustedContactGateway?.isConfigured() == true
        }
    )
    val lostPhoneAlarmManager = com.example.domain.alerts.LostPhoneAlarmManager(context)

    val incidentResponseEngine = IncidentResponseEngine(
        deviceId = deviceSettingsRepository.deviceProfile.value.deviceId,
        ownerId = deviceSettingsRepository.deviceProfile.value.ownerEmail,
        incidentDao = database.incidentDao(),
        theftReportDao = database.theftReportDao(),
        stateManager = securityStateManager
    )

    // Unified Security Brain Core Architecture
    val gpsGateway = AndroidGpsLocationGateway(locationEngine, context)
    val sensorGateway = AndroidSensorTelemetryGateway(sensorEngine)
    val cloudSyncGateway = AndroidCloudSyncGateway(
        eventQueue = securityEventQueue,
        securityEventDao = database.securityEventDao(),
        incidentDao = database.incidentDao()
    )
    val alertGateway = AndroidNotificationAlertGateway(alertManager)
    val evidenceGateway = AndroidEvidenceStorageGateway(offlineVault)

    val eventCorrelationEngine = EventCorrelationEngine(
        fingerprintDao = database.eventFingerprintDao()
    )

    val securityBrain = SecurityBrain(
        device = OntologyDevice(
            deviceId = deviceSettingsRepository.deviceProfile.value.deviceId,
            ownerId = deviceSettingsRepository.deviceProfile.value.ownerEmail,
            name = deviceSettingsRepository.deviceProfile.value.deviceName,
            model = android.os.Build.MODEL ?: "Android Device",
            osVersion = "Android ${android.os.Build.VERSION.RELEASE}"
        ),
        correlationEngine = eventCorrelationEngine,
        owner = OntologyOwner(
            ownerId = deviceSettingsRepository.deviceProfile.value.ownerEmail,
            email = deviceSettingsRepository.deviceProfile.value.ownerEmail,
            displayName = deviceSettingsRepository.deviceProfile.value.deviceName,
            enrolledDeviceIds = listOf(deviceSettingsRepository.deviceProfile.value.deviceId)
        ),
        gpsGateway = gpsGateway,
        sensorGateway = sensorGateway,
        cloudSyncGateway = cloudSyncGateway,
        alertGateway = alertGateway,
        evidenceGateway = evidenceGateway,
        behavioralLearningEngine = com.example.core.brain.baseline.BehavioralLearningEngine(
            persistenceVault = offlineVault
        )
    )

    val commandProcessor = SecurityCommandProcessor(
        expectedDeviceId = deviceSettingsRepository.deviceProfile.value.deviceId,
        lockManager = deviceLockManager,
        stateManager = securityStateManager,
        commandDao = database.securityCommandDao(),
        alarmManager = lostPhoneAlarmManager,
        securityBrain = securityBrain,
        expectedDeviceIdProvider = {
            DeviceSettingsRepository.getDeviceId(context)
                .ifBlank { deviceSettingsRepository.deviceProfile.value.deviceId }
        },
        expectedOwnerProvider = { authRepository.currentUser?.uid },
        persistTrackingState = { active, stateName ->
            DeviceSettingsRepository.setStolenTrackingActiveCommitted(context, active, stateName)
        },
        startRecoveryService = { intervalMillis ->
            val savedContact = DeviceSettingsRepository.getSavedRecoveryContact(context)
                .takeIf { it.isNotBlank() }
            com.example.domain.service.RecoveryMonitoringService.startService(
                context = context,
                contactNumber = savedContact,
                intervalMillis = intervalMillis
            )
        },
        stopRecoveryService = {
            com.example.domain.service.RecoveryMonitoringService.stopService(
                context = context,
                preservedStateName = com.example.core.model.SecurityState.RECOVERED.name
            )
        },
        monitoringIntervalProvider = { state ->
            adaptiveMonitoringPolicy.determineStrategy(
                state = state,
                batteryPercentage = deviceHealthEngine.healthInfo.value.batteryPercentage,
                isCharging = deviceHealthEngine.healthInfo.value.isCharging
            ).locationIntervalMillis
        },
        onConfigureMonitoring = { intervalMillis, requestFreshFix, enqueueOfflineSync ->
            locationEngine.applyMonitoringInterval(intervalMillis)
            if (requestFreshFix) {
                locationEngine.requestFreshLocationFix()
            }
            if (enqueueOfflineSync) {
                com.example.domain.sync.LocationSyncWorker.enqueue(context)
            }
        },
        onServiceActivationDeferred = { targetState, reason ->
            alertManager.dispatchAlert(
                title = "${targetState.name} Tracking Deferred",
                body = reason,
                isCritical = true
            )
            securityRepository.recordEvent(
                com.example.core.model.SecurityEvent(
                    id = java.util.UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    severity = com.example.core.model.EventSeverity.WARNING,
                    title = "Service de suivi différé (${targetState.name})",
                    description = reason,
                    source = "SecurityCommandProcessor",
                    riskContribution = 0
                )
            )
        }
    )

    val secureRecoveryBridge = com.example.domain.recovery.SecureRecoveryBridge(
        context = context,
        deviceId = deviceSettingsRepository.deviceProfile.value.deviceId,
        commandProcessor = commandProcessor,
        commandDao = database.securityCommandDao(),
        eventQueue = securityEventQueue,
        locationDao = database.locationDao(),
        authenticatedOwnerUidProvider = { authRepository.currentUser?.uid },
        activeDeviceIdProvider = {
            DeviceSettingsRepository.getDeviceId(context)
                .ifBlank { deviceSettingsRepository.deviceProfile.value.deviceId }
        }
    )

    init {
        val isPaused = deviceSettingsRepository.isLearningPaused.value
        behaviorLearningManager.setLearningPaused(isPaused)
        if (isPaused) {
            securityBrain.pauseBaselineLearning()
        } else {
            securityBrain.resumeBaselineLearning()
        }
    }
}
