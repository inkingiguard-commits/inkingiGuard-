package com.example

import com.example.core.brain.ai.AiAnalysisRequest
import com.example.core.brain.ai.AiProviderType
import com.example.core.brain.ai.DefaultAiOrchestrator
import com.example.core.brain.correlation.EventCorrelationEngine
import com.example.core.brain.gateways.MockCloudSyncGateway
import com.example.core.brain.gateways.MockNotificationAlertGateway
import com.example.core.brain.model.ActionType
import com.example.core.brain.model.EventCategory
import com.example.core.brain.model.NormalizedSecurityEvent
import com.example.core.brain.model.OntologyDevice
import com.example.core.brain.model.OntologyOwner
import com.example.core.brain.model.PolicyDecision
import com.example.core.brain.model.ProposedAction
import com.example.core.brain.orchestration.SecurityBrain
import com.example.core.brain.policy.SecurityPolicyEngine
import com.example.core.brain.risk.ConfigurableRiskScoringEngine
import com.example.core.brain.risk.RiskWeightsConfig
import com.example.core.model.EventSeverity
import com.example.core.model.IncidentSeverity
import com.example.core.model.RiskLevel
import com.example.core.model.SecurityState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityBrainUnitTest {

    private lateinit var correlationEngine: EventCorrelationEngine
    private lateinit var riskEngine: ConfigurableRiskScoringEngine
    private lateinit var policyEngine: SecurityPolicyEngine
    private lateinit var securityBrain: SecurityBrain
    private lateinit var mockCloudGateway: MockCloudSyncGateway
    private lateinit var mockAlertGateway: MockNotificationAlertGateway

    private val testDevice = OntologyDevice(
        deviceId = "DEV-INKINGI-01",
        ownerId = "owner@inkingiguard.com",
        name = "Pixel 8 Pro Test Node",
        model = "Pixel 8 Pro",
        osVersion = "Android 14"
    )

    private val testOwner = OntologyOwner(
        ownerId = "owner@inkingiguard.com",
        email = "inkingiguard@gmail.com",
        displayName = "Primary Security Officer",
        enrolledDeviceIds = listOf("DEV-INKINGI-01")
    )

    @Before
    fun setUp() {
        correlationEngine = EventCorrelationEngine(correlationWindowMillis = 15 * 60 * 1000L)
        riskEngine = ConfigurableRiskScoringEngine()
        policyEngine = SecurityPolicyEngine()
        mockCloudGateway = MockCloudSyncGateway()
        mockAlertGateway = MockNotificationAlertGateway()

        securityBrain = SecurityBrain(
            device = testDevice,
            owner = testOwner,
            correlationEngine = correlationEngine,
            riskScoringEngine = riskEngine,
            policyEngine = policyEngine,
            cloudSyncGateway = mockCloudGateway,
            alertGateway = mockAlertGateway
        )
    }

    @Test
    fun testEventCorrelationSuppressesRapidBurstDuplicates() = runBlocking {
        val now = 1000000L
        val event1 = NormalizedSecurityEvent(
            eventId = "EVT-1",
            deviceId = "DEV-INKINGI-01",
            ownerId = "owner@inkingiguard.com",
            category = EventCategory.KINETIC_MOTION,
            source = "AccelerometerSensor",
            timestamp = now,
            severity = EventSeverity.WARNING,
            baseRiskImpact = 15,
            title = "Sudden High Acceleration",
            details = "Impact magnitude 19.8 m/s^2"
        )

        // First event
        val res1 = correlationEngine.processEvent(event1, currentRiskScore = 35)
        assertFalse(res1.isDuplicateEvent)
        assertTrue(res1.isNewIncidentCreated)
        assertNotNull(res1.correlatedIncident)

        // Exact identical burst event within same time bucket
        val event2 = event1.copy(eventId = "EVT-2", timestamp = now + 500)
        val res2 = correlationEngine.processEvent(event2, currentRiskScore = 35)
        assertTrue(res2.isDuplicateEvent)
    }

    @Test
    fun testEventCorrelationAppendsToExistingIncidentWithoutDuplication() = runBlocking {
        val baseTime = 2000000L
        val event1 = NormalizedSecurityEvent(
            eventId = "EVT-A",
            deviceId = "DEV-INKINGI-01",
            ownerId = "owner@inkingiguard.com",
            category = EventCategory.SIM_TELEPHONY,
            source = "TelephonyManager",
            timestamp = baseTime,
            severity = EventSeverity.CRITICAL,
            baseRiskImpact = 30,
            title = "SIM Card Removed",
            details = "Carrier altered"
        )

        val res1 = correlationEngine.processEvent(event1, currentRiskScore = 30)
        assertFalse(res1.isDuplicateEvent)
        assertTrue(res1.isNewIncidentCreated)
        val incidentId = res1.correlatedIncident!!.incidentId

        // Second distinct event 2 minutes later
        val event2 = NormalizedSecurityEvent(
            eventId = "EVT-B",
            deviceId = "DEV-INKINGI-01",
            ownerId = "owner@inkingiguard.com",
            category = EventCategory.GEOLOCATION_DISPLACEMENT,
            source = "LocationManager",
            timestamp = baseTime + (2 * 60 * 1000L),
            severity = EventSeverity.WARNING,
            baseRiskImpact = 20,
            title = "Displaced Outside Trusted Zone",
            details = "Distance 1200m from perimeter"
        )

        val res2 = correlationEngine.processEvent(event2, currentRiskScore = 50)
        assertFalse(res2.isDuplicateEvent)
        // Must NOT create a new incident; must append to existing!
        assertFalse(res2.isNewIncidentCreated)
        assertEquals(incidentId, res2.correlatedIncident!!.incidentId)
        assertEquals(2, res2.correlatedIncident!!.associatedEvents.size)
        assertEquals(IncidentSeverity.MEDIUM, res2.correlatedIncident!!.severity)
    }

    @Test
    fun testConfigurableRiskScoringWithEvidenceDecay() {
        val now = 10000000L
        val halfLife = 30 * 60 * 1000L // 30 minutes
        val customWeights = RiskWeightsConfig(
            simChangeWeight = 30,
            unusualMovementWeight = 20,
            evidenceHalfLifeMillis = halfLife
        )
        val scoringEngine = ConfigurableRiskScoringEngine(customWeights)

        // Fresh event (< 2 minutes old) gets 100% weight
        val freshSignals = ConfigurableRiskScoringEngine.RawSignals(
            isSimChanged = true,
            simChangeTimestamp = now - (60 * 1000L) // 1 min ago
        )
        val freshEval = scoringEngine.evaluate(freshSignals, now)
        assertEquals(30, freshEval.totalScore)
        assertEquals(RiskLevel.MEDIUM, freshEval.riskLevel)
        assertEquals(1.0f, freshEval.evidenceItems[0].decayFactor, 0.01f)

        // Stale event at exactly 1 half-life (30 minutes ago) decays to ~50%
        val decayedSignals = ConfigurableRiskScoringEngine.RawSignals(
            isSimChanged = true,
            simChangeTimestamp = now - halfLife
        )
        val decayedEval = scoringEngine.evaluate(decayedSignals, now)
        assertEquals(15, decayedEval.totalScore) // 30 * 0.5 = 15
        assertEquals(0.5f, decayedEval.evidenceItems[0].decayFactor, 0.05f)
        assertEquals(RiskLevel.LOW, decayedEval.riskLevel)
        assertTrue(decayedEval.explanationAuditTrail[0].contains("decay"))
    }

    @Test
    fun testSecurityPolicyEngineEnforcesFailClosedRules() {
        val engine = SecurityPolicyEngine()

        // Rule 1: Cannot activate Stolen Mode without explicit owner confirmation
        val stolenActionUnconfirmed = ProposedAction(
            actionType = ActionType.ACTIVATE_STOLEN_MODE,
            deviceId = "DEV-INKINGI-01",
            requestingActor = "AutomatedAiAgent",
            isOwnerExplicitlyConfirmed = false
        )
        val stolenResult = engine.evaluateAction(stolenActionUnconfirmed)
        assertEquals(PolicyDecision.CHALLENGE_REQUIRED, stolenResult.decision)

        // Rule 2: Cannot lock hardware screen without Device Admin privilege
        val lockActionNoPrivilege = ProposedAction(
            actionType = ActionType.LOCK_DEVICE_SCREEN,
            deviceId = "DEV-INKINGI-01",
            requestingActor = "OwnerConsole",
            hasDeviceAdminPrivilege = false,
            timestamp = System.currentTimeMillis() + 1000L
        )
        val lockResult = engine.evaluateAction(lockActionNoPrivilege)
        assertEquals(PolicyDecision.REJECTED, lockResult.decision)
        assertTrue(lockResult.violations.any { it.contains("Device Administrator") })

        // Rule 3: Destructive remote wipe placeholder fails closed
        val wipeAction = ProposedAction(
            actionType = ActionType.REQUEST_REMOTE_WIPE_PLACEHOLDER,
            deviceId = "DEV-INKINGI-01",
            requestingActor = "RootUser",
            timestamp = System.currentTimeMillis() + 2000L
        )
        val wipeResult = engine.evaluateAction(wipeAction)
        assertEquals(PolicyDecision.REJECTED, wipeResult.decision)
        assertTrue(wipeResult.violations.any { it.contains("strictly prohibited") })
    }

    @Test
    fun testSecurityBrainFullIncidentLifecycleAndOwnerConfirmation() = runBlocking {
        assertEquals(SecurityState.NORMAL, securityBrain.currentState.value)

        // 1. Ingest significant anomaly (SIM altered + Outside Zone -> 50 pts -> DANGER)
        val anomalyEvent = NormalizedSecurityEvent(
            eventId = "EVT-CRIT-1",
            deviceId = testDevice.deviceId,
            ownerId = testOwner.ownerId,
            category = EventCategory.SIM_TELEPHONY,
            source = "TelephonyEngine",
            severity = EventSeverity.CRITICAL,
            baseRiskImpact = 30,
            title = "SIM Card Altered Unexpectedly",
            details = "Carrier numeric altered from home network"
        )

        val signals = ConfigurableRiskScoringEngine.RawSignals(
            isSimChanged = true,
            isOutsideTrustedZone = true
        )

        val outcome = securityBrain.ingestSecurityEvent(anomalyEvent, signals)
        assertEquals(50, outcome.updatedRiskEvaluation.totalScore)
        assertEquals(SecurityState.DANGER, securityBrain.currentState.value)
        assertEquals(1, mockAlertGateway.dispatchedAlertsCount)
        assertEquals(1, mockCloudGateway.transmittedEventsCount)

        // 2. Attempting to declare stolen without owner confirmation is challenged
        val unconfirmedStolen = securityBrain.requestDeclareStolen(
            isOwnerConfirmed = false,
            authorizationToken = "UNAUTHORIZED_TOKEN"
        )
        assertEquals(PolicyDecision.CHALLENGE_REQUIRED, unconfirmedStolen.decision)
        // State must remain in DANGER, not STOLEN_MODE
        assertEquals(SecurityState.DANGER, securityBrain.currentState.value)

        // 3. Explicit verified owner confirmation successfully engages STOLEN_MODE
        val confirmedStolen = securityBrain.requestDeclareStolen(
            isOwnerConfirmed = true,
            authorizationToken = "VERIFIED_PIN_HASH_VALID"
        )
        assertEquals(PolicyDecision.APPROVED, confirmedStolen.decision)
        assertEquals(SecurityState.STOLEN_MODE, securityBrain.currentState.value)

        // 4. Mark Recovered
        val recoveredRes = securityBrain.requestMarkRecovered("VERIFIED_PIN_HASH_VALID")
        assertEquals(PolicyDecision.APPROVED, recoveredRes.decision)
        assertEquals(SecurityState.RECOVERED, securityBrain.currentState.value)

        // 5. Disarm to NORMAL
        val disarmRes = securityBrain.requestDisarm("VERIFIED_PIN_HASH_VALID")
        assertEquals(PolicyDecision.APPROVED, disarmRes.decision)
        assertEquals(SecurityState.NORMAL, securityBrain.currentState.value)
    }

    @Test
    fun testAiOrchestratorProvidesAdvisoryWithoutClaimingRemoteConnection() = runBlocking {
        val orchestrator = DefaultAiOrchestrator()
        assertEquals(3, orchestrator.getRegisteredProviders().size)
        assertTrue(orchestrator.getRegisteredProviders().contains(AiProviderType.GEMINI))
        assertTrue(orchestrator.getRegisteredProviders().contains(AiProviderType.CLAUDE))
        assertTrue(orchestrator.getRegisteredProviders().contains(AiProviderType.LLAMA))

        val req = AiAnalysisRequest(
            incidentId = "INC-TEST-01",
            deviceId = "DEV-INKINGI-01",
            riskScore = 75,
            anomalousFactors = listOf("SIM changed", "Outside zone"),
            systemTelemetrySummary = "Telemetry breach active",
            requestedProvider = AiProviderType.GEMINI
        )

        val geminiResp = orchestrator.orchestrateAnalysis(req)
        assertEquals(AiProviderType.GEMINI, geminiResp.providerType)
        assertFalse(geminiResp.isExternalModelConnected) // Explicitly verified not claiming external live connection
        assertTrue(geminiResp.executiveSummary.isNotEmpty())
        assertTrue(geminiResp.advisoryDisclaimer.contains("Authoritative security governance"))

        // Test Claude adapter
        val claudeResp = orchestrator.orchestrateAnalysis(req.copy(requestedProvider = AiProviderType.CLAUDE))
        assertEquals(AiProviderType.CLAUDE, claudeResp.providerType)
        assertFalse(claudeResp.isExternalModelConnected)

        // Test Llama adapter
        val llamaResp = orchestrator.orchestrateAnalysis(req.copy(requestedProvider = AiProviderType.LLAMA))
        assertEquals(AiProviderType.LLAMA, llamaResp.providerType)
        assertFalse(llamaResp.isExternalModelConnected)
    }

    @Test
    fun testOfflineEventQueueEncryptsPayloadsAndDecryptsSafely() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.InkingiDatabase::class.java
        ).allowMainThreadQueries().build()

        val vault = com.example.domain.security.OfflineSecurityVault(context)
        val transmittedPayloads = mutableListOf<String>()
        val testBackendGateway = object : com.example.domain.sync.SecurityEventBackendGateway {
            override fun isBackendConfigured(): Boolean = true
            override suspend fun transmitEvent(
                item: com.example.data.local.entity.QueueItemEntity,
                decryptedPayload: String
            ): com.example.domain.sync.BackendTransmissionOutcome {
                transmittedPayloads.add(decryptedPayload)
                return com.example.domain.sync.BackendTransmissionOutcome.SentToBackend("ref-${item.id}")
            }
        }
        val queue = com.example.domain.sync.SecurityEventQueue(
            queueItemDao = db.queueItemDao(),
            vault = vault,
            backendGateway = testBackendGateway
        )

        val sensitivePayload = "{\"secret_recovery_token\":\"XY99-AZ12\",\"lat\":-1.9441,\"lng\":30.0619}"
        val eventId = queue.enqueue("ENCRYPTED_TELEMETRY", sensitivePayload)

        // Verify that payload in Room entity is encrypted and has the version envelope
        val queuedItems = db.queueItemDao().getPendingQueueItems()
        assertEquals(1, queuedItems.size)
        val rawStoredPayload = queuedItems[0].payloadJson

        assertTrue("Stored payload must have ENC:V1: version prefix", rawStoredPayload.startsWith("ENC:V1:"))
        assertFalse("Raw stored payload must NEVER contain plaintext tokens", rawStoredPayload.contains("secret_recovery_token"))
        assertFalse("Raw stored payload must NEVER contain plaintext coordinates", rawStoredPayload.contains("-1.9441"))

        // Process queue: verified that safe decryption occurs and real backend confirmation transitions to SENT_TO_BACKEND
        val syncedCount = queue.processPendingQueue(isNetworkAvailable = true)
        assertEquals(1, syncedCount)
        assertEquals(1, transmittedPayloads.size)
        assertEquals(sensitivePayload, transmittedPayloads[0])
        assertEquals(0, db.queueItemDao().getPendingQueueItems().size)
        assertEquals(
            com.example.core.model.SyncStatus.SENT_TO_BACKEND.name,
            db.queueItemDao().getItemById(eventId)?.syncStatus
        )

        db.close()
    }

    @Test
    fun testCorruptedEncryptedQueuePayloadFailsGracefullyWithoutUploadingCorruptedData() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.InkingiDatabase::class.java
        ).allowMainThreadQueries().build()

        val vault = com.example.domain.security.OfflineSecurityVault(context)
        val queue = com.example.domain.sync.SecurityEventQueue(db.queueItemDao(), vault)

        // Directly insert a corrupted encrypted envelope with invalid Base64 ciphertext
        val corruptedEntity = com.example.data.local.entity.QueueItemEntity(
            id = "CORRUPT-1",
            eventType = "TELEMETRY",
            timestamp = System.currentTimeMillis(),
            priority = com.example.core.model.QueuePriority.NORMAL.name,
            payloadJson = "ENC:V1:???===INVALID-NOT-BASE64====",
            retryCount = 0,
            syncStatus = com.example.core.model.SyncStatus.PENDING.name
        )
        db.queueItemDao().enqueueItem(corruptedEntity)

        // Process queue: Must quarantine item as FAILED and NOT count as synced
        val syncedCount = queue.processPendingQueue(isNetworkAvailable = true)
        assertEquals(0, syncedCount)

        val itemsAfter = db.queueItemDao().getPendingQueueItems()
        assertEquals(1, itemsAfter.size)
        assertEquals(com.example.core.model.SyncStatus.FAILED.name, itemsAfter[0].syncStatus)

        db.close()
    }

    @Test
    fun testDurableEventCorrelationPersistsFingerprintsAcrossRestarts() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.InkingiDatabase::class.java
        ).allowMainThreadQueries().build()

        val fingerprintDao = db.eventFingerprintDao()

        // Instance 1 creates and processes an event
        val engine1 = EventCorrelationEngine(
            correlationWindowMillis = 15 * 60 * 1000L,
            fingerprintDao = fingerprintDao
        )

        val event = NormalizedSecurityEvent(
            eventId = "EVT-PERSIST-1",
            deviceId = "DEV-PERSIST-01",
            ownerId = "owner@inkingiguard.com",
            category = EventCategory.SIM_TELEPHONY,
            source = "TelephonyManager",
            severity = EventSeverity.CRITICAL,
            baseRiskImpact = 30,
            title = "SIM Ejected",
            details = "Carrier lost",
            timestamp = 10_000_000L
        )

        val res1 = engine1.processEvent(event, 30)
        assertFalse(res1.isDuplicateEvent)
        assertTrue(res1.isNewIncidentCreated)

        // Instance 2 simulates an app process restart with empty memory, using the same persistent DAO
        val engine2 = EventCorrelationEngine(
            correlationWindowMillis = 15 * 60 * 1000L,
            fingerprintDao = fingerprintDao
        )

        // Submit the same burst event within the 30-second deduplication bucket
        val duplicateAfterRestart = event.copy(eventId = "EVT-PERSIST-2", timestamp = 10_000_500L)
        val res2 = engine2.processEvent(duplicateAfterRestart, 30)

        assertTrue("Duplicate event burst after process restart must be suppressed via durable DAO", res2.isDuplicateEvent)
        assertEquals(null, res2.correlatedIncident)

        db.close()
    }

    @Test
    fun testIncidentResponseEngineAutoResolvesWhenRiskDropsBelowThreshold() = runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.InkingiDatabase::class.java
        ).allowMainThreadQueries().build()

        val stateManager = com.example.core.security.SecurityStateManager(
            securityEventDao = db.securityEventDao(),
            auditEventDao = db.auditEventDao()
        )
        val incidentEngine = com.example.domain.incident.IncidentResponseEngine(
            deviceId = "DEV-TEST-01",
            ownerId = "owner@test.com",
            incidentDao = db.incidentDao(),
            theftReportDao = db.theftReportDao(),
            stateManager = stateManager
        )

        // 1. High risk assessment creates an incident
        val highRisk = com.example.core.model.RiskAssessment(
            score = 65,
            level = RiskLevel.MEDIUM,
            reasons = listOf("SIM changed", "Outside zone")
        )
        val incident = incidentEngine.processIncidentFromAssessment(
            riskAssessment = highRisk,
            confidence = com.example.core.model.ConfidenceLevel.HIGH_CONFIDENCE,
            currentIncident = null
        )
        assertNotNull(incident)
        assertEquals(com.example.core.model.IncidentStatus.INVESTIGATING, incident!!.status)

        // 2. Risk decays or normalizes below 30 (score = 15)
        val lowRisk = com.example.core.model.RiskAssessment(
            score = 15,
            level = RiskLevel.LOW,
            reasons = listOf("Evidence decayed over time")
        )
        val resolvedOutcome = incidentEngine.processIncidentFromAssessment(
            riskAssessment = lowRisk,
            confidence = com.example.core.model.ConfidenceLevel.LOW_CONFIDENCE,
            currentIncident = incident
        )

        assertEquals("Incident must be auto-resolved when score falls below 30", null, resolvedOutcome)
        val allDbIncidents = db.incidentDao().getIncidentsList()
        assertEquals(1, allDbIncidents.size)
        assertEquals(com.example.core.model.IncidentStatus.RESOLVED.name, allDbIncidents[0].status)
        assertNotNull(allDbIncidents[0].resolvedTimestamp)

        db.close()
    }

    @Test
    fun testDeviceLockManagerCapabilityTruthfulnessAndSafety() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val lockManager = com.example.domain.management.DeviceLockManager(context)

        val capabilities = lockManager.getCapabilities()
        assertFalse("Standard un-enrolled test environment must NOT claim Device Owner", capabilities.isDeviceOwner)
        assertFalse("Standard un-enrolled test environment must NOT claim Device Admin", capabilities.isAdminActive)
        assertFalse("Standard un-enrolled test environment cannot lock without enrollment", capabilities.canLockDevice)

        // lockDeviceNow() must return Unavailable rather than throwing an unhandled exception or faking success
        val result = lockManager.lockDeviceNow()
        assertTrue("Must report Unavailable when privileges are absent", result is com.example.domain.management.DeviceLockManager.LockExecutionResult.Unavailable)
    }
}

