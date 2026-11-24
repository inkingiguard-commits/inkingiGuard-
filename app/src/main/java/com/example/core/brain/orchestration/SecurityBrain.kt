package com.example.core.brain.orchestration

import com.example.core.brain.ai.AiAnalysisRequest
import com.example.core.brain.ai.AiAnalysisResponse
import com.example.core.brain.ai.AiOrchestrator
import com.example.core.brain.ai.AiProviderType
import com.example.core.brain.ai.DefaultAiOrchestrator
import com.example.core.brain.baseline.BaselineProfile
import com.example.core.brain.baseline.BehavioralAnomaly
import com.example.core.brain.baseline.BehavioralLearningEngine
import com.example.core.brain.baseline.BehavioralObservation
import com.example.core.brain.correlation.EventCorrelationEngine
import com.example.core.brain.gateways.CloudSyncGateway
import com.example.core.brain.gateways.EvidenceStorageGateway
import com.example.core.brain.gateways.GpsLocationGateway
import com.example.core.brain.gateways.MockCloudSyncGateway
import com.example.core.brain.gateways.MockEvidenceStorageGateway
import com.example.core.brain.gateways.MockGpsLocationGateway
import com.example.core.brain.gateways.MockNotificationAlertGateway
import com.example.core.brain.gateways.MockSensorTelemetryGateway
import com.example.core.brain.gateways.NotificationAlertGateway
import com.example.core.brain.gateways.SensorTelemetryGateway
import com.example.core.brain.model.ActionType
import com.example.core.brain.model.NormalizedSecurityEvent
import com.example.core.brain.model.OntologyDevice
import com.example.core.brain.model.OntologyIncident
import com.example.core.brain.model.OntologyOwner
import com.example.core.brain.model.PolicyDecision
import com.example.core.brain.model.PolicyEvaluationResult
import com.example.core.brain.model.ProposedAction
import com.example.core.brain.policy.SecurityPolicyEngine
import com.example.core.brain.risk.ConfigurableRiskScoringEngine
import com.example.core.brain.risk.DecayedRiskEvaluation
import com.example.core.model.CorrelationThreatState
import com.example.core.model.IncidentStatus
import com.example.core.model.SecurityState
import com.example.core.model.UserRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * InkingiGuard Security Brain.
 * Central intelligence core orchestrating event normalization, correlation,
 * deterministic risk scoring with evidence decay, policy validation,
 * behavioral baseline learning, and fail-closed incident response.
 */
