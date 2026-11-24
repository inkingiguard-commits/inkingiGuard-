package com.example.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.core.brain.model.EventCategory
import com.example.core.brain.model.NormalizedSecurityEvent
import com.example.core.brain.model.OntologyIncident
import com.example.core.brain.model.PolicyDecision
import com.example.core.brain.risk.ConfigurableRiskScoringEngine
import com.example.core.model.ConfidenceLevel
import com.example.core.model.EventSeverity
import com.example.core.model.Incident
import com.example.core.model.RiskAssessment
import com.example.core.model.SecurityEvent
import com.example.core.model.SecurityState
import com.example.di.InkingiContainer
import com.example.data.repository.DeviceSettingsRepository
import com.example.domain.location.LocationBreadcrumbPolicy
import com.example.domain.management.DeviceLockManager
import com.example.domain.service.RecoveryMonitoringService
import com.example.domain.sync.LocationSyncWorker
import com.example.domain.sync.SecurityEventSyncWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class InkingiViewModel(
    private val container: InkingiContainer
) : ViewModel() {

    private val initialDayStartMillis = dayStartMillis(System.currentTimeMillis())
    private val selectedLocalDateFlow = MutableStateFlow(initialDayStartMillis)

    private val _uiState = MutableStateFlow(
        InkingiUiState(
            recoveryContactPhone = container.deviceSettingsRepository.recoveryContactPhone.value,
            lockScreenRecoveryMessage = container.deviceSettingsRepository.lockScreenRecoveryMessage.value,
            isLearningPaused = container.deviceSettingsRepository.isLearningPaused.value,
            selectedHistoryDateMillis = initialDayStartMillis,
            remoteSelectedDateMillis = initialDayStartMillis,
            isFirebaseAvailable = container.authRepository.isFirebaseAvailable,
            isPinConfigured = container.deviceSettingsRepository.isPinConfigured.value,
            isVaultKeystoreHealthy = container.deviceSettingsRepository.vaultHealth.value.isKeystoreHealthy,
            vaultErrorMessage = container.deviceSettingsRepository.vaultHealth.value.lastError,
            pinLockoutRemainingMillis = container.deviceSettingsRepository.vaultHealth.value.lockoutRemainingMillis
        )
    )
    val uiState: StateFlow<InkingiUiState> = _uiState.asStateFlow()

    private var failedAuthCount = 0
    private var telemetryJob: Job? = null
    private var remoteDevicesJob: Job? = null
    private var remoteTelemetryJob: Job? = null
    private var remoteBreadcrumbsJob: Job? = null
    private var lastRecordedZone: String? = null
    private var lastRecordedCharging: Boolean? = null
    private var lastTelemetryObservationTimestamp: Long = 0L
    private var lastAutonomousLockIncidentId: String? = null

    init {
        // Start sensor and location telemetry
        container.sensorEngine.startListening()
        val isPersistedStolen = DeviceSettingsRepository.isStolenTrackingActive(container.context)
        val persistedStateName = DeviceSettingsRepository.getPersistedSecurityStateName(container.context)
        val initialLocationInterval = if (isPersistedStolen) {
            RecoveryMonitoringService.DEFAULT_THEFT_INTERVAL_MILLIS
        } else {
            300_000L
        }
        val startedInitialLocation = container.locationEngine.startLocationUpdates(initialLocationInterval)
        _uiState.update {
            it.copy(
                hasFineLocationPermission = container.locationEngine.hasFineLocationPermission(),
                hasCoarseLocationPermission = container.locationEngine.hasLocationPermission(),
                hasBackgroundLocationPermission = container.locationEngine.hasBackgroundLocationPermission(),
                isLocationUpdatesActive = startedInitialLocation && container.locationEngine.isTracking,
                isGpsAvailable = container.locationEngine.isGpsAvailable.value
            )
        }
        if (isPersistedStolen || persistedStateName == SecurityState.RECOVERED.name) {
            restorePersistedStolenTracking()
        }

        viewModelScope.launch(Dispatchers.IO) {
            container.securityRepository.seedDefaultDataIfEmpty()
            withContext(Dispatchers.Main) {
                refreshCapabilities()
            }
        }

        // Collect real Firebase Authentication state
        viewModelScope.launch {
            container.authRepository.authState.collectLatest { user ->
                _uiState.update { current ->
                    current.copy(
                        isAuthenticated = (user != null),
                        authenticatedUserEmail = user?.email,
                        authenticatedUserId = user?.uid,
                        isEmailVerified = user?.isEmailVerified ?: false,
                        isFirebaseAvailable = container.authRepository.isFirebaseAvailable
                    )
                }
                if (user != null) {
                    val prefs = container.context.getSharedPreferences("inkingi_prefs", android.content.Context.MODE_PRIVATE)
                    prefs.edit().putString("owner_uid", user.uid).apply()
                    user.email?.let { container.deviceSettingsRepository.updateOwnerEmailIfBlank(it) }
                    container.secureRecoveryBridge.startRemoteCommandListener()
                    evaluateSuperAdminAuthorization()
                    LocationSyncWorker.enqueue(container.context)
                    SecurityEventSyncWorker.enqueue(container.context)
                    scheduleTelemetryEvaluation(debounceMs = 0L)
                    remoteDevicesJob?.cancel()
                    remoteDevicesJob = viewModelScope.launch {
                        container.secureRecoveryBridge.observeOwnerDevices().collectLatest { devices ->
                            _uiState.update { it.copy(ownerRemoteDevices = devices) }
                        }
                    }
                } else {
                    container.secureRecoveryBridge.stopRemoteCommandListener()
                    remoteDevicesJob?.cancel()
                    stopObservingRemoteDevice()
                    container.superAdminRepository.clearAdminStatus()
                    _uiState.update {
                        it.copy(
                            isSuperAdmin = false,
                            isCheckingSuperAdmin = false,
                            adminVerificationMessage = null,
                            superAdminUserCount = null,
                            ownerRemoteDevices = emptyList(),
                            remoteTrackedTelemetry = null,
                            remoteDeviceBreadcrumbs = emptyList(),
                            selectedRemoteDeviceId = null
                        )
                    }
                }
            }
        }

        viewModelScope.launch {
            container.deviceSettingsRepository.deviceProfile.collectLatest { profile ->
                _uiState.update { it.copy(deviceProfile = profile) }
            }
        }

        viewModelScope.launch {
            container.deviceSettingsRepository.isPinConfigured.collectLatest { configured ->
                _uiState.update { it.copy(isPinConfigured = configured) }
            }
        }

        viewModelScope.launch {
            container.deviceSettingsRepository.vaultHealth.collectLatest { health ->
                _uiState.update {
                    it.copy(
                        isPinConfigured = health.isPinConfigured,
                        isVaultKeystoreHealthy = health.isKeystoreHealthy,
                        vaultErrorMessage = health.lastError,
                        pinLockoutRemainingMillis = health.lockoutRemainingMillis
                    )
                }
            }
        }

        viewModelScope.launch {
            container.deviceSettingsRepository.currentLanguage.collectLatest { lang ->
                _uiState.update { it.copy(currentLanguage = lang) }
                val cal = java.util.Calendar.getInstance()
                val currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                val zoneName = _uiState.value.location?.closestZoneName
                val currentRole = _uiState.value.currentUserRole
                val (_, statusMsg) = container.behaviorLearningManager.isWithinBaseline(currentHour, zoneName, currentRole, lang)
                _uiState.update { it.copy(baselineStatusMessage = statusMsg) }
            }
        }

        // UNIFIED SECURITY BRAIN: Authoritative source for SecurityState
        viewModelScope.launch {
            container.securityBrain.currentState.collectLatest { state ->
                val prev = _uiState.value.securityState
                _uiState.update { it.copy(securityState = state) }

                // Autonomous Emergency Lock: If transitioning to SUSPECTED_LOST from multi-signal convergence, attempt hardware lock
                if (state == SecurityState.SUSPECTED_LOST && prev != SecurityState.SUSPECTED_LOST) {
                    triggerAutonomousEmergencyLock()
                }

                // Wait for any in-flight remote command transition to record its service outcome before checking
                container.commandProcessor.isRemoteTransitionInProgress.first { !it }
                val remoteServiceOutcome = container.commandProcessor.consumeRemoteHandledServiceState(state)
                if (remoteServiceOutcome != null) {
                    if (state == SecurityState.NORMAL || state == SecurityState.RECOVERED) {
                        lastAutonomousLockIncidentId = null
                    }
                    _uiState.update { it.copy(isForegroundServiceActive = remoteServiceOutcome) }
                    return@collectLatest
                }

                // Manage Recovery Foreground Service lifecycle safely for local/autonomous transitions
                if (state == SecurityState.DANGER ||
                    state == SecurityState.SUSPECTED_LOST ||
                    state == SecurityState.STOLEN_MODE ||
                    state == SecurityState.RECOVERY_MODE
                ) {
                    try {
                        val currentBattery = _uiState.value.deviceHealth.batteryPercentage
                        val isCharging = _uiState.value.deviceHealth.isCharging
                        val strategy = container.adaptiveMonitoringPolicy.determineStrategy(
                            state = state,
                            batteryPercentage = currentBattery,
                            isCharging = isCharging
                        )
                        container.locationEngine.applyMonitoringInterval(strategy.locationIntervalMillis)
                        val savedContact = container.deviceSettingsRepository.recoveryContactPhone.value
                            .takeIf { it.isNotBlank() }
                        val startRes = RecoveryMonitoringService.startServiceWithResult(
                            context = container.context,
                            contactNumber = savedContact,
                            intervalMillis = strategy.locationIntervalMillis
                        )
                        when (startRes) {
                            is com.example.domain.service.ServiceStartResult.Started -> {
                                _uiState.update { it.copy(isForegroundServiceActive = true, lastForegroundServiceError = null) }
                            }
                            is com.example.domain.service.ServiceStartResult.BlockedPermission -> {
                                _uiState.update { it.copy(isForegroundServiceActive = false, lastForegroundServiceError = startRes.reason) }
                            }
                            is com.example.domain.service.ServiceStartResult.BlockedBackgroundRestriction -> {
                                _uiState.update { it.copy(isForegroundServiceActive = false, lastForegroundServiceError = startRes.reason) }
                            }
                            is com.example.domain.service.ServiceStartResult.Failed -> {
                                _uiState.update { it.copy(isForegroundServiceActive = false, lastForegroundServiceError = startRes.reason) }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("InkingiViewModel", "Could not start RecoveryMonitoringService: ${e.message}", e)
                        _uiState.update {
                            it.copy(
                                isForegroundServiceActive = false,
                                lastForegroundServiceError = e.message ?: "Service error"
                            )
                        }
                    }
                } else if (prev != state && (state == SecurityState.NORMAL || state == SecurityState.RECOVERED)) {
                    lastAutonomousLockIncidentId = null
                    val currentBattery = _uiState.value.deviceHealth.batteryPercentage
                    val isCharging = _uiState.value.deviceHealth.isCharging
                    val strategy = container.adaptiveMonitoringPolicy.determineStrategy(
                        state = state,
                        batteryPercentage = currentBattery,
                        isCharging = isCharging
                    )
                    container.locationEngine.applyMonitoringInterval(strategy.locationIntervalMillis)
                    try {
                        val preserved = if (state == SecurityState.RECOVERED) SecurityState.RECOVERED.name else null
                        RecoveryMonitoringService.stopService(container.context, preserved)
                        _uiState.update { it.copy(isForegroundServiceActive = false, lastForegroundServiceError = null) }
                    } catch (e: Exception) {
                        android.util.Log.e("InkingiViewModel", "Could not stop RecoveryMonitoringService: ${e.message}", e)
                    }
                }
            }
        }

        // Truthfully mirror any runtime foreground service stop or restriction error
        viewModelScope.launch {
            RecoveryMonitoringService.lastServiceError.collectLatest { err ->
                val unexpectedStop = RecoveryMonitoringService.runtimeUnexpectedStop.value
                _uiState.update { current ->
                    current.copy(
                        isForegroundServiceActive = if (unexpectedStop && err != null) false else current.isForegroundServiceActive,
                        lastForegroundServiceError = err
                    )
                }
            }
        }

        // Reflect remote command foreground service start/stop status truthfully in UI state
        viewModelScope.launch {
            container.commandProcessor.recoveryServiceActive.collectLatest { remoteActive ->
                if (remoteActive != null) {
                    _uiState.update {
                        it.copy(
                            isForegroundServiceActive = remoteActive,
                            lastForegroundServiceError = if (remoteActive) null else RecoveryMonitoringService.lastServiceError.value
                        )
                    }
                }
            }
        }

        // UNIFIED SECURITY BRAIN: Authoritative source for Decayed Risk Evaluation
        viewModelScope.launch {
            container.securityBrain.currentRiskEvaluation.collectLatest { evaluation ->
                val assessment = container.securityBrain.riskScoringEngine.toRiskAssessment(evaluation)
                _uiState.update { current ->
                    current.copy(
                        riskAssessment = assessment,
                        confidenceLevel = when {
                            evaluation.evidenceItems.size >= 3 || evaluation.totalScore >= 70 -> ConfidenceLevel.HIGH_CONFIDENCE
                            evaluation.evidenceItems.isNotEmpty() || evaluation.totalScore >= 30 -> ConfidenceLevel.MEDIUM_CONFIDENCE
                            else -> ConfidenceLevel.LOW_CONFIDENCE
                        }
                    )
                }
            }
        }

        // UNIFIED SECURITY BRAIN: Authoritative source for active Incident
        viewModelScope.launch {
            container.securityBrain.activeIncident.collectLatest { ontologyInc ->
                val domainIncident = ontologyInc?.let { inc ->
                    Incident(
                        incidentId = inc.incidentId,
                        deviceId = inc.deviceId,
                        ownerId = inc.ownerId,
                        title = inc.title,
                        status = inc.status,
                        severity = inc.severity,
                        riskScore = inc.riskScore,
                        triggeringFactors = inc.primaryFactors,
                        createdTimestamp = inc.createdTimestamp,
                        resolvedTimestamp = inc.resolvedTimestamp
                    )
                }
                _uiState.update { it.copy(activeIncident = domainIncident) }
            }
        }

        // UNIFIED SECURITY BRAIN: Authoritative source for Threat State
        viewModelScope.launch {
            container.securityBrain.currentThreatState.collectLatest { threat ->
                _uiState.update { it.copy(threatState = threat) }
            }
        }

        // UNIFIED SECURITY BRAIN: Authoritative source for Contributing Signals
        viewModelScope.launch {
            container.securityBrain.contributingSignals.collectLatest { signals ->
                _uiState.update { it.copy(correlatedSignals = signals) }
            }
        }

        // UNIFIED SECURITY BRAIN: Authoritative source for Behavioral Learning baseline profile
        viewModelScope.launch {
            container.securityBrain.baselineProfile.collectLatest { profile ->
                _uiState.update { it.copy(baselineProfile = profile) }
            }
        }

        // Hook alarm manager timeout callback to automatically update state when timeout elapses
        container.lostPhoneAlarmManager.onAlarmStopped = {
            _uiState.update { it.copy(isFindMyPhoneAlarmActive = false) }
        }

        viewModelScope.launch {
            container.deviceSettingsRepository.currentUserRole.collectLatest { role ->
                _uiState.update { it.copy(currentUserRole = role) }
                scheduleTelemetryEvaluation(debounceMs = 0L)
            }
        }

        viewModelScope.launch {
            container.deviceSettingsRepository.isLearningPaused.collectLatest { paused ->
                _uiState.update { it.copy(isLearningPaused = paused) }
            }
        }

        viewModelScope.launch {
            container.deviceSettingsRepository.trustedPersons.collectLatest { persons ->
                _uiState.update { it.copy(trustedPersons = persons) }
            }
        }

        viewModelScope.launch {
            container.deviceSettingsRepository.recoveryContactPhone.collectLatest { phone ->
                _uiState.update { it.copy(recoveryContactPhone = phone) }
            }
        }

        viewModelScope.launch {
            container.deviceSettingsRepository.lockScreenRecoveryMessage.collectLatest { msg ->
                _uiState.update { it.copy(lockScreenRecoveryMessage = msg) }
            }
        }

        viewModelScope.launch {
            val initialZone = _uiState.value.location?.closestZoneName
            val currentRole = _uiState.value.currentUserRole
            try {
                container.behaviorLearningManager.recordAppOpenEvent(initialZone, currentRole)
                container.behaviorLearningManager.loadInitialBaseline()
            } catch (e: IllegalStateException) {
                if (container.database.isOpen && e.message?.contains("closed", ignoreCase = true) != true) throw e
            }
        }

        viewModelScope.launch {
            var lastRecordedBaselineState: Boolean? = null
            container.behaviorLearningManager.baselineFlow.collectLatest { baseline ->
                val cal = java.util.Calendar.getInstance()
                val currentHour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                val zoneName = _uiState.value.location?.closestZoneName
                val currentRole = _uiState.value.currentUserRole
                val lang = _uiState.value.currentLanguage
                val (within, statusMsg) = container.behaviorLearningManager.isWithinBaseline(currentHour, zoneName, currentRole, lang)

                // Record genuine ledger event if status changed
                if (lastRecordedBaselineState != within) {
                    lastRecordedBaselineState = within
                    container.securityRepository.recordEvent(
                        SecurityEvent(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            severity = if (within) EventSeverity.INFO else EventSeverity.WARNING,
                            title = if (within) "Dans baseline" else "Hors baseline",
                            description = statusMsg,
                            source = "BehaviorLearning",
                            riskContribution = if (within) 0 else 20
                        )
                    )
                }

                _uiState.update { current ->
                    current.copy(
                        behaviorBaseline = baseline,
                        learnedDays = baseline.learningDays,
                        isWithinBaseline = within,
                        baselineStatusMessage = statusMsg,
                        isLearningPaused = baseline.isPaused || container.deviceSettingsRepository.isLearningPaused.value
                    )
                }
                scheduleTelemetryEvaluation(debounceMs = 100L)
            }
        }

        viewModelScope.launch {
            container.database.arnaqueAppriseDao().getTotalBlockedCount()
                .catch { e -> if (e is IllegalStateException && (!container.database.isOpen || e.message?.contains("closed", ignoreCase = true) == true)) Unit else throw e }
                .collectLatest { count ->
                    _uiState.update { it.copy(totalBlockedScams = count) }
                }
        }

        viewModelScope.launch {
            container.database.arnaqueAppriseDao().getLearnedPatternsCount()
                .catch { e -> if (e is IllegalStateException && (!container.database.isOpen || e.message?.contains("closed", ignoreCase = true) == true)) Unit else throw e }
                .collectLatest { count ->
                    _uiState.update { it.copy(totalLearnedScamPatterns = count) }
                }
        }

        viewModelScope.launch {
            container.sensorEngine.meshSnapshot.collectLatest { mesh ->
                _uiState.update { it.copy(sensorMesh = mesh) }
                // Re-evaluate risk immediately if an active kinetic anomaly occurs
                if (mesh.lastMotionEvent != null || mesh.currentAccelerationMagnitude > 18.0f) {
                    scheduleTelemetryEvaluation(debounceMs = 0L)
                }
            }
        }

        viewModelScope.launch {
            container.locationEngine.lastLocation.collectLatest { loc ->
                _uiState.update { it.copy(location = loc) }
                scheduleTelemetryEvaluation()
            }
        }

        viewModelScope.launch {
            container.locationEngine.isGpsAvailable.collectLatest { available ->
                _uiState.update { it.copy(isGpsAvailable = available) }
            }
        }

        viewModelScope.launch {
            container.database.locationDao().getLocationHistory(50)
                .catch { e -> if (e is IllegalStateException && (!container.database.isOpen || e.message?.contains("closed", ignoreCase = true) == true)) Unit else throw e }
                .collectLatest { history ->
                    _uiState.update { it.copy(recentLocationHistory = history) }
                }
        }

        viewModelScope.launch {
            selectedLocalDateFlow.flatMapLatest { dayStart ->
                val (start, end) = dayBoundsMillis(dayStart)
                container.database.locationDao().getLocationsInRange(start, end)
            }
                .catch { e -> if (e is IllegalStateException && (!container.database.isOpen || e.message?.contains("closed", ignoreCase = true) == true)) Unit else throw e }
                .collectLatest { filtered ->
                    _uiState.update { it.copy(dateFilteredLocalHistory = filtered) }
                }
        }

        viewModelScope.launch {
            container.database.locationDao().getUnsyncedCount()
                .catch { e -> if (e is IllegalStateException && (!container.database.isOpen || e.message?.contains("closed", ignoreCase = true) == true)) Unit else throw e }
                .collectLatest { unsynced ->
                    _uiState.update { it.copy(unsyncedLocationCount = unsynced) }
                }
        }

        viewModelScope.launch {
            container.secureRecoveryBridge.syncStatusState.collectLatest { status ->
                _uiState.update { it.copy(locationSyncStatus = status) }
            }
        }

        viewModelScope.launch {
            container.secureRecoveryBridge.lastSuccessfulSyncAt.collectLatest { ts ->
                _uiState.update { it.copy(lastSuccessfulLocationSyncAt = ts) }
            }
        }

        // Start listening for authenticated owner recovery commands
        try {
            container.secureRecoveryBridge.startRemoteCommandListener()
        } catch (_: Exception) {}

        viewModelScope.launch {
            container.securityRepository.activeTrustedZones
                .catch { e ->
                    if (e is IllegalStateException && (!container.database.isOpen || e.message?.contains("closed", ignoreCase = true) == true)) Unit
                    else throw e
                }
                .collectLatest { zones ->
                    _uiState.update { it.copy(trustedZones = zones) }
                    container.locationEngine.updateTrustedZones(zones)
                    scheduleTelemetryEvaluation()
                }
        }

        viewModelScope.launch {
            container.deviceHealthEngine.healthInfo.collectLatest { health ->
                _uiState.update { it.copy(deviceHealth = health) }
                scheduleTelemetryEvaluation()
            }
        }

        viewModelScope.launch {
            container.networkEngine.networkInfo.collectLatest { net ->
                _uiState.update { it.copy(networkInfo = net) }
                scheduleTelemetryEvaluation()
            }
        }

        viewModelScope.launch {
            container.simEngine.simInfo.collectLatest { sim ->
                _uiState.update { it.copy(simInfo = sim) }
                scheduleTelemetryEvaluation()
            }
        }

        viewModelScope.launch {
            container.securityRepository.allSecurityEvents
                .catch { e ->
                    if (e is IllegalStateException && (!container.database.isOpen || e.message?.contains("closed", ignoreCase = true) == true)) Unit
                    else throw e
                }
                .collectLatest { events ->
                    _uiState.update { it.copy(recentEvents = events) }
                }
        }

        // Phase 2 incident response history streams (activeIncident is owned by SecurityBrain)

        viewModelScope.launch {
            container.incidentResponseEngine.allIncidents
                .catch { e ->
                    if (e is IllegalStateException && (!container.database.isOpen || e.message?.contains("closed", ignoreCase = true) == true)) Unit
                    else throw e
                }
                .collectLatest { incidents ->
                    _uiState.update { it.copy(allIncidents = incidents) }
                }
        }

        viewModelScope.launch {
            container.incidentResponseEngine.latestTheftReport
                .catch { e ->
                    if (e is IllegalStateException && (!container.database.isOpen || e.message?.contains("closed", ignoreCase = true) == true)) Unit
                    else throw e
                }
                .collectLatest { report ->
                    _uiState.update { it.copy(latestTheftReport = report) }
                }
        }

        viewModelScope.launch {
            container.securityEventQueue.pendingCount
                .catch { e ->
                    if (e is IllegalStateException && (!container.database.isOpen || e.message?.contains("closed", ignoreCase = true) == true)) Unit
                    else throw e
                }
                .collectLatest { count ->
                    _uiState.update { it.copy(pendingQueueCount = count) }
                }
        }

        viewModelScope.launch {
            container.securityEventQueue.queueSummary.collectLatest { summary ->
                _uiState.update { it.copy(queueStatusSummary = summary) }
            }
        }

        viewModelScope.launch {
            container.alertManager.dispatchStatus.collectLatest { status ->
                _uiState.update { it.copy(alertDispatchStatus = status) }
            }
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                container.securityEventQueue.refreshSummary()
            } catch (e: IllegalStateException) {
                if (container.database.isOpen && e.message?.contains("closed", ignoreCase = true) != true) throw e
            }
        }

        viewModelScope.launch {
            container.tamperEngine.tamperEvents.collectLatest { tampers ->
                _uiState.update { it.copy(tamperAlerts = tampers) }
            }
        }
    }

    fun refreshCapabilities() {
        val caps = container.deviceLockManager.getCapabilities()
        _uiState.update { it.copy(deviceCapabilities = caps) }
        onLocationPermissionsUpdated()
    }

    /**
     * Re-evaluates runtime location permissions (including after the user grants them in-app
     * or returns from Android Settings) and restarts GPS updates if permission just became available.
     */
    fun onLocationPermissionsUpdated() {
        val hasFine = container.locationEngine.hasFineLocationPermission()
        val hasAny = container.locationEngine.hasLocationPermission()
        val hasBackground = container.locationEngine.hasBackgroundLocationPermission()
        val wasTracking = container.locationEngine.isTracking

        val trackingActive = if (hasAny) {
            container.locationEngine.ensureLocationUpdatesStarted()
        } else {
            container.locationEngine.ensureLocationUpdatesStarted()
            false
        }

        _uiState.update { current ->
            current.copy(
                hasFineLocationPermission = hasFine,
                hasCoarseLocationPermission = hasAny,
                hasBackgroundLocationPermission = hasBackground,
                isLocationUpdatesActive = trackingActive && container.locationEngine.isTracking,
                isGpsAvailable = container.locationEngine.isGpsAvailable.value
            )
        }

        if (!wasTracking && trackingActive) {
            scheduleTelemetryEvaluation(debounceMs = 0L)
        }

        // Retry deferred foreground service start if security state demands active beacon & permission is now granted
        val state = _uiState.value.securityState
        val needsBeacon = state == SecurityState.DANGER ||
            state == SecurityState.SUSPECTED_LOST ||
            state == SecurityState.STOLEN_MODE ||
            state == SecurityState.OWNER_CONFIRMED_STOLEN ||
            state == SecurityState.RECOVERY_MODE
        if (needsBeacon && hasAny && !_uiState.value.isForegroundServiceActive) {
            val strategy = container.adaptiveMonitoringPolicy.determineStrategy(
                state = state,
                batteryPercentage = _uiState.value.deviceHealth.batteryPercentage,
                isCharging = _uiState.value.deviceHealth.isCharging
            )
            val savedContact = container.deviceSettingsRepository.recoveryContactPhone.value
                .takeIf { it.isNotBlank() }
            val startRes = RecoveryMonitoringService.startServiceWithResult(
                context = container.context,
                contactNumber = savedContact,
                intervalMillis = strategy.locationIntervalMillis
            )
            when (startRes) {
                is com.example.domain.service.ServiceStartResult.Started -> {
                    _uiState.update { it.copy(isForegroundServiceActive = true, lastForegroundServiceError = null) }
                }
                is com.example.domain.service.ServiceStartResult.BlockedPermission -> {
                    _uiState.update { it.copy(isForegroundServiceActive = false, lastForegroundServiceError = startRes.reason) }
                }
                is com.example.domain.service.ServiceStartResult.BlockedBackgroundRestriction -> {
                    _uiState.update { it.copy(isForegroundServiceActive = false, lastForegroundServiceError = startRes.reason) }
                }
                is com.example.domain.service.ServiceStartResult.Failed -> {
                    _uiState.update { it.copy(isForegroundServiceActive = false, lastForegroundServiceError = startRes.reason) }
                }
            }
        }
    }

    /**
     * Explicit user-triggered retry for GPS signal acquisition.
     */
    fun retryGpsAcquisition() {
        onLocationPermissionsUpdated()
        if (container.locationEngine.hasLocationPermission()) {
            container.locationEngine.requestFreshLocationFix()
            scheduleTelemetryEvaluation(debounceMs = 150L)
        }
    }

    fun scheduleTelemetryEvaluation(debounceMs: Long = 300L) {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch(Dispatchers.Default) {
            if (debounceMs > 0) {
                delay(debounceMs)
            }
            evaluateSystemTelemetryInternal()
        }
    }

    private suspend fun evaluateSystemTelemetryInternal() {
        val sim = container.simEngine.simInfo.value
        val loc = container.locationEngine.lastLocation.value
        val motion = container.sensorEngine.meshSnapshot.value.lastMotionEvent
        val health = container.deviceHealthEngine.healthInfo.value
        val net = container.networkEngine.networkInfo.value
        val mesh = container.sensorEngine.meshSnapshot.value
        val deviceProfile = _uiState.value.deviceProfile
        val activeRole = _uiState.value.currentUserRole
        val now = System.currentTimeMillis()
        val isPaused = container.deviceSettingsRepository.isLearningPaused.value

        val isSimChanged = sim.hasSimChanged
        val isOutsideTrustedZone = loc?.let { !it.isInsideTrustedZone } ?: false
        val isUnusualMotion = motion != null || mesh.currentAccelerationMagnitude > 18.0f
        val isRebootAnomaly = health.isRebootAnomalyDetected
        val isNetworkDropped = !net.isConnected
        val isSensorAnomaly = mesh.currentAccelerationMagnitude > 18.0f

        // 1. Evaluate behavioral deviations via BehavioralLearningEngine (non-overwriting) and record if active
        val behavioralObservation = com.example.core.brain.baseline.BehavioralObservation(
            simCarrier = if (sim.isSimPresent) sim.carrierName else null,
            networkType = net.networkType,
            isNetworkConnected = net.isConnected,
            isRebootAnomaly = health.isRebootAnomalyDetected,
            failedAuthAttempts = failedAuthCount,
            accelerationMagnitude = mesh.currentAccelerationMagnitude,
            latitude = loc?.latitude,
            longitude = loc?.longitude,
            isInsideTrustedZone = loc?.isInsideTrustedZone,
            isCharging = health.isCharging,
            zoneName = loc?.closestZoneName,
            role = activeRole,
            timestamp = now
        )
        val engineAnomalies = container.securityBrain.evaluateAndRecordBehavioralObservation(
            observation = behavioralObservation,
            now = now
        )

        // 2. Record genuine device activity transitions into Room-backed BehaviorLearningManager (honors pause state)
        if (!isPaused) {
            try {
                val currentZone = loc?.closestZoneName
                val currentCharging = health.isCharging

                val isZoneChange = currentZone != null && currentZone != lastRecordedZone
                val isChargingChange = lastRecordedCharging != null && currentCharging != lastRecordedCharging
                val isSignificantInterval = (now - lastTelemetryObservationTimestamp) >= 3600_000L // 1 hour

                if (isZoneChange || isChargingChange || (isSignificantInterval && currentZone != null)) {
                    lastRecordedZone = currentZone
                    lastRecordedCharging = currentCharging
                    lastTelemetryObservationTimestamp = now

                    val observationAction = when {
                        isChargingChange && currentCharging -> "CHARGING_CONNECTED"
                        isZoneChange -> "ZONE_TRANSITION"
                        else -> "LOCATION_ACTIVITY"
                    }
                    container.behaviorLearningManager.recordObservation(
                        zoneName = currentZone,
                        role = activeRole,
                        action = observationAction
                    )
                }
            } catch (_: Exception) {}
        }

        // 3. Evaluate Room-backed learned hours/zones baseline
        val currentHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val (withinRoomBaseline, baselineStatusMsg) = container.behaviorLearningManager.isWithinBaseline(
            currentHour = currentHour,
            currentZoneName = loc?.closestZoneName,
            role = activeRole,
            lang = _uiState.value.currentLanguage
        )

        val isBehavioralDeviation = activeRole == com.example.core.model.UserRole.OWNER &&
            (!withinRoomBaseline || engineAnomalies.isNotEmpty())
        val deviationDesc = when {
            !withinRoomBaseline -> baselineStatusMsg
            engineAnomalies.isNotEmpty() -> engineAnomalies.first().explanation
            else -> null
        }

        val signals = ConfigurableRiskScoringEngine.RawSignals(
            isSimChanged = isSimChanged,
            simChangeTimestamp = if (isSimChanged) now else 0L,
            failedAuthAttempts = failedAuthCount,
            lastAuthFailureTimestamp = if (failedAuthCount > 0) now else 0L,
            isOutsideTrustedZone = isOutsideTrustedZone,
            zoneExitTimestamp = if (isOutsideTrustedZone) now else 0L,
            isUnusualMovementDetected = isUnusualMotion,
            movementTimestamp = if (isUnusualMotion) now else 0L,
            isUnexpectedRestartDetected = isRebootAnomaly,
            restartTimestamp = if (isRebootAnomaly) now else 0L,
            hasCorrelatedSensorAnomalies = isSensorAnomaly,
            sensorAnomalyTimestamp = if (isSensorAnomaly) now else 0L,
            hasNetworkAnomaly = isNetworkDropped,
            networkAnomalyTimestamp = if (isNetworkDropped) now else 0L,
            isBehavioralDeviation = isBehavioralDeviation,
            behavioralDeviationTimestamp = if (isBehavioralDeviation) now else 0L,
            behavioralDeviationDescription = deviationDesc,
            userRole = activeRole
        )

        data class TelemetryDescriptor(
            val category: EventCategory,
            val title: String,
            val details: String,
            val severity: EventSeverity,
            val baseRiskImpact: Int
        )

        // Select normalized event based on primary telemetry signal
        val descriptor = when {
            isSimChanged -> TelemetryDescriptor(
                EventCategory.SIM_TELEPHONY,
                "SIM Module Alteration",
                "SIM state: ${sim.simState}, Carrier: ${sim.carrierName}",
                EventSeverity.CRITICAL,
                30
            )
            failedAuthCount >= 3 -> TelemetryDescriptor(
                EventCategory.AUTHENTICATION_ACCESS,
                "Multiple Master Authentication Failures",
                "$failedAuthCount consecutive failed PIN attempts",
                EventSeverity.WARNING,
                40
            )
            isOutsideTrustedZone -> TelemetryDescriptor(
                EventCategory.GEOLOCATION_DISPLACEMENT,
                "Perimeter Boundary Displacement",
                "Coordinates (${loc?.latitude}, ${loc?.longitude}) outside safe zone",
                EventSeverity.WARNING,
                20
            )
            isUnusualMotion -> TelemetryDescriptor(
                EventCategory.KINETIC_MOTION,
                "Kinetic Acceleration Anomaly",
                "Magnitude: ${mesh.currentAccelerationMagnitude} m/s²",
                EventSeverity.WARNING,
                15
            )
            isRebootAnomaly -> TelemetryDescriptor(
                EventCategory.SYSTEM_LIFECYCLE,
                "Unexpected Device Reboot",
                "Reboot anomaly detected during active security posture",
                EventSeverity.WARNING,
                20
            )
            isNetworkDropped -> TelemetryDescriptor(
                EventCategory.NETWORK_CONNECTIVITY,
                "Radio Link Disconnected",
                "Sudden loss of network connectivity",
                EventSeverity.INFO,
                10
            )
            isBehavioralDeviation -> TelemetryDescriptor(
                EventCategory.SYSTEM_LIFECYCLE,
                "Behavioral Baseline Deviation",
                deviationDesc ?: "Observation outside learned usage pattern",
                EventSeverity.WARNING,
                20
            )
            else -> TelemetryDescriptor(
                EventCategory.SYSTEM_LIFECYCLE,
                "Nominal Telemetry Heartbeat",
                "All sensor mesh signals nominal",
                EventSeverity.INFO,
                0
            )
        }

        val normalizedEvent = NormalizedSecurityEvent(
            eventId = UUID.randomUUID().toString(),
            deviceId = deviceProfile.deviceId,
            ownerId = deviceProfile.ownerEmail,
            category = descriptor.category,
            source = "TelemetryEngine",
            severity = descriptor.severity,
            baseRiskImpact = descriptor.baseRiskImpact,
            title = descriptor.title,
            details = descriptor.details,
            timestamp = now
        )

        // 4. SINGLE AUTHORITATIVE RISK & INCIDENT EVALUATION VIA SECURITYBRAIN
        val ingestionOutcome = container.securityBrain.ingestSecurityEvent(
            event = normalizedEvent,
            signals = signals,
            now = now
        )

        val riskEval = ingestionOutcome.updatedRiskEvaluation
        val riskAssessment = container.securityBrain.riskScoringEngine.toRiskAssessment(riskEval)
        val brainState = container.securityBrain.currentState.value
        val threatState = container.securityBrain.currentThreatState.value
        val contributingSignals = container.securityBrain.contributingSignals.value
        val distinctFactorCount = riskEval.evidenceItems.map { it.factorKey }.distinct().size

        // Adaptive monitoring calculation based on unified state and battery
        val strategy = container.adaptiveMonitoringPolicy.determineStrategy(
            state = brainState,
            batteryPercentage = health.batteryPercentage,
            isCharging = health.isCharging
        )

        // Apply adaptive GPS sampling interval to LocationIntelligenceEngine
        container.locationEngine.applyMonitoringInterval(strategy.locationIntervalMillis)

        // Propagate adaptive interval to active recovery service if running
        if (_uiState.value.isForegroundServiceActive) {
            try {
                RecoveryMonitoringService.updateInterval(container.context, strategy.locationIntervalMillis)
            } catch (e: Exception) {
                android.util.Log.e("InkingiViewModel", "Could not update RecoveryMonitoringService interval: ${e.message}", e)
            }
        }

        // Generate AI advisory layer (strictly advisory, deterministic rules remain authoritative)
        val aiAdvisory = container.aiEngine.generateAdvisory(
            riskAssessment = riskAssessment,
            simInfo = sim,
            location = loc,
            motion = motion,
            health = health,
            network = net,
            recentEvents = _uiState.value.recentEvents,
            lang = _uiState.value.currentLanguage
        )

        withContext(Dispatchers.Main) {
            _uiState.update { current ->
                current.copy(
                    riskAssessment = riskAssessment,
                    confidenceLevel = when {
                        distinctFactorCount >= 3 || riskAssessment.score >= 70 -> ConfidenceLevel.HIGH_CONFIDENCE
                        distinctFactorCount >= 1 || riskAssessment.score >= 30 -> ConfidenceLevel.MEDIUM_CONFIDENCE
                        else -> ConfidenceLevel.LOW_CONFIDENCE
                    },
                    threatState = threatState,
                    correlatedSignals = contributingSignals,
                    isWithinBaseline = withinRoomBaseline,
                    baselineStatusMessage = baselineStatusMsg,
                    isLearningPaused = isPaused,
                    aiAdvisory = aiAdvisory,
                    failedAuthAttempts = failedAuthCount,
                    monitoringStrategy = strategy
                )
            }
        }

        // Record event into local room repository if not duplicate suppressed
        val impact = descriptor.baseRiskImpact
        if (!ingestionOutcome.isDuplicateSuppressed && impact > 0) {
            withContext(Dispatchers.IO) {
                container.securityRepository.recordEvent(
                    SecurityEvent(
                        id = normalizedEvent.eventId,
                        timestamp = normalizedEvent.timestamp,
                        severity = normalizedEvent.severity,
                        title = normalizedEvent.title,
                        description = normalizedEvent.details,
                        source = normalizedEvent.source,
                        riskContribution = descriptor.baseRiskImpact
                    )
                )
            }
        }

        // 4. Secure Owner-Scoped Recovery Telemetry Sync
        withContext(Dispatchers.IO) {
            container.secureRecoveryBridge.syncTelemetry(
                state = container.securityBrain.currentState.value,
                location = loc,
                health = health,
                network = net,
                recentEvents = _uiState.value.recentEvents,
                isGpsAvailable = container.locationEngine.isGpsAvailable.value
            )
        }

        // Trigger SIM Change alert with authorized TrustedPerson validation & truthful dispatch state
        if (sim.hasSimChanged) {
            container.alertManager.dispatchSimChangeAlert(
                carrier = sim.carrierName,
                trustedContacts = _uiState.value.trustedPersons,
                isNetworkAvailable = net.isConnected,
                processImmediately = true
            )
        }
    }

    fun registerDevice(deviceName: String, ownerEmail: String, pin: String, biometric: Boolean): Boolean {
        val enrolled = container.deviceSettingsRepository.registerDevice(deviceName, ownerEmail, pin, biometric)
        if (!enrolled) {
            val health = container.deviceSettingsRepository.vaultHealth.value
            val validation = com.example.domain.security.OfflineSecurityVault.validatePinStrength(pin)
            val errMsg = when (validation) {
                is com.example.domain.security.OfflineSecurityVault.PinValidationResult.InvalidFormat -> validation.reason
                is com.example.domain.security.OfflineSecurityVault.PinValidationResult.WeakPin -> validation.reason
                is com.example.domain.security.OfflineSecurityVault.PinValidationResult.Valid ->
                    health.lastError ?: "Android Keystore unavailable; Master PIN could not be enrolled."
            }
            _uiState.update { it.copy(pinActionError = errMsg) }
            return false
        }
        _uiState.update { it.copy(pinActionError = null) }
        viewModelScope.launch {
            container.securityRepository.recordEvent(
                SecurityEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    severity = EventSeverity.INFO,
                    title = "Device Enrolled: $deviceName",
                    description = "Owner security identity $ownerEmail bound to hardware node.",
                    source = "DeviceRegistration",
                    riskContribution = 0
                )
            )
        }
        return true
    }

    fun setAuthDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showAuthDialog = visible, authErrorMessage = null) }
    }

    fun clearAuthError() {
        _uiState.update { it.copy(authErrorMessage = null) }
    }

    fun signInWithFirebase(email: String, password: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authErrorMessage = null) }
            val result = container.authRepository.signInWithEmail(email, password)
            when (result) {
                is com.example.data.repository.AuthResult.Success -> {
                    val signedInEmail = result.user?.email ?: email.trim()
                    container.deviceSettingsRepository.updateOwnerEmailIfBlank(signedInEmail)
                    _uiState.update {
                        it.copy(
                            isAuthLoading = false,
                            isAuthenticated = true,
                            authenticatedUserEmail = result.user?.email,
                            authenticatedUserId = result.user?.uid,
                            isEmailVerified = result.isEmailVerified,
                            showAuthDialog = false,
                            authErrorMessage = null
                        )
                    }
                    onComplete(true)
                }
                is com.example.data.repository.AuthResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isAuthLoading = false,
                            authErrorMessage = result.message
                        )
                    }
                    onComplete(false)
                }
            }
        }
    }

    fun registerWithFirebase(
        email: String,
        password: String,
        deviceName: String,
        masterPin: String,
        onComplete: (Boolean) -> Unit = {}
    ) {
        when (val pinCheck = com.example.domain.security.OfflineSecurityVault.validatePinStrength(masterPin)) {
            is com.example.domain.security.OfflineSecurityVault.PinValidationResult.InvalidFormat -> {
                _uiState.update { it.copy(authErrorMessage = pinCheck.reason) }
                onComplete(false)
                return
            }
            is com.example.domain.security.OfflineSecurityVault.PinValidationResult.WeakPin -> {
                _uiState.update { it.copy(authErrorMessage = pinCheck.reason) }
                onComplete(false)
                return
            }
            is com.example.domain.security.OfflineSecurityVault.PinValidationResult.Valid -> Unit
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authErrorMessage = null) }
            val result = container.authRepository.registerWithEmail(email, password)
            when (result) {
                is com.example.data.repository.AuthResult.Success -> {
                    val registered = container.deviceSettingsRepository.registerDevice(
                        deviceName = deviceName.ifBlank { "Android Secure Node" },
                        ownerEmail = email.trim(),
                        pin = masterPin,
                        biometric = false
                    )
                    if (!registered) {
                        val err = container.deviceSettingsRepository.vaultHealth.value.lastError
                            ?: "Hardware Keystore vault unavailable. Master PIN could not be saved."
                        _uiState.update {
                            it.copy(
                                isAuthLoading = false,
                                isAuthenticated = true,
                                authenticatedUserEmail = result.user?.email,
                                authenticatedUserId = result.user?.uid,
                                isEmailVerified = result.isEmailVerified,
                                authVerificationSent = true,
                                authErrorMessage = err
                            )
                        }
                        onComplete(false)
                        return@launch
                    }
                    _uiState.update {
                        it.copy(
                            isAuthLoading = false,
                            isAuthenticated = true,
                            authenticatedUserEmail = result.user?.email,
                            authenticatedUserId = result.user?.uid,
                            isEmailVerified = result.isEmailVerified,
                            authVerificationSent = true,
                            authErrorMessage = null
                        )
                    }
                    onComplete(true)
                }
                is com.example.data.repository.AuthResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isAuthLoading = false,
                            authErrorMessage = result.message
                        )
                    }
                    onComplete(false)
                }
            }
        }
    }

    fun sendEmailVerification(onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true) }
            val result = container.authRepository.sendEmailVerification()
            when (result) {
                is com.example.data.repository.AuthResult.Success -> {
                    _uiState.update { it.copy(isAuthLoading = false, authVerificationSent = true, authErrorMessage = null) }
                    onComplete(true)
                }
                is com.example.data.repository.AuthResult.Error -> {
                    _uiState.update { it.copy(isAuthLoading = false, authErrorMessage = result.message) }
                    onComplete(false)
                }
            }
        }
    }

    fun checkEmailVerificationStatus(onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true) }
            val verified = container.authRepository.checkEmailVerification()
            _uiState.update {
                it.copy(
                    isAuthLoading = false,
                    isEmailVerified = verified,
                    authErrorMessage = if (!verified) {
                        if (it.currentLanguage.equals("en", ignoreCase = true))
                            "Email not yet verified. Please click the link received in your inbox."
                        else
                            "Email non encore vérifié. Veuillez cliquer sur le lien reçu dans votre boîte de réception."
                    } else null
                )
            }
            if (verified) {
                evaluateSuperAdminAuthorization(forceRefresh = true)
            }
            onComplete(verified)
        }
    }

    fun signOutFromFirebase() {
        viewModelScope.launch {
            container.authRepository.signOut()
            _uiState.update {
                it.copy(
                    isAuthenticated = false,
                    authenticatedUserEmail = null,
                    authenticatedUserId = null,
                    isEmailVerified = false,
                    authVerificationSent = false,
                    authSuccessMessage = null,
                    authErrorMessage = null
                )
            }
        }
    }

    fun signInWithGoogle(idToken: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authErrorMessage = null, authSuccessMessage = null) }
            val result = container.authRepository.signInWithGoogle(idToken)
            when (result) {
                is com.example.data.repository.AuthResult.Success -> {
                    result.user?.email?.let { container.deviceSettingsRepository.updateOwnerEmailIfBlank(it) }
                    _uiState.update {
                        it.copy(
                            isAuthLoading = false,
                            isAuthenticated = true,
                            authenticatedUserEmail = result.user?.email,
                            authenticatedUserId = result.user?.uid,
                            isEmailVerified = result.isEmailVerified,
                            authErrorMessage = null,
                            authSuccessMessage = "Connexion Google réussie."
                        )
                    }
                    onComplete(true)
                }
                is com.example.data.repository.AuthResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isAuthLoading = false,
                            authErrorMessage = result.message
                        )
                    }
                    onComplete(false)
                }
            }
        }
    }

    fun sendPasswordResetEmail(email: String, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isAuthLoading = true, authErrorMessage = null, authSuccessMessage = null) }
            val result = container.authRepository.sendPasswordResetEmail(email)
            when (result) {
                is com.example.data.repository.AuthResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isAuthLoading = false,
                            authResetEmailSent = true,
                            authSuccessMessage = "Un email de réinitialisation a été envoyé à $email. Vérifiez votre boîte de réception.",
                            authErrorMessage = null
                        )
                    }
                    onComplete(true)
                }
                is com.example.data.repository.AuthResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isAuthLoading = false,
                            authErrorMessage = result.message
                        )
                    }
                    onComplete(false)
                }
            }
        }
    }

    fun clearAuthMessages() {
        _uiState.update {
            it.copy(
                authErrorMessage = null,
                authSuccessMessage = null,
                authResetEmailSent = false
            )
        }
    }

    /**
     * Evaluates Super Admin authorization from Firestore "appconfig/admins".
     * Strictly requires authenticated user and verified email.
     */
    fun evaluateSuperAdminAuthorization(forceRefresh: Boolean = false, onComplete: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCheckingSuperAdmin = true) }
            val result = container.superAdminRepository.verifySuperAdminStatus(forceRefresh)
            when (result) {
                is com.example.data.repository.AdminVerificationResult.Authorized -> {
                    val countResult = container.superAdminRepository.fetchRegisteredUsersCount()
                    _uiState.update {
                        it.copy(
                            isSuperAdmin = true,
                            isCheckingSuperAdmin = false,
                            adminVerificationMessage = "Super Admin vérifié (${result.email})",
                            superAdminUserCount = countResult.getOrNull()
                        )
                    }
                    onComplete(true)
                }
                is com.example.data.repository.AdminVerificationResult.Denied -> {
                    _uiState.update {
                        it.copy(
                            isSuperAdmin = false,
                            isCheckingSuperAdmin = false,
                            adminVerificationMessage = result.reason,
                            superAdminUserCount = null
                        )
                    }
                    onComplete(false)
                }
                is com.example.data.repository.AdminVerificationResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isSuperAdmin = false,
                            isCheckingSuperAdmin = false,
                            adminVerificationMessage = result.message,
                            superAdminUserCount = null
                        )
                    }
                    onComplete(false)
                }
            }
        }
    }

    fun enrollMasterPin(pin: String): Boolean {
        val isEn = _uiState.value.currentLanguage.equals("en", ignoreCase = true)
        return when (val res = container.deviceSettingsRepository.enrollMasterPin(pin)) {
            is com.example.domain.security.OfflineSecurityVault.PinEnrollmentResult.Success -> {
                failedAuthCount = 0
                _uiState.update {
                    it.copy(
                        isPinConfigured = true,
                        failedAuthAttempts = 0,
                        pinActionError = null,
                        pinActionSuccess = if (isEn) "Master PIN enrolled securely in hardware vault." else "Code PIN maître enregistré dans le coffre matériel."
                    )
                }
                true
            }
            is com.example.domain.security.OfflineSecurityVault.PinEnrollmentResult.Rejected -> {
                _uiState.update { it.copy(pinActionError = res.reason, pinActionSuccess = null) }
                false
            }
            is com.example.domain.security.OfflineSecurityVault.PinEnrollmentResult.VaultUnavailable -> {
                _uiState.update {
                    it.copy(
                        isVaultKeystoreHealthy = false,
                        vaultErrorMessage = res.reason,
                        pinActionError = res.reason,
                        pinActionSuccess = null
                    )
                }
                false
            }
        }
    }

    fun changeMasterPin(currentPin: String, newPin: String): Boolean {
        val isEn = _uiState.value.currentLanguage.equals("en", ignoreCase = true)
        return when (val res = container.deviceSettingsRepository.changeMasterPin(currentPin, newPin)) {
            is com.example.domain.security.OfflineSecurityVault.PinEnrollmentResult.Success -> {
                failedAuthCount = 0
                _uiState.update {
                    it.copy(
                        isPinConfigured = true,
                        failedAuthAttempts = 0,
                        pinActionError = null,
                        pinActionSuccess = if (isEn) "Master PIN updated securely." else "Code PIN maître mis à jour de façon sécurisée."
                    )
                }
                true
            }
            is com.example.domain.security.OfflineSecurityVault.PinEnrollmentResult.Rejected -> {
                _uiState.update { it.copy(pinActionError = res.reason, pinActionSuccess = null) }
                false
            }
            is com.example.domain.security.OfflineSecurityVault.PinEnrollmentResult.VaultUnavailable -> {
                _uiState.update {
                    it.copy(
                        isVaultKeystoreHealthy = false,
                        vaultErrorMessage = res.reason,
                        pinActionError = res.reason,
                        pinActionSuccess = null
                    )
                }
                false
            }
        }
    }

    fun clearPinMessages() {
        _uiState.update { it.copy(pinActionError = null, pinActionSuccess = null) }
    }

    fun verifyMasterPin(pin: String): Boolean {
        val isEn = _uiState.value.currentLanguage.equals("en", ignoreCase = true)
        return when (val result = container.deviceSettingsRepository.verifyPinDetailed(pin)) {
            is com.example.domain.security.OfflineSecurityVault.PinVerificationResult.Verified -> {
                failedAuthCount = 0
                _uiState.update {
                    it.copy(
                        failedAuthAttempts = 0,
                        pinActionError = null,
                        pinLockoutRemainingMillis = 0L
                    )
                }
                true
            }
            is com.example.domain.security.OfflineSecurityVault.PinVerificationResult.NotConfigured -> {
                _uiState.update {
                    it.copy(
                        isPinConfigured = false,
                        pinActionError = if (isEn)
                            "No Master PIN configured. Enroll a Master PIN in Settings first."
                        else
                            "Aucun code PIN maître configuré. Définissez un PIN dans Paramètres."
                    )
                }
                false
            }
            is com.example.domain.security.OfflineSecurityVault.PinVerificationResult.LockedOut -> {
                val secs = (result.retryAfterMillis + 999L) / 1000L
                _uiState.update {
                    it.copy(
                        pinLockoutRemainingMillis = result.retryAfterMillis,
                        pinActionError = if (isEn)
                            "Too many failed attempts. Try again in ${secs}s."
                        else
                            "Trop de tentatives échouées. Réessayez dans ${secs}s."
                    )
                }
                false
            }
            is com.example.domain.security.OfflineSecurityVault.PinVerificationResult.VaultUnavailable -> {
                _uiState.update {
                    it.copy(
                        isVaultKeystoreHealthy = false,
                        vaultErrorMessage = result.reason,
                        pinActionError = if (isEn)
                            "Security vault Keystore unavailable. Verification blocked."
                        else
                            "Coffre Keystore indisponible. Vérification bloquée par sécurité."
                    )
                }
                viewModelScope.launch {
                    container.securityRepository.recordEvent(
                        SecurityEvent(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            severity = EventSeverity.CRITICAL,
                            title = "Vault Keystore Verification Failure",
                            description = "Master PIN verification failed closed: ${result.reason}",
                            source = "OfflineSecurityVault",
                            riskContribution = 25
                        )
                    )
                }
                false
            }
            is com.example.domain.security.OfflineSecurityVault.PinVerificationResult.InvalidPin -> {
                failedAuthCount++
                _uiState.update {
                    it.copy(
                        failedAuthAttempts = failedAuthCount,
                        pinActionError = if (isEn)
                            "Incorrect PIN (${result.remainingAttempts} attempts remaining)."
                        else
                            "Code PIN incorrect (${result.remainingAttempts} tentatives restantes)."
                    )
                }
                viewModelScope.launch {
                    container.securityRepository.recordEvent(
                        SecurityEvent(
                            id = UUID.randomUUID().toString(),
                            timestamp = System.currentTimeMillis(),
                            severity = EventSeverity.WARNING,
                            title = "Master Authentication Failed",
                            description = "Unauthorized attempt to verify master PIN credentials ($failedAuthCount attempts).",
                            source = "AuthModule",
                            riskContribution = if (failedAuthCount >= 3) 40 else 10
                        )
                    )
                    if (failedAuthCount >= 3) {
                        container.alertManager.dispatchAlert(
                            title = "Multiple Auth Failures",
                            body = "$failedAuthCount failed PIN attempts on node.",
                            isCritical = true
                        )
                    }
                }
                scheduleTelemetryEvaluation(debounceMs = 0L)
                false
            }
        }
    }

    fun armHighAlert() {
        viewModelScope.launch {
            val res = container.securityBrain.armHighAlert()
            if (res.decision == PolicyDecision.APPROVED) {
                container.securityStateManager.armHighAlert()
                _uiState.update { it.copy(isArmArmed = true) }
            }
        }
    }

    fun disarmWithPin(pin: String): Boolean {
        if (verifyMasterPin(pin)) {
            failedAuthCount = 0
            DeviceSettingsRepository.setStolenTrackingActive(container.context, false)
            _uiState.update { it.copy(failedAuthAttempts = 0) }
            viewModelScope.launch {
                val opaqueToken = "DISARM_AUTH_${UUID.randomUUID()}"
                val res = container.securityBrain.requestDisarm(authorizationToken = opaqueToken)
                if (res.decision == PolicyDecision.APPROVED) {
                    container.securityStateManager.disarmToNormal("Verified Master PIN")
                    _uiState.update { it.copy(isArmArmed = false) }
                }
            }
            return true
        }
        return false
    }

    /**
     * Unified, Owner-Authorized Theft Confirmation Protocol:
     * 1. Verifies Master PIN securely through DeviceSettingsRepository.
     * 2. Rejects invalid PIN attempts and logs an authentic security failure in the ledger.
     * 3. Obtains authorization through SecurityBrain and SecurityPolicyEngine with an opaque token (NEVER the raw PIN).
     * 4. Transitions SecurityStateManager into OWNER_CONFIRMED_STOLEN / STOLEN_MODE.
     * 5. Attempts legitimate hardware screen lock via DeviceLockManager.lockDeviceNow() without faking when Device Admin is inactive.
     * 6. Generates and persists the forensic theft evidence report into the offline queue.
     * 7. AdaptiveMonitoringPolicy automatically triggers 10s recovery beacons when in STOLEN_MODE.
     */
    fun confirmStolenByOwner(pin: String): Boolean {
        if (!verifyMasterPin(pin)) {
            viewModelScope.launch {
                container.securityRepository.recordEvent(
                    SecurityEvent(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        severity = EventSeverity.WARNING,
                        title = "Tentative vol rejetée",
                        description = "Tentative d'activation du mode vol rejetée (code PIN maître invalide).",
                        source = "AuthModule",
                        riskContribution = 20
                    )
                )
            }
            return false
        }

        viewModelScope.launch {
            // Step 1: Engage Unified Security Brain Policy & Incident response with opaque cryptographically random token (NEVER raw PIN)
            val opaqueSessionToken = "PIN_VERIFIED_SESSION_${UUID.randomUUID()}"
            val brainDecision = container.securityBrain.requestDeclareStolen(
                isOwnerConfirmed = true,
                authorizationToken = opaqueSessionToken,
                persistTransition = {
                    DeviceSettingsRepository.setStolenTrackingActiveCommitted(
                        context = container.context,
                        active = true,
                        stateName = SecurityState.STOLEN_MODE.name
                    )
                }
            )

            if (brainDecision.decision == PolicyDecision.APPROVED) {
                // Step 2: Attempt legitimate Android Device Lock if Device Admin is active
                val lockResult = container.deviceLockManager.lockDeviceNow()
                val isLockSuccess = lockResult is DeviceLockManager.LockExecutionResult.Success

                container.securityRepository.recordEvent(
                    SecurityEvent(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        severity = if (isLockSuccess) EventSeverity.CRITICAL else EventSeverity.WARNING,
                        title = if (isLockSuccess) "Verrouillage matériel exécuté" else "Verrouillage matériel différé",
                        description = when (lockResult) {
                            is DeviceLockManager.LockExecutionResult.Success ->
                                "Écran verrouillé avec succès via DevicePolicyManager.lockNow()."
                            is DeviceLockManager.LockExecutionResult.Unavailable ->
                                lockResult.reason
                            is DeviceLockManager.LockExecutionResult.SecurityError ->
                                lockResult.message
                        },
                        source = "DevicePolicyManager",
                        riskContribution = if (isLockSuccess) 80 else 40
                    )
                )

                // Step 3: Transition state machine to OWNER_CONFIRMED_STOLEN then STOLEN_MODE
                container.securityStateManager.confirmStolenByOwner("Verified Master PIN")

                // Step 4: Compile cryptographic forensic theft report
                val report = container.incidentResponseEngine.generateTheftReport(
                    activeIncident = _uiState.value.activeIncident,
                    location = _uiState.value.location,
                    simInfo = _uiState.value.simInfo,
                    batteryPercent = _uiState.value.deviceHealth.batteryPercentage
                )

                // Step 5: Enqueue high priority offline queue item for backend sync with JSONObject & idempotency key
                val theftPayload = org.json.JSONObject().apply {
                    put("reportId", report.reportId)
                    put("deviceId", report.deviceId)
                    put("signature", report.cryptographicSignature)
                    put("timestamp", report.timestamp)
                }.toString()
                container.securityEventQueue.enqueue(
                    eventType = "THEFT_REPORT_COMPILED",
                    payload = theftPayload,
                    priority = com.example.core.model.QueuePriority.CRITICAL,
                    idempotencyKey = "theft_report_${report.reportId}"
                )

                // Step 6: Start RecoveryMonitoringService using AdaptiveMonitoringPolicy & request immediate fresh GPS fix
                val strategy = container.adaptiveMonitoringPolicy.determineStrategy(
                    state = SecurityState.STOLEN_MODE,
                    batteryPercentage = _uiState.value.deviceHealth.batteryPercentage,
                    isCharging = _uiState.value.deviceHealth.isCharging
                )
                try {
                    container.locationEngine.applyMonitoringInterval(strategy.locationIntervalMillis)
                    container.locationEngine.requestFreshLocationFix()
                    LocationSyncWorker.enqueue(container.context)
                    SecurityEventSyncWorker.enqueue(container.context)
                    val savedContact = container.deviceSettingsRepository.recoveryContactPhone.value
                        .takeIf { it.isNotBlank() }
                    val isServiceStarted = RecoveryMonitoringService.startService(
                        context = container.context,
                        contactNumber = savedContact,
                        intervalMillis = strategy.locationIntervalMillis
                    )
                    _uiState.update { it.copy(isForegroundServiceActive = isServiceStarted) }
                } catch (e: Exception) {
                    android.util.Log.e("InkingiViewModel", "Failed to engage RecoveryMonitoringService: ${e.message}", e)
                }
            }
        }
        return true
    }

    /**
     * Autonomous Emergency Device Lock:
     * When SecurityBrain reaches SUSPECTED_LOST through sufficiently strong independent threat signals,
     * InkingiGuard automatically attempts hardware screen locking via DevicePolicyManager.lockNow().
     *
     * Strict Guardrails:
     * 1. Requires SUSPECTED_LOST state with current risk score >= 80 and multiple independent evidence items.
     * 2. Executes at most once per active incident cluster to prevent duplicate lock attempts.
     * 3. Respects battery safety policy: never locks solely due to low battery.
     * 4. If Device Admin is unavailable, records the real limitation without faking a successful lock.
     * 5. Does NOT automatically change SUSPECTED_LOST into OWNER_CONFIRMED_STOLEN.
     */
    fun triggerAutonomousEmergencyLock(): DeviceLockManager.LockExecutionResult? {
        val currentState = _uiState.value.securityState
        val riskEval = container.securityBrain.currentRiskEvaluation.value
        val incident = _uiState.value.activeIncident
        val incidentId = incident?.incidentId ?: "AUTO_CLUSTER_${riskEval.timestamp}"

        // Guard 1: Must be in SUSPECTED_LOST state AND have sufficient risk score (>= 80)
        if (currentState != SecurityState.SUSPECTED_LOST || riskEval.totalScore < 80) {
            return null
        }

        // Guard 2: Require multiple independent signal categories (not repeated copies of one signal)
        val distinctFactorKeys = riskEval.evidenceItems.map { it.factorKey }.distinct()
        if (distinctFactorKeys.size < 2) {
            android.util.Log.w("InkingiViewModel", "Autonomous lock deferred: evidence contains only 1 factor category (${distinctFactorKeys.firstOrNull()}). Multiple independent signals required.")
            return null
        }

        // Guard 3: Execute at most once per incident cluster (anti-duplicate guard)
        if (lastAutonomousLockIncidentId == incidentId) {
            android.util.Log.d("InkingiViewModel", "Autonomous lock already executed for incident $incidentId. Suppressing duplicate.")
            return null
        }

        // Guard 4: Strict Battery Safety Requirement
        // At battery level 15% or below (<= 15%), InkingiGuard must NEVER initiate an automatic device lock,
        // even when physical theft signals (SIM_CHANGE, AUTH_FAILURES, PERIMETER_EXIT, etc.) are present.
        // At or below 15%, preserve normal phone usability, do not lock, while continuing GPS monitoring and evidence storage.
        val batteryPct = _uiState.value.deviceHealth.batteryPercentage
        if (batteryPct <= 15) {
            android.util.Log.w("InkingiViewModel", "Autonomous lock strictly prohibited at or below 15% battery threshold (current: $batteryPct%). Preserving device usability and GPS monitoring.")
            return null
        }

        lastAutonomousLockIncidentId = incidentId

        // Execute legitimate hardware lock via DeviceLockManager
        val lockResult = container.deviceLockManager.lockDeviceNow()
        val isLockSuccess = lockResult is DeviceLockManager.LockExecutionResult.Success

        viewModelScope.launch {
            container.securityRepository.recordEvent(
                SecurityEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    severity = if (isLockSuccess) EventSeverity.CRITICAL else EventSeverity.WARNING,
                    title = if (isLockSuccess) "Verrouillage d'urgence autonome exécuté" else "Verrouillage d'urgence autonome différé",
                    description = when (lockResult) {
                        is DeviceLockManager.LockExecutionResult.Success ->
                            "Écran verrouillé de façon autonome (SUSPECTED_LOST, score: ${riskEval.totalScore}/100, facteurs: ${distinctFactorKeys.joinToString(", ")})."
                        is DeviceLockManager.LockExecutionResult.Unavailable ->
                            "Verrouillage autonome non exécuté: ${lockResult.reason}"
                        is DeviceLockManager.LockExecutionResult.SecurityError ->
                            "Erreur de sécurité verrouillage autonome: ${lockResult.message}"
                    },
                    source = "AutonomousSecurityBrain",
                    riskContribution = if (isLockSuccess) 90 else 50
                )
            )

            // Dispatch alert to notify the environment of protective lock
            container.alertManager.dispatchAlert(
                title = if (isLockSuccess) "AUTONOMOUS LOCK ENGAGED" else "AUTONOMOUS LOCK DEFERRED",
                body = if (isLockSuccess)
                    "Device autonomously locked due to multi-signal divergence (Score: ${riskEval.totalScore})."
                else
                    "Multi-signal divergence detected. Enable Device Admin to allow autonomous hardware lock.",
                isCritical = true
            )
        }

        return lockResult
    }

    fun activateRecoveryMode(pin: String): Boolean {
        if (verifyMasterPin(pin)) {
            viewModelScope.launch {
                val opaqueToken = "RECOVERY_AUTH_${UUID.randomUUID()}"
                val res = container.securityBrain.requestActivateRecoveryMode(
                    authorizationToken = opaqueToken,
                    persistTransition = {
                        DeviceSettingsRepository.setStolenTrackingActiveCommitted(
                            context = container.context,
                            active = true,
                            stateName = SecurityState.RECOVERY_MODE.name
                        )
                    }
                )
                if (res.decision == PolicyDecision.APPROVED) {
                    container.securityStateManager.activateRecoveryMode("Owner Authenticated PIN")
                }
            }
            return true
        }
        return false
    }

    fun markRecovered(pin: String): Boolean {
        if (verifyMasterPin(pin)) {
            viewModelScope.launch {
                val opaqueToken = "RECOVERED_AUTH_${UUID.randomUUID()}"
                val res = container.securityBrain.requestMarkRecovered(
                    authorizationToken = opaqueToken,
                    persistTransition = {
                        DeviceSettingsRepository.setStolenTrackingActiveCommitted(
                            context = container.context,
                            active = false,
                            stateName = SecurityState.RECOVERED.name
                        )
                    }
                )
                if (res.decision == PolicyDecision.APPROVED) {
                    container.securityStateManager.markRecovered("Verified Master PIN")
                    container.alertManager.dispatchAlert(
                        title = "DEVICE RECOVERED",
                        body = "Node restored to authorized custody.",
                        isCritical = false
                    )
                }
            }
            return true
        }
        return false
    }

    fun attemptDeviceLock(): DeviceLockManager.LockExecutionResult {
        return container.deviceLockManager.lockDeviceNow()
    }

    fun triggerOfflineSync() {
        LocationSyncWorker.enqueue(container.context)
        SecurityEventSyncWorker.enqueue(container.context)
        viewModelScope.launch {
            val isOnline = _uiState.value.networkInfo.isConnected
            container.securityEventQueue.processPendingQueue(isOnline)
            container.securityEventQueue.pruneExpiredConfirmedItems()
            if (isOnline) {
                val syncedPoints = container.secureRecoveryBridge.syncOfflineBreadcrumbs()
                if (syncedPoints > 0) {
                    android.util.Log.i("InkingiViewModel", "Synchronized $syncedPoints offline location breadcrumbs to Firestore.")
                }
            }
        }
    }

    fun runTamperCheck() {
        container.tamperEngine.performTamperAudit()
    }

    fun addTrustedZone(name: String, radius: Float) {
        val loc = _uiState.value.location
        val lat = loc?.latitude ?: -1.9441
        val lng = loc?.longitude ?: 30.0619
        viewModelScope.launch {
            container.securityRepository.addTrustedZone(name, lat, lng, radius)
        }
    }

    fun removeTrustedZone(id: String) {
        viewModelScope.launch {
            container.securityRepository.removeTrustedZone(id)
        }
    }

    fun switchUserRole(targetRole: com.example.core.model.UserRole, pin: String? = null): Boolean {
        val success = container.deviceSettingsRepository.switchUserRole(targetRole, pin)
        if (success) {
            viewModelScope.launch {
                container.securityRepository.recordEvent(
                    SecurityEvent(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        severity = com.example.core.model.EventSeverity.INFO,
                        title = "Profil utilisateur changé",
                        description = "Profil actif: ${targetRole.name}.",
                        source = "UserManagement",
                        riskContribution = 0
                    )
                )
            }
        }
        return success
    }

    fun addTrustedPerson(name: String, phoneNumber: String, relationship: String, pin: String): Boolean {
        val success = container.deviceSettingsRepository.addTrustedPerson(name, phoneNumber, relationship, pin)
        if (success) {
            viewModelScope.launch {
                container.securityRepository.recordEvent(
                    SecurityEvent(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        severity = com.example.core.model.EventSeverity.INFO,
                        title = "Personne de confiance ajoutée",
                        description = "Contact: $name ($relationship).",
                        source = "TrustedPersonManagement",
                        riskContribution = 0
                    )
                )
            }
        }
        return success
    }

    fun removeTrustedPerson(id: String, pin: String): Boolean {
        val success = container.deviceSettingsRepository.removeTrustedPerson(id, pin)
        if (success) {
            viewModelScope.launch {
                container.securityRepository.recordEvent(
                    SecurityEvent(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        severity = com.example.core.model.EventSeverity.INFO,
                        title = "Personne de confiance retirée",
                        description = "Contact ID: $id retiré par le propriétaire.",
                        source = "TrustedPersonManagement",
                        riskContribution = 0
                    )
                )
            }
        }
        return success
    }

    fun clearLedger(pin: String? = null): Boolean {
        // Enforce owner verification if pin is provided or if in guest mode
        if (pin != null && !verifyMasterPin(pin)) return false
        if (_uiState.value.currentUserRole == com.example.core.model.UserRole.GUEST && (pin == null || !verifyMasterPin(pin))) return false
        viewModelScope.launch {
            container.securityRepository.clearAllEvents()
        }
        return true
    }

    fun resetBehaviorBaseline(pin: String? = null): Boolean {
        if (pin != null && !verifyMasterPin(pin)) return false
        if (_uiState.value.currentUserRole == com.example.core.model.UserRole.GUEST && (pin == null || !verifyMasterPin(pin))) return false
        viewModelScope.launch {
            container.behaviorLearningManager.resetBaseline()
        }
        return true
    }

    fun setLanguage(lang: String, pin: String? = null): Boolean {
        // In GUEST mode, changing system settings is restricted to owner authorization
        if (_uiState.value.currentUserRole == com.example.core.model.UserRole.GUEST) {
            if (pin.isNullOrBlank() || !verifyMasterPin(pin)) {
                return false
            }
        }
        container.deviceSettingsRepository.setLanguage(lang)
        scheduleTelemetryEvaluation(debounceMs = 0L)
        return true
    }

    /**
     * Phase 3: Lost Phone / Find Nearby
     * Plays audible ringtone on STREAM_ALARM without microphone or surveillance.
     */
    fun triggerFindMyPhoneAlarm(): Boolean {
        val started = container.lostPhoneAlarmManager.startAlarm()
        if (started) {
            _uiState.update { it.copy(isFindMyPhoneAlarmActive = true) }
            viewModelScope.launch {
                container.securityRepository.recordEvent(
                    SecurityEvent(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        severity = EventSeverity.INFO,
                        title = "Alarme locale déclenchée",
                        description = "Alarme sonore 'Retrouver mon téléphone' activée par le propriétaire.",
                        source = "LostPhoneAlarm",
                        riskContribution = 0
                    )
                )
            }
        }
        return started
    }

    fun stopFindMyPhoneAlarm(pin: String): Boolean {
        if (!verifyMasterPin(pin)) {
            viewModelScope.launch {
                container.securityRepository.recordEvent(
                    SecurityEvent(
                        id = UUID.randomUUID().toString(),
                        timestamp = System.currentTimeMillis(),
                        severity = EventSeverity.WARNING,
                        title = "Échec d'arrêt alarme",
                        description = "Tentative non autorisée d'interrompre l'alarme sonore sans code PIN valide.",
                        source = "LostPhoneAlarm",
                        riskContribution = 10
                    )
                )
            }
            return false
        }

        container.lostPhoneAlarmManager.stopAlarm()
        _uiState.update { it.copy(isFindMyPhoneAlarmActive = false) }

        viewModelScope.launch {
            container.securityRepository.recordEvent(
                SecurityEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    severity = EventSeverity.INFO,
                    title = "Alarme locale arrêtée",
                    description = "Alarme sonore interrompue avec succès par le propriétaire (Code PIN vérifié).",
                    source = "LostPhoneAlarm",
                    riskContribution = 0
                )
            )
        }
        return true
    }

    fun generatePoliceReportAction() {
        if (_uiState.value.policeReportState.status == PoliceReportSubmissionStatus.SUBMITTING) {
            return
        }

        val currentSnapshot = _uiState.value
        val isEn = currentSnapshot.currentLanguage.equals("en", ignoreCase = true)
        val ownerUid = currentSnapshot.authenticatedUserId
            ?: container.authRepository.currentUser?.uid
        val ownerEmail = (currentSnapshot.authenticatedUserEmail
            ?: currentSnapshot.deviceProfile.ownerEmail).ifBlank { "Non configuré" }
        val isOnline = currentSnapshot.networkInfo.isConnected

        _uiState.update {
            it.copy(
                policeReportState = PoliceReportState(
                    status = PoliceReportSubmissionStatus.SUBMITTING,
                    detailMessage = if (isEn)
                        "Collecting verified device telemetry and attempting cloud backup..."
                    else
                        "Collecte de la télémétrie vérifiée et sauvegarde cloud en cours..."
                )
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val submissionResult = container.evidenceRepository.collectEvidenceForPoliceWithResult(
                deviceId = currentSnapshot.deviceProfile.deviceId,
                ownerUid = ownerUid,
                isNetworkConnected = isOnline,
                location = currentSnapshot.location,
                simInfo = currentSnapshot.simInfo,
                batteryInfo = currentSnapshot.deviceHealth,
                riskAssessment = currentSnapshot.riskAssessment
            )

            val reportText = com.example.domain.evidence.PoliceReportGenerator.generateReportText(
                evidence = submissionResult.evidence,
                ownerEmail = ownerEmail
            )

            val now = System.currentTimeMillis()
            val mappedState = when (submissionResult.outcome) {
                com.example.domain.evidence.EvidenceSyncOutcome.SUBMITTED -> {
                    val emailResult = container.emailRelayGateway.queueOwnerEmail(
                        ownerEmail = ownerEmail,
                        ownerUid = ownerUid,
                        reportText = reportText
                    )
                    val emailNote = if (emailResult.isSuccess) {
                        if (isEn) "Owner email relay queued." else "Relais email propriétaire mis en file d'attente."
                    } else {
                        if (isEn) "Owner email relay skipped/failed (${emailResult.exceptionOrNull()?.message ?: "unavailable"})."
                        else "Relais email non exécuté (${emailResult.exceptionOrNull()?.message ?: "indisponible"})."
                    }
                    PoliceReportState(
                        status = PoliceReportSubmissionStatus.SUBMITTED,
                        evidenceSyncStatus = submissionResult.syncStatusLabel,
                        emailRelayStatus = emailResult.getOrElse { it.message ?: "FAILED" },
                        reportPreview = reportText,
                        detailMessage = if (isEn) {
                            "Evidence snapshot saved to your encrypted cloud backup. $emailNote Note: InkingiGuard does NOT transmit reports directly to police—share this technical report manually."
                        } else {
                            "Dossier technique sauvegardé dans votre espace cloud sécurisé. $emailNote Remarque : InkingiGuard n'envoie PAS automatiquement le rapport à la police — remettez ce document manuellement aux autorités."
                        },
                        updatedAtMillis = now
                    )
                }

                com.example.domain.evidence.EvidenceSyncOutcome.PENDING_OFFLINE -> {
                    PoliceReportState(
                        status = PoliceReportSubmissionStatus.PENDING,
                        evidenceSyncStatus = submissionResult.syncStatusLabel,
                        emailRelayStatus = "OFFLINE_DEFERRED",
                        reportPreview = reportText,
                        detailMessage = if (isEn) {
                            "Offline: technical report generated and saved in the local encrypted queue only. It has NOT been synced to the cloud or sent to police."
                        } else {
                            "Hors ligne : rapport technique généré et enregistré dans la file locale chiffrée uniquement. NON synchronisé au cloud et NON envoyé à la police."
                        },
                        updatedAtMillis = now
                    )
                }

                com.example.domain.evidence.EvidenceSyncOutcome.UNAVAILABLE -> {
                    PoliceReportState(
                        status = PoliceReportSubmissionStatus.UNAVAILABLE,
                        evidenceSyncStatus = submissionResult.syncStatusLabel,
                        emailRelayStatus = "UNAVAILABLE",
                        reportPreview = reportText,
                        detailMessage = if (isEn) {
                            "Cloud backup unavailable (signed-in Firebase owner session required). Local report generated only—NOT synced or sent to police."
                        } else {
                            "Sauvegarde cloud indisponible (session propriétaire Firebase requise). Rapport généré localement uniquement — NON synchronisé et NON envoyé à la police."
                        },
                        updatedAtMillis = now
                    )
                }

                com.example.domain.evidence.EvidenceSyncOutcome.FAILED -> {
                    PoliceReportState(
                        status = PoliceReportSubmissionStatus.FAILED,
                        evidenceSyncStatus = submissionResult.syncStatusLabel,
                        emailRelayStatus = "FAILED",
                        reportPreview = reportText,
                        detailMessage = if (isEn) {
                            "Cloud upload failed (${submissionResult.errorMessage ?: "backend error"}). Local report generated only—NOT synced or sent to police."
                        } else {
                            "Échec d'envoi cloud (${submissionResult.errorMessage ?: "erreur serveur"}). Rapport généré localement uniquement — NON synchronisé et NON envoyé à la police."
                        },
                        updatedAtMillis = now
                    )
                }
            }

            _uiState.update { it.copy(policeReportState = mappedState) }
        }
    }

    /**
     * Big Red Button Action:
     * VOL CONFIRMÉ - VERROUILLER -> Delegates directly to the unified confirmStolenByOwner protocol.
     * Preserves backward compatibility for existing callers while eliminating duplication.
     */
    fun confirmStolenWithStrongLock(pin: String): Boolean {
        return confirmStolenByOwner(pin)
    }

    /**
     * Big Red Button Action 2:
     * Reads Firestore owner-scoped telemetry via SecureRecoveryBridge to retrieve
     * verified last known lat, lng, time, battery, and tamper indicators.
     */
    suspend fun trackStolenPhone(targetDeviceId: String): Map<String, Any?>? = withContext(Dispatchers.IO) {
        val cleanId = targetDeviceId.trim()
        val result = container.secureRecoveryBridge.fetchAuthorizedDeviceRecoveryTelemetry(cleanId)
        if (result.isSuccess) {
            val telemetry = result.getOrNull()
            if (telemetry != null) {
                _uiState.update {
                    it.copy(
                        selectedRemoteDeviceId = telemetry.deviceId,
                        remoteTrackedTelemetry = telemetry,
                        remoteTrackingError = null
                    )
                }
                val validLat = telemetry.lastKnownLat?.takeIf { lat ->
                    LocationBreadcrumbPolicy.isValidCoordinate(lat, telemetry.lastKnownLng)
                }
                val validLng = telemetry.lastKnownLng?.takeIf { lng ->
                    LocationBreadcrumbPolicy.isValidCoordinate(telemetry.lastKnownLat, lng)
                }
                mapOf(
                    "deviceId" to telemetry.deviceId,
                    "deviceName" to telemetry.deviceName.ifBlank { telemetry.deviceId },
                    "lat" to validLat,
                    "lng" to validLng,
                    "accuracy" to telemetry.locationAccuracy,
                    "altitude" to telemetry.altitude,
                    "speed" to telemetry.speed,
                    "bearing" to telemetry.bearing,
                    "isFreshFix" to telemetry.isFreshFix,
                    "locationSource" to telemetry.locationSource,
                    "isGpsAvailable" to telemetry.isGpsAvailable,
                    "time" to telemetry.timestamp,
                    "lastSyncedAt" to telemetry.lastSyncedAt,
                    "syncStatus" to telemetry.syncStatus,
                    "battery" to "${telemetry.batteryLevel}%",
                    "status" to telemetry.securityState,
                    "isCharging" to telemetry.isCharging,
                    "connectivity" to telemetry.connectivityStatus,
                    "recentTamper" to telemetry.recentTamperEvents.joinToString("; "),
                    "historyPoints" to telemetry.locationHistory
                )
            } else {
                null
            }
        } else {
            null
        }
    }

    /**
     * Retrieves full typed recovery telemetry including location history
     */
    suspend fun trackDeviceRecoveryTelemetry(targetDeviceId: String): Result<com.example.domain.recovery.RemoteRecoveryTelemetry> =
        container.secureRecoveryBridge.fetchAuthorizedDeviceRecoveryTelemetry(targetDeviceId.trim())

    fun selectLocalHistoryDate(dayMillis: Long) {
        val start = dayStartMillis(dayMillis)
        selectedLocalDateFlow.value = start
        _uiState.update { it.copy(selectedHistoryDateMillis = start) }
    }

    fun startObservingRemoteDevice(targetDeviceId: String) {
        val cleanId = targetDeviceId.trim()
        if (cleanId.isBlank()) return

        remoteTelemetryJob?.cancel()
        remoteBreadcrumbsJob?.cancel()

        _uiState.update {
            it.copy(
                selectedRemoteDeviceId = cleanId,
                isObservingRemoteDevice = true,
                remoteTrackingError = null
            )
        }

        remoteTelemetryJob = viewModelScope.launch {
            container.secureRecoveryBridge.observeAuthorizedDeviceTelemetry(cleanId).collectLatest { telemetry ->
                _uiState.update { current ->
                    current.copy(
                        remoteTrackedTelemetry = telemetry,
                        isObservingRemoteDevice = false,
                        remoteTrackingError = if (telemetry == null) {
                            if (current.currentLanguage.equals("en", ignoreCase = true)) {
                                "No verified telemetry found for '$cleanId' under your account."
                            } else {
                                "Aucune télémétrie vérifiée trouvée pour '$cleanId' sous votre compte."
                            }
                        } else null
                    )
                }
            }
        }

        startRemoteBreadcrumbsListener(cleanId, _uiState.value.remoteSelectedDateMillis)
    }

    fun selectRemoteHistoryDate(dayMillis: Long) {
        val start = dayStartMillis(dayMillis)
        _uiState.update { it.copy(remoteSelectedDateMillis = start) }
        val activeTarget = _uiState.value.selectedRemoteDeviceId
        if (!activeTarget.isNullOrBlank()) {
            startRemoteBreadcrumbsListener(activeTarget, start)
        }
    }

    private fun startRemoteBreadcrumbsListener(targetDeviceId: String, dayMillis: Long) {
        val (dayStart, dayEnd) = dayBoundsMillis(dayMillis)
        remoteBreadcrumbsJob?.cancel()
        remoteBreadcrumbsJob = viewModelScope.launch {
            container.secureRecoveryBridge.observeAuthorizedDeviceBreadcrumbs(
                targetDeviceId = targetDeviceId,
                dayStartMs = dayStart,
                dayEndMs = dayEnd
            ).collectLatest { points ->
                _uiState.update { it.copy(remoteDeviceBreadcrumbs = points) }
            }
        }
    }

    fun stopObservingRemoteDevice() {
        remoteTelemetryJob?.cancel()
        remoteBreadcrumbsJob?.cancel()
        _uiState.update {
            it.copy(
                isObservingRemoteDevice = false,
                remoteTrackingError = null
            )
        }
    }

    private fun restorePersistedStolenTracking() {
        viewModelScope.launch {
            try {
                val isTrackingActive = DeviceSettingsRepository.isStolenTrackingActive(container.context)
                val persistedState = DeviceSettingsRepository.getPersistedSecurityStateName(container.context)

                if (!isTrackingActive) {
                    if (persistedState == SecurityState.RECOVERED.name &&
                        container.securityBrain.currentState.value == SecurityState.NORMAL
                    ) {
                        container.securityBrain.requestMarkRecovered(
                            authorizationToken = "RESTORED_RECOVERED_SESSION_${UUID.randomUUID()}"
                        )
                        container.securityStateManager.markRecovered("Restored persisted RECOVERED")
                        _uiState.update { it.copy(isForegroundServiceActive = false) }
                    }
                    return@launch
                }

                if (persistedState == SecurityState.RECOVERY_MODE.name) {
                    if (container.securityBrain.currentState.value != SecurityState.RECOVERY_MODE) {
                        container.securityBrain.requestActivateRecoveryMode(
                            authorizationToken = "RESTORED_RECOVERY_SESSION_${UUID.randomUUID()}"
                        )
                        container.securityStateManager.activateRecoveryMode("Restored persisted RECOVERY_MODE")
                    }
                } else {
                    if (container.securityBrain.currentState.value == SecurityState.NORMAL) {
                        container.securityBrain.requestDeclareStolen(
                            isOwnerConfirmed = true,
                            authorizationToken = "RESTORED_STOLEN_SESSION_${UUID.randomUUID()}"
                        )
                        container.securityStateManager.confirmStolenByOwner("Restored persisted STOLEN_MODE")
                    }
                }

                container.locationEngine.applyMonitoringInterval(RecoveryMonitoringService.DEFAULT_THEFT_INTERVAL_MILLIS)
                container.locationEngine.requestFreshLocationFix()
                LocationSyncWorker.enqueue(container.context)
                val savedContact = container.deviceSettingsRepository.recoveryContactPhone.value
                    .takeIf { it.isNotBlank() }
                val startedRes = RecoveryMonitoringService.startServiceWithResult(
                    context = container.context,
                    contactNumber = savedContact,
                    intervalMillis = RecoveryMonitoringService.DEFAULT_THEFT_INTERVAL_MILLIS
                )
                when (startedRes) {
                    is com.example.domain.service.ServiceStartResult.Started -> {
                        _uiState.update { it.copy(isForegroundServiceActive = true, lastForegroundServiceError = null) }
                    }
                    is com.example.domain.service.ServiceStartResult.BlockedPermission -> {
                        _uiState.update { it.copy(isForegroundServiceActive = false, lastForegroundServiceError = startedRes.reason) }
                    }
                    is com.example.domain.service.ServiceStartResult.BlockedBackgroundRestriction -> {
                        _uiState.update { it.copy(isForegroundServiceActive = false, lastForegroundServiceError = startedRes.reason) }
                    }
                    is com.example.domain.service.ServiceStartResult.Failed -> {
                        _uiState.update { it.copy(isForegroundServiceActive = false, lastForegroundServiceError = startedRes.reason) }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("InkingiViewModel", "Could not restore persisted tracking state: ${e.message}")
            }
        }
    }

    private fun dayStartMillis(epochMs: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = epochMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun dayBoundsMillis(dayStartMs: Long): Pair<Long, Long> {
        val start = dayStartMillis(dayStartMs)
        val end = Calendar.getInstance().apply {
            timeInMillis = start
            set(Calendar.HOUR_OF_DAY, 23)
            set(Calendar.MINUTE, 59)
            set(Calendar.SECOND, 59)
            set(Calendar.MILLISECOND, 999)
        }.timeInMillis
        return start to end
    }

    /**
     * Dispatches an authenticated, owner-scoped remote recovery command (e.g. LOCK_DEVICE, TRIGGER_ALARM)
     * with anti-replay protection and 10-minute validity window.
     */
    suspend fun sendRemoteRecoveryCommand(
        targetDeviceId: String,
        commandType: com.example.core.model.CommandType,
        pin: String
    ): Result<String> {
        if (!verifyMasterPin(pin)) {
            return Result.failure(SecurityException("Code PIN maître incorrect. Action refusée."))
        }
        return container.secureRecoveryBridge.dispatchRemoteCommandToDevice(
            targetDeviceId = targetDeviceId.trim(),
            commandType = commandType
        )
    }

    /**
     * Configures a lost-phone recovery lock screen message.
     * Uses Android Device Owner Lock Screen Info if provisioned, or displays a high-priority
     * public lock-screen ongoing notification fallback. Never displays passwords, PINs or secrets.
     */
    fun configureLockScreenMessage(message: String, contactPhone: String): String {
        val sanitizedMsg = message.trim().take(150)
        val sanitizedPhone = contactPhone.trim().take(30)
        val isEn = _uiState.value.currentLanguage.equals("en", ignoreCase = true)

        if (sanitizedMsg.isBlank()) {
            return if (isEn) {
                "Error: Please enter a recovery message."
            } else {
                "Erreur : Veuillez saisir un message de récupération."
            }
        }
        if (sanitizedPhone.isNotBlank() &&
            !com.example.data.repository.DeviceSettingsRepository.isValidRecoveryPhone(sanitizedPhone)
        ) {
            return if (isEn) {
                "Error: Invalid emergency contact phone number."
            } else {
                "Erreur : Numéro de téléphone de contact d'urgence invalide."
            }
        }

        val saved = container.deviceSettingsRepository.saveLockScreenRecoveryConfig(
            sanitizedMsg,
            sanitizedPhone
        )
        if (!saved) {
            return if (isEn) {
                "Error: Could not save lock-screen recovery configuration."
            } else {
                "Erreur : Impossible d'enregistrer la configuration de récupération."
            }
        }

        val dpmText = if (sanitizedPhone.isNotBlank()) {
            "$sanitizedMsg — Contact: $sanitizedPhone"
        } else {
            sanitizedMsg
        }
        val appliedViaDpm = container.deviceLockManager.setLockScreenRecoveryMessage(dpmText)
        // Also deploy persistent public notification fallback so it appears on any standard Android lock screen
        container.alertManager.showLockScreenRecoveryNotification(sanitizedMsg, sanitizedPhone)

        if (_uiState.value.isForegroundServiceActive) {
            try {
                RecoveryMonitoringService.startService(
                    context = container.context,
                    contactNumber = sanitizedPhone.ifBlank { null },
                    intervalMillis = _uiState.value.monitoringStrategy.locationIntervalMillis
                )
            } catch (_: Exception) {}
        }

        return if (appliedViaDpm) {
            if (isEn) {
                "Message saved and set directly on the hardware lock screen (Device Policy)."
            } else {
                "Message enregistré et défini directement sur l'écran de verrouillage matériel (Device Policy)."
            }
        } else {
            if (isEn) {
                "Message saved and displayed as a persistent priority lock-screen notification."
            } else {
                "Message enregistré et affiché en notification permanente prioritaire sur l'écran de verrouillage."
            }
        }
    }

    // Behavioral Learning Baseline Controls
    fun pauseBaselineLearning() {
        container.deviceSettingsRepository.setLearningPaused(true)
        container.securityBrain.pauseBaselineLearning()
        container.behaviorLearningManager.setLearningPaused(true)
        _uiState.update { it.copy(isLearningPaused = true) }
        scheduleTelemetryEvaluation(debounceMs = 0L)
    }

    fun resumeBaselineLearning() {
        container.deviceSettingsRepository.setLearningPaused(false)
        container.securityBrain.resumeBaselineLearning()
        container.behaviorLearningManager.setLearningPaused(false)
        _uiState.update { it.copy(isLearningPaused = false) }
        scheduleTelemetryEvaluation(debounceMs = 0L)
    }

    fun resetBaselineLearning() {
        container.securityBrain.resetBaselineLearning()
        viewModelScope.launch {
            container.behaviorLearningManager.resetBaseline()
            scheduleTelemetryEvaluation(debounceMs = 0L)
        }
    }

    // Diagnostic simulators
    fun simulateKineticAnomaly() {
        container.sensorEngine.triggerSimulatedKineticAnomaly()
        scheduleTelemetryEvaluation(debounceMs = 0L)
    }

    fun simulateSimAlteration() {
        container.simEngine.triggerSimulatedSimAlteration()
        scheduleTelemetryEvaluation(debounceMs = 0L)
    }

    fun simulateDisplacementOutsideZone() {
        container.locationEngine.setSimulatedLocation(-1.8500, 30.2000, insideZone = false)
        scheduleTelemetryEvaluation(debounceMs = 0L)
    }

    fun simulateRebootAnomaly() {
        container.deviceHealthEngine.triggerSimulatedRebootAnomaly()
        scheduleTelemetryEvaluation(debounceMs = 0L)
    }

    fun simulateNetworkSevered() {
        container.networkEngine.triggerSimulatedNetworkDrop()
        scheduleTelemetryEvaluation(debounceMs = 0L)
    }

    fun simulateBatteryPercentage(percentage: Int, isCharging: Boolean = false) {
        container.deviceHealthEngine.setSimulatedBatteryPercentage(percentage, isCharging)
        _uiState.update { it.copy(deviceHealth = container.deviceHealthEngine.healthInfo.value) }
    }

    fun resetDiagnosticSimulations() {
        container.sensorEngine.clearMotionEvent()
        container.simEngine.clearSimAlteration()
        container.deviceHealthEngine.clearRebootAnomaly()
        container.networkEngine.restoreNetwork()
        container.locationEngine.startLocationUpdates()
        failedAuthCount = 0
        scheduleTelemetryEvaluation(debounceMs = 0L)
    }

    override fun onCleared() {
        super.onCleared()
        remoteDevicesJob?.cancel()
        remoteTelemetryJob?.cancel()
        remoteBreadcrumbsJob?.cancel()
        container.secureRecoveryBridge.stopRemoteCommandListener()
        container.sensorEngine.stopListening()
        container.locationEngine.stopLocationUpdates()
        container.networkEngine.cleanup()
        if (!DeviceSettingsRepository.isStolenTrackingActive(container.context)) {
            try {
                RecoveryMonitoringService.stopService(container.context)
            } catch (_: Exception) {}
        }
    }

    class Factory(private val container: InkingiContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return InkingiViewModel(container) as T
        }
    }
}