class SecurityBrain(
    val device: OntologyDevice,
    val owner: OntologyOwner,
    val correlationEngine: EventCorrelationEngine = EventCorrelationEngine(),
    val riskScoringEngine: ConfigurableRiskScoringEngine = ConfigurableRiskScoringEngine(),
    val policyEngine: SecurityPolicyEngine = SecurityPolicyEngine(),
    val behavioralLearningEngine: BehavioralLearningEngine = BehavioralLearningEngine(),
    val aiOrchestrator: AiOrchestrator = DefaultAiOrchestrator(),
    val gpsGateway: GpsLocationGateway = MockGpsLocationGateway(),
    val sensorGateway: SensorTelemetryGateway = MockSensorTelemetryGateway(),
    val cloudSyncGateway: CloudSyncGateway = MockCloudSyncGateway(),
    val alertGateway: NotificationAlertGateway = MockNotificationAlertGateway(),
    val evidenceGateway: EvidenceStorageGateway = MockEvidenceStorageGateway()
) {

    private val _currentState = MutableStateFlow(device.securityState)
    val currentState: StateFlow<SecurityState> = _currentState.asStateFlow()

    private val _currentRiskEvaluation = MutableStateFlow(
        riskScoringEngine.evaluate(ConfigurableRiskScoringEngine.RawSignals())
    )
    val currentRiskEvaluation: StateFlow<DecayedRiskEvaluation> = _currentRiskEvaluation.asStateFlow()

    private val _baselineProfile = MutableStateFlow(behavioralLearningEngine.getProfile())
    val baselineProfile: StateFlow<BaselineProfile> = _baselineProfile.asStateFlow()

    private val _activeIncident = MutableStateFlow<OntologyIncident?>(null)
    val activeIncident: StateFlow<OntologyIncident?> = _activeIncident.asStateFlow()

    private val _currentThreatState = MutableStateFlow(CorrelationThreatState.NORMAL)
    val currentThreatState: StateFlow<CorrelationThreatState> = _currentThreatState.asStateFlow()

    private val _contributingSignals = MutableStateFlow<List<String>>(emptyList())
    val contributingSignals: StateFlow<List<String>> = _contributingSignals.asStateFlow()

    data class IngestionOutcome(
        val event: NormalizedSecurityEvent,
        val isDuplicateSuppressed: Boolean,
        val updatedRiskEvaluation: DecayedRiskEvaluation,
        val affectedIncident: OntologyIncident?,
        val policyDecision: PolicyEvaluationResult
    )

    /**
     * Ingests a normalized security event through correlation, risk decay, and policy checks.
     */
    suspend fun ingestSecurityEvent(
        event: NormalizedSecurityEvent,
        signals: ConfigurableRiskScoringEngine.RawSignals,
        now: Long = System.currentTimeMillis()
    ): IngestionOutcome {
        // 1. Calculate decayed risk evaluation (authoritative scoring)
        val riskEval = riskScoringEngine.evaluate(signals, now)
        _currentRiskEvaluation.value = riskEval

        val distinctFactors = riskEval.evidenceItems.map { it.factorKey }.distinct()
        val distinctFactorCount = distinctFactors.size

        _contributingSignals.value = if (riskEval.evidenceItems.isEmpty()) {
            emptyList()
        } else {
            riskEval.evidenceItems.map { it.description }
        }

        _currentThreatState.value = when {
            signals.userRole != UserRole.OWNER -> {
                when {
                    riskEval.totalScore >= 70 -> CorrelationThreatState.SUSPICIOUS
                    riskEval.totalScore > 0 -> CorrelationThreatState.LOW_RISK
                    else -> CorrelationThreatState.NORMAL
                }
            }
            riskEval.totalScore >= 70 && distinctFactorCount >= 3 -> CorrelationThreatState.THEFT_CONFIRMATION_REQUIRED
            riskEval.totalScore >= 70 -> CorrelationThreatState.HIGH_RISK
            riskEval.totalScore >= 30 || distinctFactorCount in 1..2 -> CorrelationThreatState.SUSPICIOUS
            riskEval.totalScore > 0 -> CorrelationThreatState.LOW_RISK
            else -> CorrelationThreatState.NORMAL
        }

        // 2. Correlate through EventCorrelationEngine (suppressing duplicate events & duplicate incidents)
        val correlation = correlationEngine.processEvent(event, riskEval.totalScore)
        if (correlation.correlatedIncident != null) {
            _activeIncident.value = correlation.correlatedIncident
            cloudSyncGateway.pushIncidentUpdate(correlation.correlatedIncident)
        } else if (riskEval.totalScore < 30 && _activeIncident.value?.status == IncidentStatus.INVESTIGATING) {
            val resolved = correlationEngine.resolveIncident(_activeIncident.value!!.incidentId, now)
            if (resolved != null) {
                cloudSyncGateway.pushIncidentUpdate(resolved)
            }
            _activeIncident.value = null
        }

        if (!correlation.isDuplicateEvent) {
            cloudSyncGateway.pushSecurityEvent(event)
        }

        // 3. Evaluate state change policy (require >= 2 independent signals and score >= 80 for SUSPECTED_LOST; preserve GUEST/TRUSTED safety)
        val targetState = when {
            _currentState.value == SecurityState.STOLEN_MODE ||
            _currentState.value == SecurityState.RECOVERY_MODE ||
            _currentState.value == SecurityState.OWNER_CONFIRMED_STOLEN -> _currentState.value
            signals.userRole != UserRole.OWNER -> {
                if (riskEval.totalScore >= 50) SecurityState.WARNING else SecurityState.NORMAL
            }
            riskEval.totalScore >= 80 && distinctFactorCount >= 2 -> SecurityState.SUSPECTED_LOST
            riskEval.totalScore >= 50 -> SecurityState.DANGER
            riskEval.totalScore >= 30 -> SecurityState.WARNING
            else -> SecurityState.NORMAL
        }

        val proposedAction = ProposedAction(
            actionType = ActionType.CHANGE_SECURITY_STATE,
            deviceId = device.deviceId,
            requestingActor = "SecurityBrainCorrelation",
            targetState = targetState,
            currentRiskScore = riskEval.totalScore,
            timestamp = now
        )

        val policyResult = policyEngine.evaluateAction(proposedAction)
        if (policyResult.decision == PolicyDecision.APPROVED && targetState != _currentState.value) {
            _currentState.value = targetState
            if (targetState == SecurityState.DANGER || targetState == SecurityState.SUSPECTED_LOST) {
                alertGateway.sendEmergencyAlert(
                    title = "Risk Escalation: ${targetState.name}",
                    message = "Score: ${riskEval.totalScore}/100. Factors: ${event.title}",
                    isCritical = true
                )
            }
        }

        return IngestionOutcome(
            event = event,
            isDuplicateSuppressed = correlation.isDuplicateEvent,
            updatedRiskEvaluation = riskEval,
            affectedIncident = _activeIncident.value,
            policyDecision = policyResult
        )
    }

    /**
     * Owner confirmation flow for declaring a device stolen.
     * Enforces two-phase verification: fails closed if owner confirmation is absent.
     */
    suspend fun requestDeclareStolen(
        isOwnerConfirmed: Boolean,
        authorizationToken: String,
        persistTransition: (suspend () -> Boolean)? = null
    ): PolicyEvaluationResult {
        val proposed = ProposedAction(
            actionType = ActionType.ACTIVATE_STOLEN_MODE,
            deviceId = device.deviceId,
            requestingActor = owner.email,
            authorizationToken = authorizationToken,
            isOwnerExplicitlyConfirmed = isOwnerConfirmed,
            currentRiskScore = _currentRiskEvaluation.value.totalScore
        )

        val policyCheck = policyEngine.evaluateAction(proposed)
        if (policyCheck.decision == PolicyDecision.APPROVED) {
            val persisted = try {
                persistTransition?.invoke() ?: true
            } catch (_: Exception) {
                false
            }
            if (!persisted) {
                return PolicyEvaluationResult(
                    decision = PolicyDecision.REJECTED,
                    explanation = "STOLEN_MODE transition aborted: tracking persistence failed.",
                    violations = listOf("PERSISTENCE_FAILED")
                )
            }

            if (_currentState.value != SecurityState.STOLEN_MODE) {
                _currentState.value = SecurityState.OWNER_CONFIRMED_STOLEN
            }
            _currentState.value = SecurityState.STOLEN_MODE

            alertGateway.sendEmergencyAlert(
                title = "STOLEN MODE ACTIVE",
                message = "Theft confirmed by verified owner. High frequency telemetry engaged.",
                isCritical = true
            )

            evidenceGateway.storeEncryptedPayload(
                key = "THEFT_TOKEN_${device.deviceId}",
                payload = "STOLEN:${System.currentTimeMillis()}:$authorizationToken"
            )
        }
        return policyCheck
    }

    /**
     * Authenticated disarm request.
     */
    suspend fun requestDisarm(authorizationToken: String): PolicyEvaluationResult {
        val proposed = ProposedAction(
            actionType = ActionType.DISARM_SYSTEM,
            deviceId = device.deviceId,
            requestingActor = owner.email,
            authorizationToken = authorizationToken,
            currentRiskScore = _currentRiskEvaluation.value.totalScore
        )

        val policyCheck = policyEngine.evaluateAction(proposed)
        if (policyCheck.decision == PolicyDecision.APPROVED) {
            _currentState.value = SecurityState.NORMAL
            _currentThreatState.value = CorrelationThreatState.NORMAL
            _contributingSignals.value = emptyList()
            _activeIncident.value?.let {
                val resolved = correlationEngine.resolveIncident(it.incidentId)
                if (resolved != null) {
                    cloudSyncGateway.pushIncidentUpdate(resolved)
                }
            }
            _activeIncident.value = null
        }
        return policyCheck
    }

    /**
     * Arm system to defensive WARNING / armed state.
     */
    suspend fun armHighAlert(): PolicyEvaluationResult {
        val proposed = ProposedAction(
            actionType = ActionType.CHANGE_SECURITY_STATE,
            deviceId = device.deviceId,
            requestingActor = owner.email,
            targetState = SecurityState.WARNING,
            currentRiskScore = _currentRiskEvaluation.value.totalScore
        )
        val policyCheck = policyEngine.evaluateAction(proposed)
        if (policyCheck.decision == PolicyDecision.APPROVED) {
            _currentState.value = SecurityState.WARNING
        }
        return policyCheck
    }

    /**
     * Activate recovery mode with authenticated master token.
     */
    suspend fun requestActivateRecoveryMode(
        authorizationToken: String,
        persistTransition: (suspend () -> Boolean)? = null
    ): PolicyEvaluationResult {
        val proposed = ProposedAction(
            actionType = ActionType.CHANGE_SECURITY_STATE,
            deviceId = device.deviceId,
            requestingActor = owner.email,
            targetState = SecurityState.RECOVERY_MODE,
            authorizationToken = authorizationToken,
            currentRiskScore = _currentRiskEvaluation.value.totalScore
        )
        val policyCheck = policyEngine.evaluateAction(proposed)
        if (policyCheck.decision == PolicyDecision.APPROVED) {
            val persisted = try {
                persistTransition?.invoke() ?: true
            } catch (_: Exception) {
                false
            }
            if (!persisted) {
                return PolicyEvaluationResult(
                    decision = PolicyDecision.REJECTED,
                    explanation = "RECOVERY_MODE transition aborted: state persistence failed.",
                    violations = listOf("PERSISTENCE_FAILED")
                )
            }
            _currentState.value = SecurityState.RECOVERY_MODE
        }
        return policyCheck
    }

    /**
     * Mark device recovered.
     */
    suspend fun requestMarkRecovered(
        authorizationToken: String,
        persistTransition: (suspend () -> Boolean)? = null
    ): PolicyEvaluationResult {
        val proposed = ProposedAction(
            actionType = ActionType.MARK_RECOVERED,
            deviceId = device.deviceId,
            requestingActor = owner.email,
            authorizationToken = authorizationToken,
            currentRiskScore = _currentRiskEvaluation.value.totalScore
        )

        val policyCheck = policyEngine.evaluateAction(proposed)
        if (policyCheck.decision == PolicyDecision.APPROVED) {
            val persisted = try {
                persistTransition?.invoke() ?: true
            } catch (_: Exception) {
                false
            }
            if (!persisted) {
                return PolicyEvaluationResult(
                    decision = PolicyDecision.REJECTED,
                    explanation = "MARK_RECOVERED transition aborted: recovered status persistence failed.",
                    violations = listOf("PERSISTENCE_FAILED")
                )
            }
            _currentState.value = SecurityState.RECOVERED
            _currentThreatState.value = CorrelationThreatState.NORMAL
            _contributingSignals.value = emptyList()
            _activeIncident.value?.let {
                val resolved = correlationEngine.resolveIncident(it.incidentId)
                if (resolved != null) {
                    cloudSyncGateway.pushIncidentUpdate(resolved)
                }
            }
            _activeIncident.value = null
        }
        return policyCheck
    }

    /**
     * AI Advisory request.
     */
    suspend fun requestAiAdvisory(provider: AiProviderType = AiProviderType.GEMINI): AiAnalysisResponse {
        val factors = _currentRiskEvaluation.value.evidenceItems.map { it.description }
        val req = AiAnalysisRequest(
            incidentId = _activeIncident.value?.incidentId,
            deviceId = device.deviceId,
            riskScore = _currentRiskEvaluation.value.totalScore,
            anomalousFactors = factors,
            systemTelemetrySummary = "Current state: ${_currentState.value.name}. Incidents active: ${_activeIncident.value != null}",
            requestedProvider = provider
        )
        return aiOrchestrator.orchestrateAnalysis(req)
    }

    /**
     * Evaluates behavioral deviations and records the observation in BehavioralLearningEngine
     * WITHOUT triggering a separate risk evaluation overwrite (used by the unified telemetry pipeline).
     */
    fun evaluateAndRecordBehavioralObservation(
        observation: BehavioralObservation,
        now: Long = System.currentTimeMillis()
    ): List<BehavioralAnomaly> {
        val anomalies = behavioralLearningEngine.evaluateDeviations(observation, now)
        behavioralLearningEngine.recordObservation(observation)
        _baselineProfile.value = behavioralLearningEngine.getProfile()
        return anomalies
    }

    /**
     * Processes behavioral observation:
     * 1. Evaluates deviations against established baseline profile.
     * 2. Ingests detected anomalies into the primary SecurityBrain event ingestion pipeline.
     * 3. Records observation into local baseline model (privacy-preserving statistical metrics).
     */
    suspend fun ingestBehavioralObservation(
        observation: BehavioralObservation,
        now: Long = System.currentTimeMillis()
    ): List<IngestionOutcome> {
        val anomalies = behavioralLearningEngine.evaluateDeviations(observation, now)
        val outcomes = mutableListOf<IngestionOutcome>()

        if (anomalies.isNotEmpty()) {
            val hasSim = anomalies.any { it.factorKey == "BEHAVIORAL_SIM_DEVIATION" }
            val authAttempts = if (anomalies.any { it.factorKey == "BEHAVIORAL_AUTH_SPIKE" }) observation.failedAuthAttempts else 0
            val isOutside = anomalies.any { it.factorKey == "BEHAVIORAL_LOCATION_DISPERSION" }
            val isKinetic = anomalies.any { it.factorKey == "BEHAVIORAL_KINETIC_DISRUPTION" }
            val isReboot = anomalies.any { it.factorKey == "BEHAVIORAL_REBOOT_ANOMALY" }
            val isNetwork = anomalies.any { it.factorKey == "BEHAVIORAL_RADIO_SEVERED" || it.factorKey == "BEHAVIORAL_NETWORK_DEVIATION" }

            val combinedSignals = ConfigurableRiskScoringEngine.RawSignals(
                isSimChanged = hasSim,
                failedAuthAttempts = authAttempts,
                isOutsideTrustedZone = isOutside,
                isUnusualMovementDetected = isKinetic,
                isUnexpectedRestartDetected = isReboot,
                hasCorrelatedSensorAnomalies = isKinetic && isOutside,
                hasNetworkAnomaly = isNetwork,
                userRole = observation.role
            )

            for (anomaly in anomalies) {
                val normalizedEvent = anomaly.toNormalizedEvent(device.deviceId, owner.ownerId)
                val outcome = ingestSecurityEvent(normalizedEvent, combinedSignals, now)
                outcomes.add(outcome)
            }
        }

        // Record observation into local baseline model
        behavioralLearningEngine.recordObservation(observation)
        _baselineProfile.value = behavioralLearningEngine.getProfile()

        return outcomes
    }

    fun pauseBaselineLearning() {
        behavioralLearningEngine.pause()
        _baselineProfile.value = behavioralLearningEngine.getProfile()
    }

    fun resumeBaselineLearning() {
        behavioralLearningEngine.resume()
        _baselineProfile.value = behavioralLearningEngine.getProfile()
    }

    fun resetBaselineLearning() {
        behavioralLearningEngine.reset()
        _baselineProfile.value = behavioralLearningEngine.getProfile()
    }
}
