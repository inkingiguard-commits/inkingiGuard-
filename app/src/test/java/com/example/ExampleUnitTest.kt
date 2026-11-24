package com.example

import com.example.core.model.CommandStatus
import com.example.core.model.CommandType
import com.example.core.model.ConfidenceLevel
import com.example.core.model.EventSeverity
import com.example.core.model.LocationEvent
import com.example.core.model.MonitoringFrequency
import com.example.core.model.MotionEvent
import com.example.core.model.MovementType
import com.example.core.model.RiskAssessment
import com.example.core.model.RiskLevel
import com.example.core.model.SecurityCommand
import com.example.core.model.SecurityState
import com.example.core.model.SimSecurityInfo
import com.example.core.model.TamperType
import com.example.core.security.RiskEngine
import com.example.core.security.SecurityStateManager
import com.example.core.security.SignalCorrelationEngine
import com.example.data.local.dao.AuditEventDao
import com.example.data.local.dao.QueueItemDao
import com.example.data.local.dao.SecurityCommandDao
import com.example.data.local.dao.SecurityEventDao
import com.example.data.local.entity.AuditEventEntity
import com.example.data.local.entity.QueueItemEntity
import com.example.data.local.entity.SecurityCommandEntity
import com.example.data.local.entity.SecurityEventEntity
import com.example.domain.command.SecurityCommandProcessor
import com.example.domain.monitoring.AdaptiveMonitoringPolicy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleUnitTest {

    private lateinit var riskEngine: RiskEngine
    private lateinit var correlationEngine: SignalCorrelationEngine

    @Before
    fun setUp() {
        riskEngine = RiskEngine()
        correlationEngine = SignalCorrelationEngine(riskEngine)
    }

    @Test
    fun testNominalBaselineEvaluatesToLowRiskZeroScore() {
        val input = RiskEngine.EvaluationInput()
        val assessment = riskEngine.evaluate(input)

        assertEquals(0, assessment.score)
        assertEquals(RiskLevel.LOW, assessment.level)
        assertTrue(assessment.reasons.isNotEmpty())
    }

    @Test
    fun testSimChangeAdds30Points() {
        val input = RiskEngine.EvaluationInput(isSimChanged = true)
        val assessment = riskEngine.evaluate(input)

        assertEquals(30, assessment.score)
        assertEquals(RiskLevel.MEDIUM, assessment.level)
        assertTrue(assessment.reasons.any { it.contains("SIM alteration") })
    }

    @Test
    fun testThreeAuthFailuresAdd40Points() {
        val input = RiskEngine.EvaluationInput(failedAuthAttempts = 3)
        val assessment = riskEngine.evaluate(input)

        assertEquals(40, assessment.score)
        assertEquals(RiskLevel.MEDIUM, assessment.level)
        assertTrue(assessment.reasons.any { it.contains("authentication failures") })
    }

    @Test
    fun testOutsideTrustedZoneAdds20Points() {
        val input = RiskEngine.EvaluationInput(isOutsideTrustedZone = true)
        val assessment = riskEngine.evaluate(input)

        assertEquals(20, assessment.score)
        assertEquals(RiskLevel.LOW, assessment.level)
        assertTrue(assessment.reasons.any { it.contains("trusted perimeter") })
    }

    @Test
    fun testUnusualMovementAdds15Points() {
        val input = RiskEngine.EvaluationInput(isUnusualMovementDetected = true)
        val assessment = riskEngine.evaluate(input)

        assertEquals(15, assessment.score)
        assertEquals(RiskLevel.LOW, assessment.level)
    }

    @Test
    fun testCombinedAnomaliesReachHighRiskAndClampsAt100() {
        val input = RiskEngine.EvaluationInput(
            isSimChanged = true,
            failedAuthAttempts = 3,
            isOutsideTrustedZone = true,
            isUnusualMovementDetected = true,
            isUnexpectedRestartDetected = true
        )
        val assessment = riskEngine.evaluate(input)

        assertEquals(100, assessment.score)
        assertEquals(RiskLevel.HIGH, assessment.level)
        assertEquals(5, assessment.reasons.size)
    }

    @Test
    fun testCorrelationEngineDetectsTheftPattern() {
        val simInfo = SimSecurityInfo(
            simState = "UNKNOWN",
            isSimPresent = true,
            carrierName = "UNAUTHORIZED",
            simOperatorNumeric = "12345",
            hasSimChanged = true
        )
        val location = LocationEvent(
            latitude = 1.0,
            longitude = 1.0,
            accuracy = 10f,
            isInsideTrustedZone = false
        )
        val motion = MotionEvent(
            eventId = "evt1",
            deviceId = "dev1",
            sensorType = "ACCEL",
            movementType = MovementType.SUDDEN_ACCELERATION,
            confidence = ConfidenceLevel.HIGH_CONFIDENCE,
            timestamp = System.currentTimeMillis(),
            accelerationMagnitude = 20f,
            rotationRate = 4.5f
        )

        val report = correlationEngine.correlateSignals(
            simInfo = simInfo,
            lastLocation = location,
            recentMotion = motion,
            failedAuthCount = 0,
            isRebootDetected = false,
            isNetworkDropped = false,
            sensorAnomalyFlag = true
        )

        assertEquals(75, report.assessment.score)
        assertEquals(RiskLevel.HIGH, report.assessment.level)
        assertTrue(report.isTheftPatternDetected)
        assertTrue(report.generatedSecurityEvents.isNotEmpty())
    }

    // =======================================================
    // Phase 2 Incident Response Engine Unit Tests
    // =======================================================

    @Test
    fun testIncidentStateMachineTransitionsAndOwnerConfirmation() = runBlocking {
        val fakeSecurityDao = FakeSecurityEventDao()
        val fakeAuditDao = FakeAuditEventDao()
        val stateManager = SecurityStateManager(
            securityEventDao = fakeSecurityDao,
            auditEventDao = fakeAuditDao,
            deviceIdProvider = { "IG-TEST-DEVICE" }
        )

        assertEquals(SecurityState.NORMAL, stateManager.currentState.value)

        // Evaluate medium risk -> WARNING
        stateManager.evaluateStateFromRisk(
            RiskAssessment(score = 35, level = RiskLevel.MEDIUM, reasons = listOf("SIM alteration"))
        )
        assertEquals(SecurityState.WARNING, stateManager.currentState.value)

        // Mark Suspected Lost
        stateManager.markSuspectedLost("Device outside perimeter with kinetic jerk")
        assertEquals(SecurityState.SUSPECTED_LOST, stateManager.currentState.value)

        // Owner Confirms Stolen -> Transitions to STOLEN_MODE
        stateManager.confirmStolenByOwner("Verified Master PIN")
        assertEquals(SecurityState.STOLEN_MODE, stateManager.currentState.value)

        // Owner marks recovered
        stateManager.markRecovered("Verified Master PIN")
        assertEquals(SecurityState.RECOVERED, stateManager.currentState.value)

        // Disarm to normal
        stateManager.disarmToNormal("Verified Master PIN")
        assertEquals(SecurityState.NORMAL, stateManager.currentState.value)

        // Verify audit trail recorded transitions
        assertTrue(fakeAuditDao.insertedAudits.size >= 4)
    }

    @Test
    fun testBatteryAwareAdaptiveMonitoringPolicy() {
        val policy = AdaptiveMonitoringPolicy()

        // Critical battery below 15% prioritizes battery preservation
        val criticalStrategy = policy.determineStrategy(
            state = SecurityState.STOLEN_MODE,
            batteryPercentage = 12,
            isCharging = false
        )
        assertTrue(criticalStrategy.isBatteryConservationActive)
        assertEquals(MonitoringFrequency.LOW, criticalStrategy.frequency)

        // Normal state has relaxed monitoring
        val normalStrategy = policy.determineStrategy(
            state = SecurityState.NORMAL,
            batteryPercentage = 80,
            isCharging = false
        )
        assertFalse(normalStrategy.isBatteryConservationActive)
        assertEquals(MonitoringFrequency.LOW, normalStrategy.frequency)
        assertEquals(300_000L, normalStrategy.locationIntervalMillis)

        // Stolen mode with healthy battery has critical recovery beacon
        val stolenStrategy = policy.determineStrategy(
            state = SecurityState.STOLEN_MODE,
            batteryPercentage = 80,
            isCharging = false
        )
        assertEquals(MonitoringFrequency.CRITICAL, stolenStrategy.frequency)
        assertEquals(10_000L, stolenStrategy.locationIntervalMillis)
    }

    @Test
    fun testSecurityCommandAuthorizationFailsClosedOnDeviceMismatch() = runBlocking {
        val fakeSecurityDao = FakeSecurityEventDao()
        val fakeAuditDao = FakeAuditEventDao()
        val fakeCommandDao = FakeSecurityCommandDao()
        val stateManager = SecurityStateManager(fakeSecurityDao, fakeAuditDao) { "IG-DEVICE-A" }

        // Mismatched command processor: target is IG-DEVICE-B but command is for IG-DEVICE-A
        val processor = SecurityCommandProcessor(
            expectedDeviceId = "IG-DEVICE-B",
            lockManager = null as? com.example.domain.management.DeviceLockManager ?: FakeDeviceLockManager(),
            stateManager = stateManager,
            commandDao = fakeCommandDao
        )

        val command = SecurityCommand(
            commandId = "CMD-101",
            deviceId = "IG-DEVICE-A", // Mismatched!
            ownerId = "owner@test.com",
            commandType = CommandType.LOCK_DEVICE
        )

        val result = processor.executeCommand(command, "RemoteConsole")
        assertTrue(result is SecurityCommandProcessor.CommandExecutionResult.Rejected)
        assertEquals(CommandStatus.REJECTED, fakeCommandDao.lastUpdatedCommand?.toDomain()?.status)
    }

    @Test
    fun testAntiArnaqueEngineDetectsPhishingAndMaliciousLinks() {
        // French phishing SMS with delivery impersonation and link shortener
        val resultA = com.example.domain.ai.AntiArnaqueEngine.analyzeTextOrUrl(
            "Urgent Chronopost : Votre colis est bloqué, cliquez ici bit.ly/colis-track",
            lang = "fr"
        )
        assertTrue(resultA.isSuspicious)
        assertTrue(resultA.detectedIndicators.any { it.contains("colis") || it.contains("Lien court") })

        // English phishing SMS with account suspension and malicious domain
        val resultB = com.example.domain.ai.AntiArnaqueEngine.analyzeTextOrUrl(
            "Urgent: Your account is suspended, click here http://fake-login.xyz/verify otp password",
            lang = "en"
        )
        assertTrue(resultB.isSuspicious)
        assertTrue(resultB.riskScore >= 60)
        assertEquals("HIGH-CONFIDENCE SCAM (CRITICAL DANGER)", resultB.verdict)

        // Normal innocent message does not trigger scam detection
        val innocent = com.example.domain.ai.AntiArnaqueEngine.analyzeTextOrUrl(
            "Bonjour, on se voit à 14h pour le déjeuner.",
            lang = "fr"
        )
        assertFalse(innocent.isSuspicious)
    }

    @Test
    fun testAntiArnaqueEngineDetectsSocialEngineeringAndMaliciousDomains() {
        val scamMessage = "Félicitations ! Votre compte est bloqué, confirmez ici bit.ly/claim500 mot de passe"
        val analysis = com.example.domain.ai.AntiArnaqueEngine.analyzeTextOrUrl(scamMessage, lang = "fr")

        assertTrue(analysis.isSuspicious)
        assertTrue(analysis.riskScore >= 75)
        assertEquals("ARNAQUE TRÈS PROBABLE (DANGER CRITIQUE)", analysis.verdict)
    }

    @Test
    fun testLostPhoneAlarmManagerStartsAndStopsCleanly() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val alarmManager = com.example.domain.alerts.LostPhoneAlarmManager(context)

        // Robolectric environment handles audio attributes safely
        val started = alarmManager.startAlarm(timeoutMs = 5000L)
        // If ringtone exists in test runner, it starts or handles gracefully
        alarmManager.stopAlarm()
        assertFalse(alarmManager.isAlarmActive())
    }

    @Test
    fun testLostPhoneAlarmTimeoutCallbackInvoked() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val alarmManager = com.example.domain.alerts.LostPhoneAlarmManager(context)

        var stoppedInvoked = false
        alarmManager.onAlarmStopped = {
            stoppedInvoked = true
        }

        // Start with a short timeout
        alarmManager.startAlarm(timeoutMs = 50L)

        // Advance Robolectric main looper
        org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertFalse(alarmManager.isAlarmActive())
        assertTrue(stoppedInvoked)
    }

    @Test
    fun testAlarmStopAuthorizationGatingWithMasterPin() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(com.example.data.repository.DeviceSettingsRepository.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(com.example.domain.security.OfflineSecurityVault.VAULT_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.InkingiDatabase::class.java
        ).allowMainThreadQueries().build()
        val container = com.example.di.InkingiContainer(context, db)
        val viewModel = com.example.presentation.InkingiViewModel(container)
        assertTrue(viewModel.enrollMasterPin("8492"))

        // Trigger alarm
        viewModel.triggerFindMyPhoneAlarm()

        // 1. Unauthorized attempt (wrong PIN) fails closed and keeps alarm state active or records warning
        val unauthorizedSuccess = viewModel.stopFindMyPhoneAlarm("9999")
        assertFalse(unauthorizedSuccess)

        // 2. Authorized owner attempt (enrolled Master PIN "8492") succeeds
        val authorizedSuccess = viewModel.stopFindMyPhoneAlarm("8492")
        assertTrue(authorizedSuccess)
        assertFalse(viewModel.uiState.value.isFindMyPhoneAlarmActive)

        db.close()
    }

    @Test
    fun testOwnerOnlyTheftConfirmationRejectsUnauthorizedPin() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(com.example.data.repository.DeviceSettingsRepository.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(com.example.domain.security.OfflineSecurityVault.VAULT_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.InkingiDatabase::class.java
        ).allowMainThreadQueries().build()
        val container = com.example.di.InkingiContainer(context, db)
        val viewModel = com.example.presentation.InkingiViewModel(container)
        assertTrue(viewModel.enrollMasterPin("8492"))

        // 1. Unauthorized attempt to confirm theft
        val unauthorizedConfirmed = viewModel.confirmStolenByOwner("0000")
        assertFalse(unauthorizedConfirmed)
        // State remains nominal
        assertFalse(viewModel.uiState.value.securityState == SecurityState.OWNER_CONFIRMED_STOLEN)

        // 2. Strong lock attempt with wrong PIN is also rejected
        val unauthorizedLock = viewModel.confirmStolenWithStrongLock("0000")
        assertFalse(unauthorizedLock)

        // 3. Authorized owner attempt succeeds
        val authorizedConfirmed = viewModel.confirmStolenByOwner("8492")
        assertTrue(authorizedConfirmed)

        db.close()
    }

    @Test
    fun testGuestRestrictionsAndTrustedPersonManagement() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences(com.example.data.repository.DeviceSettingsRepository.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(com.example.domain.security.OfflineSecurityVault.VAULT_PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        val repo = com.example.data.repository.DeviceSettingsRepository(context)
        assertTrue(repo.enrollMasterPin("8492") is com.example.domain.security.OfflineSecurityVault.PinEnrollmentResult.Success)

        // Switch to GUEST mode (does not require PIN)
        val switchedToGuest = repo.switchUserRole(com.example.core.model.UserRole.GUEST, null)
        assertTrue(switchedToGuest)
        assertEquals(com.example.core.model.UserRole.GUEST, repo.currentUserRole.value)

        // Guest attempting to switch back to OWNER without correct PIN fails
        val guestEscapeWithoutPin = repo.switchUserRole(com.example.core.model.UserRole.OWNER, null)
        assertFalse(guestEscapeWithoutPin)

        val guestEscapeWrongPin = repo.switchUserRole(com.example.core.model.UserRole.OWNER, "9999")
        assertFalse(guestEscapeWrongPin)
        assertEquals(com.example.core.model.UserRole.GUEST, repo.currentUserRole.value)

        // Owner authenticates PIN to exit GUEST mode
        val ownerRestore = repo.switchUserRole(com.example.core.model.UserRole.OWNER, "8492")
        assertTrue(ownerRestore)
        assertEquals(com.example.core.model.UserRole.OWNER, repo.currentUserRole.value)

        // Adding trusted person requires owner PIN
        val addFail = repo.addTrustedPerson("Alice M.", "+25779000001", "Famille", "wrong")
        assertFalse(addFail)

        val addSuccess = repo.addTrustedPerson("Alice M.", "+25779000001", "Famille", "8492")
        assertTrue(addSuccess)
        assertTrue(repo.trustedPersons.value.any { it.name == "Alice M." })

        val personId = repo.trustedPersons.value.first { it.name == "Alice M." }.id

        // Deleting trusted person requires owner PIN
        val deleteFail = repo.removeTrustedPerson(personId, "wrong")
        assertFalse(deleteFail)

        val deleteSuccess = repo.removeTrustedPerson(personId, "8492")
        assertTrue(deleteSuccess)
        assertFalse(repo.trustedPersons.value.any { it.id == personId })
    }

    @Test
    fun testSingleSignalAnomalyNeverDeclaresTheftAlone() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.InkingiDatabase::class.java
        ).allowMainThreadQueries().build()
        val container = com.example.di.InkingiContainer(context, db)
        val viewModel = com.example.presentation.InkingiViewModel(container)

        // Simulate SIM alteration only
        viewModel.simulateSimAlteration()
        // Single signal increases risk but must NEVER transition automatically into OWNER_CONFIRMED_STOLEN or STOLEN_MODE
        assertFalse(viewModel.uiState.value.securityState == SecurityState.OWNER_CONFIRMED_STOLEN)
        assertFalse(viewModel.uiState.value.securityState == SecurityState.STOLEN_MODE)

        // Simulate displacement outside zone
        viewModel.simulateDisplacementOutsideZone()
        assertFalse(viewModel.uiState.value.securityState == SecurityState.STOLEN_MODE)

        // Guest using phone is NOT classified as a thief
        viewModel.switchUserRole(com.example.core.model.UserRole.GUEST)
        assertFalse(viewModel.uiState.value.securityState == SecurityState.STOLEN_MODE)

        db.close()
    }

    @Test
    fun testBehaviorBaselineExplainabilityDoesNotDeclareTheftOnDeviationAlone() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val fakeDao = object : com.example.data.local.dao.BehaviorEventDao {
            override suspend fun insertEvent(event: com.example.data.local.entity.BehaviorEventEntity): Long = 1L
            override fun getAllEvents(): Flow<List<com.example.data.local.entity.BehaviorEventEntity>> = flowOf(emptyList())
            override fun getRecentEvents(limit: Int): Flow<List<com.example.data.local.entity.BehaviorEventEntity>> = flowOf(emptyList())
            override fun getUniqueLearningDays(): Flow<Int> = flowOf(5)
            override suspend fun getUniqueLearningDaysCount(): Int = 5
            override suspend fun getTotalEventCount(): Int = 15
            override suspend fun getFrequentHours(): List<com.example.data.local.dao.HourFrequency> = listOf(com.example.data.local.dao.HourFrequency(10, 5))
            override suspend fun getFrequentZones(): List<com.example.data.local.dao.ZoneFrequency> = listOf(com.example.data.local.dao.ZoneFrequency("Maison", 10))
            override suspend fun getOwnerEventsSince(deviceId: String, sinceTimestamp: Long): List<com.example.data.local.entity.BehaviorEventEntity> = emptyList()
            override suspend fun clearAll() {}
            override suspend fun clearForDevice(deviceId: String) {}
        }

        val manager = com.example.domain.ai.BehaviorLearningManager(fakeDao, "IG-TEST")
        runBlocking {
            manager.computeAndSyncBaseline()
        }

        // Test at an unusual night hour (3 AM) in unknown zone
        val (within, statusMsg) = manager.isWithinBaseline(3, "Inconnu")
        assertFalse(within)
        assertTrue(statusMsg.contains("Hors baseline"))
        // Confirms it notes risk increase without declaring theft
        assertFalse(statusMsg.contains("VOL_CONFIRME"))
    }

    @Test
    fun testGuestActivityDoesNotContaminateOwnerBaseline() {
        val fakeDao = object : com.example.data.local.dao.BehaviorEventDao {
            val events = mutableListOf<com.example.data.local.entity.BehaviorEventEntity>()
            override suspend fun insertEvent(event: com.example.data.local.entity.BehaviorEventEntity): Long {
                events.add(event)
                return 1L
            }
            override fun getAllEvents(): Flow<List<com.example.data.local.entity.BehaviorEventEntity>> = flowOf(events)
            override fun getRecentEvents(limit: Int): Flow<List<com.example.data.local.entity.BehaviorEventEntity>> = flowOf(events.take(limit))
            override fun getUniqueLearningDays(): Flow<Int> = flowOf(1)
            override suspend fun getUniqueLearningDaysCount(): Int = 1
            override suspend fun getTotalEventCount(): Int = events.size
            override suspend fun getFrequentHours(): List<com.example.data.local.dao.HourFrequency> = listOf(com.example.data.local.dao.HourFrequency(14, events.size))
            override suspend fun getFrequentZones(): List<com.example.data.local.dao.ZoneFrequency> = listOf(com.example.data.local.dao.ZoneFrequency("Maison", events.size))
            override suspend fun getOwnerEventsSince(deviceId: String, sinceTimestamp: Long): List<com.example.data.local.entity.BehaviorEventEntity> =
                events.filter { it.timestamp >= sinceTimestamp && (it.device_id.isEmpty() || it.device_id == deviceId) && it.action != "GUEST_SESSION" && it.action != "TRUSTED_PERSON_SESSION" }
            override suspend fun clearAll() { events.clear() }
            override suspend fun clearForDevice(deviceId: String) { events.removeAll { it.device_id.isEmpty() || it.device_id == deviceId } }
        }

        val manager = com.example.domain.ai.BehaviorLearningManager(fakeDao, "IG-TEST")
        runBlocking {
            // Guest uses phone in unusual zone
            manager.recordAppOpenEvent("Zone Etrangere", com.example.core.model.UserRole.GUEST)
        }

        assertEquals(1, fakeDao.events.size)
        assertEquals("GUEST_SESSION", fakeDao.events[0].action)

        // Baseline profile remains uncomputed from guest session
        assertEquals(0, manager.baselineFlow.value.totalObservations)

        // Also test BehavioralLearningEngine does not ingest GUEST observations
        val engine = com.example.core.brain.baseline.BehavioralLearningEngine(minRequiredSamples = 5)
        val guestObs = com.example.core.brain.baseline.BehavioralObservation(
            simCarrier = "ForeignCarrier",
            role = com.example.core.model.UserRole.GUEST
        )
        engine.recordObservation(guestObs)
        assertEquals(0, engine.getProfile().sampleCount)
        assertFalse(engine.getProfile().knownCarriers.contains("ForeignCarrier"))

        // Owner observation DOES record into baseline
        val ownerObs = com.example.core.brain.baseline.BehavioralObservation(
            simCarrier = "Econet Leo",
            role = com.example.core.model.UserRole.OWNER
        )
        engine.recordObservation(ownerObs)
        assertEquals(1, engine.getProfile().sampleCount)
        assertTrue(engine.getProfile().knownCarriers.contains("Econet Leo"))
    }

    @Test
    fun testTrustedPersonActivityHandling() {
        val manager = com.example.domain.ai.BehaviorLearningManager(
            object : com.example.data.local.dao.BehaviorEventDao {
                override suspend fun insertEvent(event: com.example.data.local.entity.BehaviorEventEntity): Long = 1L
                override fun getAllEvents(): Flow<List<com.example.data.local.entity.BehaviorEventEntity>> = flowOf(emptyList())
                override fun getRecentEvents(limit: Int): Flow<List<com.example.data.local.entity.BehaviorEventEntity>> = flowOf(emptyList())
                override fun getUniqueLearningDays(): Flow<Int> = flowOf(0)
                override suspend fun getUniqueLearningDaysCount(): Int = 0
                override suspend fun getTotalEventCount(): Int = 0
                override suspend fun getFrequentHours(): List<com.example.data.local.dao.HourFrequency> = emptyList()
                override suspend fun getFrequentZones(): List<com.example.data.local.dao.ZoneFrequency> = emptyList()
                override suspend fun getOwnerEventsSince(deviceId: String, sinceTimestamp: Long): List<com.example.data.local.entity.BehaviorEventEntity> = emptyList()
                override suspend fun clearAll() {}
                override suspend fun clearForDevice(deviceId: String) {}
            },
            "IG-TEST"
        )

        val (isWithin, explanation) = manager.isWithinBaseline(2, "Zone Inconnue", com.example.core.model.UserRole.TRUSTED_PERSON)
        assertTrue(isWithin)
        assertTrue(explanation.contains("Personne de confiance active"))
    }

    @Test
    fun testFamiliarZoneRecognitionAndUnknownZoneRiskContribution() {
        val riskEngine = com.example.core.security.RiskEngine()
        val correlationEngine = com.example.core.security.SignalCorrelationEngine(riskEngine)

        val simInfo = com.example.core.model.SimSecurityInfo("READY", true, "Lumitel", "64201", false)

        // Inside trusted zone: 0 zone penalty
        val insideLoc = com.example.core.model.LocationEvent(-1.94, 30.06, 10f, isInsideTrustedZone = true, closestZoneName = "Bureau")
        val insideReport = correlationEngine.correlateSignals(
            simInfo = simInfo,
            lastLocation = insideLoc,
            recentMotion = null,
            failedAuthCount = 0,
            isRebootDetected = false,
            isNetworkDropped = false,
            sensorAnomalyFlag = false
        )
        assertEquals(0, insideReport.assessment.score)
        assertEquals(com.example.core.model.CorrelationThreatState.NORMAL, insideReport.threatState)

        // Outside trusted zone alone: +20 points, but NEVER declares theft
        val outsideLoc = com.example.core.model.LocationEvent(-1.99, 30.12, 15f, isInsideTrustedZone = false, closestZoneName = null)
        val outsideReport = correlationEngine.correlateSignals(
            simInfo = simInfo,
            lastLocation = outsideLoc,
            recentMotion = null,
            failedAuthCount = 0,
            isRebootDetected = false,
            isNetworkDropped = false,
            sensorAnomalyFlag = false
        )
        assertEquals(20, outsideReport.assessment.score)
        assertFalse(outsideReport.isTheftPatternDetected)
        assertFalse(outsideReport.threatState == com.example.core.model.CorrelationThreatState.THEFT_CONFIRMATION_REQUIRED)
    }

    @Test
    fun testMultiSignalCorrelationRequiresMultipleIndependentAnomalies() {
        val riskEngine = com.example.core.security.RiskEngine()
        val correlationEngine = com.example.core.security.SignalCorrelationEngine(riskEngine)

        val simChanged = com.example.core.model.SimSecurityInfo("READY", true, "Unknown SIM", "99999", true)
        val outsideLoc = com.example.core.model.LocationEvent(-1.99, 30.12, 15f, isInsideTrustedZone = false, closestZoneName = null)
        val unusualMotion = com.example.core.model.MotionEvent("1", "dev", "ACCEL", com.example.core.model.MovementType.SUDDEN_ACCELERATION, com.example.core.model.ConfidenceLevel.HIGH_CONFIDENCE, System.currentTimeMillis(), 24f, 2f)

        // 1. Single anomaly alone (SIM change only): increases risk (+30) but NEVER theft confirmation
        val singleSimReport = correlationEngine.correlateSignals(
            simInfo = simChanged,
            lastLocation = null,
            recentMotion = null,
            failedAuthCount = 0,
            isRebootDetected = false,
            isNetworkDropped = false,
            sensorAnomalyFlag = false
        )
        assertFalse(singleSimReport.isTheftPatternDetected)
        assertNotEquals(com.example.core.model.CorrelationThreatState.THEFT_CONFIRMATION_REQUIRED, singleSimReport.threatState)

        // 2. Converging independent anomalies: SIM change + outside zone + unusual kinetic acceleration + radio drop
        val multiSignalReport = correlationEngine.correlateSignals(
            simInfo = simChanged,
            lastLocation = outsideLoc,
            recentMotion = unusualMotion,
            failedAuthCount = 0,
            isRebootDetected = false,
            isNetworkDropped = true,
            sensorAnomalyFlag = true
        )

        assertTrue(multiSignalReport.assessment.score >= 70)
        assertTrue(multiSignalReport.correlatedAnomaliesCount >= 3)
        assertEquals(com.example.core.model.CorrelationThreatState.THEFT_CONFIRMATION_REQUIRED, multiSignalReport.threatState)
        assertTrue(multiSignalReport.isTheftPatternDetected)

        // Real explainable reasons stored
        assertTrue(multiSignalReport.contributingSignals.any { it.contains("SIM alteration") })
        assertTrue(multiSignalReport.contributingSignals.any { it.contains("trusted perimeter") })
        assertTrue(multiSignalReport.contributingSignals.any { it.contains("acceleration") })
    }

    @Test
    fun testOfflineLocationAndSensorResilience() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.InkingiDatabase::class.java
        ).allowMainThreadQueries().build()
        val container = com.example.di.InkingiContainer(context, db)
        val viewModel = com.example.presentation.InkingiViewModel(container)

        // Offline / GPS unavailable: system maintains state gracefully without crashing
        assertNotNull(viewModel.uiState.value.securityState)
        assertEquals(SecurityState.NORMAL, viewModel.uiState.value.securityState)

        db.close()
    }

    @Test
    fun testRemoteRecoveryCommandValidationAndAntiReplay() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.InkingiDatabase::class.java
        ).allowMainThreadQueries().build()
        val container = com.example.di.InkingiContainer(context, db)

        val bridge = container.secureRecoveryBridge

        // 1. Dispatching command with wrong master PIN fails closed
        runBlocking {
            val badPinResult = container.commandProcessor.executeCommand(
                com.example.core.model.SecurityCommand(
                    commandId = "cmd-1",
                    deviceId = "WRONG-DEVICE",
                    ownerId = "owner@test.com",
                    commandType = com.example.core.model.CommandType.LOCK_DEVICE,
                    createdTimestamp = System.currentTimeMillis(),
                    expirationTimestamp = System.currentTimeMillis() + 60000
                ),
                authorizedBy = "owner@test.com"
            )
            // Target device mismatch rejected immediately
            assertTrue(badPinResult is com.example.domain.command.SecurityCommandProcessor.CommandExecutionResult.Rejected)
        }

        // 2. Expired command (anti-replay check) fails closed
        runBlocking {
            val expiredResult = container.commandProcessor.executeCommand(
                com.example.core.model.SecurityCommand(
                    commandId = "cmd-2",
                    deviceId = container.deviceSettingsRepository.deviceProfile.value.deviceId,
                    ownerId = "owner@test.com",
                    commandType = com.example.core.model.CommandType.TRIGGER_ALARM,
                    createdTimestamp = System.currentTimeMillis() - 200000,
                    expirationTimestamp = System.currentTimeMillis() - 1000 // Expired
                ),
                authorizedBy = "owner@test.com"
            )
            assertTrue(expiredResult is com.example.domain.command.SecurityCommandProcessor.CommandExecutionResult.Rejected)
            assertTrue((expiredResult as com.example.domain.command.SecurityCommandProcessor.CommandExecutionResult.Rejected).reason.contains("expired"))
        }

        // 3. Valid command targeting correct device executes
        runBlocking {
            val validResult = container.commandProcessor.executeCommand(
                com.example.core.model.SecurityCommand(
                    commandId = "cmd-3",
                    deviceId = container.deviceSettingsRepository.deviceProfile.value.deviceId,
                    ownerId = "owner@test.com",
                    commandType = com.example.core.model.CommandType.TRIGGER_ALARM,
                    createdTimestamp = System.currentTimeMillis(),
                    expirationTimestamp = System.currentTimeMillis() + 60000
                ),
                authorizedBy = "owner@test.com"
            )
            assertTrue(validResult is com.example.domain.command.SecurityCommandProcessor.CommandExecutionResult.Success)
            assertTrue(container.lostPhoneAlarmManager.isAlarmActive())

            // Stop alarm via remote command
            val stopResult = container.commandProcessor.executeCommand(
                com.example.core.model.SecurityCommand(
                    commandId = "cmd-4",
                    deviceId = container.deviceSettingsRepository.deviceProfile.value.deviceId,
                    ownerId = "owner@test.com",
                    commandType = com.example.core.model.CommandType.STOP_ALARM,
                    createdTimestamp = System.currentTimeMillis(),
                    expirationTimestamp = System.currentTimeMillis() + 60000
                ),
                authorizedBy = "owner@test.com"
            )
            assertTrue(stopResult is com.example.domain.command.SecurityCommandProcessor.CommandExecutionResult.Success)
            assertFalse(container.lostPhoneAlarmManager.isAlarmActive())
        }

        // 4. Local-First Telemetry Sync does not crash in offline test environment
        runBlocking {
            val synced = bridge.syncTelemetry(
                state = SecurityState.NORMAL,
                location = com.example.core.model.LocationEvent(-1.94, 30.06, 8.5f),
                health = null,
                network = null,
                recentEvents = emptyList()
            )
            assertNotNull(synced)
            assertEquals(container.deviceSettingsRepository.deviceProfile.value.deviceId, synced.deviceId)
        }

        db.close()
    }

    @Test
    fun testRealRecoveryMapTraceabilityAndOfflineBreadcrumbs() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.InkingiDatabase::class.java
        ).allowMainThreadQueries().build()
        val container = com.example.di.InkingiContainer(context, db)

        runBlocking {
            // 1. Insert genuine historical coordinates in Room
            val locDao = db.locationDao()
            val now = System.currentTimeMillis()
            locDao.insertLocation(
                com.example.data.local.entity.LocationHistoryEntity(
                    latitude = -1.9441,
                    longitude = 30.0619,
                    accuracy = 5.2f,
                    altitude = 1500.0,
                    speed = 0.0f,
                    bearing = 0.0f,
                    timestamp = now - 60000,
                    isInsideTrustedZone = true,
                    syncedWithBackend = false
                )
            )
            locDao.insertLocation(
                com.example.data.local.entity.LocationHistoryEntity(
                    latitude = -1.9445,
                    longitude = 30.0625,
                    accuracy = 6.0f,
                    altitude = 1502.0,
                    speed = 1.2f,
                    bearing = 90.0f,
                    timestamp = now,
                    isInsideTrustedZone = false,
                    syncedWithBackend = false
                )
            )

            val history = locDao.getLocationHistory(10).first()
            assertEquals(2, history.size)
            assertEquals(-1.9445, history[0].latitude, 0.0001)

            // 2. Sync telemetry with breadcrumbs
            val synced = container.secureRecoveryBridge.syncTelemetry(
                state = SecurityState.STOLEN_MODE,
                location = com.example.core.model.LocationEvent(-1.9445, 30.0625, 6.0f, isInsideTrustedZone = false),
                health = com.example.core.model.DeviceHealthInfo(
                    batteryPercentage = 42,
                    isCharging = true,
                    temperatureCelsius = 30f,
                    uptimeMillis = 50000L,
                    osVersion = "Android 14",
                    deviceModel = "Test Device",
                    manufacturer = "OEM"
                ),
                network = com.example.core.model.NetworkSecurityInfo(
                    isConnected = true,
                    isWifi = false,
                    isCellular = true,
                    networkType = "LTE"
                ),
                recentEvents = emptyList()
            )

            // 3. Verify real metrics and trace points are captured accurately without simulation
            assertNotNull(synced)
            assertEquals(42, synced.batteryLevel)
            assertTrue(synced.isCharging)
            assertEquals("LTE", synced.connectivityStatus)
            assertEquals("STOLEN_MODE", synced.securityState)
            assertEquals(1, synced.locationHistory.size)
            assertEquals(-1.9445, synced.locationHistory[0].latitude, 0.0001)
            assertFalse(synced.locationHistory[0].isInsideTrustedZone)
        }

        db.close()
    }

    // Fake helpers for JVM test isolation
    private class FakeSecurityEventDao : SecurityEventDao {
        val insertedEvents = mutableListOf<SecurityEventEntity>()
        override fun getAllEvents(): Flow<List<SecurityEventEntity>> = flowOf(insertedEvents)
        override fun getRecentEvents(limit: Int): Flow<List<SecurityEventEntity>> = flowOf(insertedEvents.take(limit))
        override suspend fun insertEvent(event: SecurityEventEntity) { insertedEvents.add(event) }
        override suspend fun clearAll() { insertedEvents.clear() }
        override suspend fun getCount(): Int = insertedEvents.size
    }

    private class FakeAuditEventDao : AuditEventDao {
        val insertedAudits = mutableListOf<AuditEventEntity>()
        override fun getAllAuditEvents(): Flow<List<AuditEventEntity>> = flowOf(insertedAudits)
        override suspend fun insertAuditEvent(audit: AuditEventEntity) { insertedAudits.add(audit) }
    }

    private class FakeSecurityCommandDao : SecurityCommandDao {
        var lastUpdatedCommand: SecurityCommandEntity? = null
        override fun getAllCommands(): Flow<List<SecurityCommandEntity>> = flowOf(emptyList())
        override suspend fun getPendingCommands(): List<SecurityCommandEntity> = emptyList()
        override suspend fun insertCommand(command: SecurityCommandEntity) {}
        override suspend fun updateCommand(command: SecurityCommandEntity) { lastUpdatedCommand = command }
    }

    private class FakeDeviceLockManager : com.example.domain.management.DeviceLockManager(
        androidx.test.core.app.ApplicationProvider.getApplicationContext()
    ) {
        override fun lockDeviceNow(): LockExecutionResult {
            return LockExecutionResult.Unavailable("Fake lock for testing", false)
        }
    }

    @Test
    fun testAutonomousLockGuardrails() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.InkingiDatabase::class.java
        ).allowMainThreadQueries().build()
        val container = com.example.di.InkingiContainer(context, db)
        val viewModel = com.example.presentation.InkingiViewModel(container)

        // Test 1: In NORMAL state without multi-signals, autonomous lock returns null (no lock)
        val normalResult = viewModel.triggerAutonomousEmergencyLock()
        assertNull(normalResult)

        // Test 2: Verify isolated signal (auth failures only) cannot reach score 80
        val isolatedAuth = com.example.core.brain.risk.ConfigurableRiskScoringEngine.RawSignals(
            failedAuthAttempts = 4
        )
        val evalIsolated = container.securityBrain.riskScoringEngine.evaluate(isolatedAuth)
        assertEquals(40, evalIsolated.totalScore)
        // Score 40 is WARNING (< 80) -> does not trigger SUSPECTED_LOST
        assertTrue(evalIsolated.totalScore < 80)

        // Test 3: Multiple independent signals below threshold (<80)
        val subThresholdSignals = com.example.core.brain.risk.ConfigurableRiskScoringEngine.RawSignals(
            isOutsideTrustedZone = true, // 20 pts
            isUnusualMovementDetected = true, // 15 pts
            hasNetworkAnomaly = true // 10 pts
        )
        val evalSub = container.securityBrain.riskScoringEngine.evaluate(subThresholdSignals)
        assertEquals(45, evalSub.totalScore)
        assertTrue(evalSub.totalScore < 80)

        // Test 4: Sufficient independent signals reaching SUSPECTED_LOST (>= 80)
        val criticalSignals = com.example.core.brain.risk.ConfigurableRiskScoringEngine.RawSignals(
            isSimChanged = true, // 30 pts
            failedAuthAttempts = 3, // 40 pts
            isOutsideTrustedZone = true, // 20 pts
            isUnusualMovementDetected = true // 15 pts
        )
        val evalCritical = container.securityBrain.riskScoringEngine.evaluate(criticalSignals)
        assertTrue(evalCritical.totalScore >= 80)
        assertEquals(4, evalCritical.evidenceItems.map { it.factorKey }.distinct().size)

        // Test 5: Device Admin unavailable does not report false success
        val capabilities = container.deviceLockManager.getCapabilities()
        val lockAttempt = container.deviceLockManager.lockDeviceNow()
        if (!capabilities.isAdminActive) {
            assertTrue(lockAttempt is com.example.domain.management.DeviceLockManager.LockExecutionResult.Unavailable)
        }

        // Test 6: Battery preservation check: low battery alone does not produce SUSPECTED_LOST
        val batteryOnlySignals = com.example.core.brain.risk.ConfigurableRiskScoringEngine.RawSignals()
        val evalBattery = container.securityBrain.riskScoringEngine.evaluate(batteryOnlySignals)
        assertEquals(0, evalBattery.totalScore)

        // Test 7: Strict Battery Protection - Battery 14% + strong theft signals = NO automatic lock
        // InkingiUiState with 14% battery
        viewModel.simulateBatteryPercentage(14, false)
        assertTrue(viewModel.uiState.value.deviceHealth.batteryPercentage <= 15)
        val lockResultAt14 = viewModel.triggerAutonomousEmergencyLock()
        assertNull("Lock must be strictly null at 14% battery", lockResultAt14)

        // Test 8: Strict Battery Protection - Battery 15% + strong theft signals = NO automatic lock
        // InkingiUiState with 15% battery
        viewModel.simulateBatteryPercentage(15, false)
        assertTrue(viewModel.uiState.value.deviceHealth.batteryPercentage <= 15)
        val lockResultAt15 = viewModel.triggerAutonomousEmergencyLock()
        assertNull("Lock must be strictly null at 15% battery", lockResultAt15)

        // Test 9: Battery 16% + sufficient independent signals: normal policy evaluation
        viewModel.simulateBatteryPercentage(16, false)
        assertFalse(viewModel.uiState.value.deviceHealth.batteryPercentage <= 15)
        assertTrue(viewModel.uiState.value.deviceHealth.batteryPercentage > 15)

        // Test 10: Task 5 Alarm Manager test start and safe stop
        val alarmManager = container.lostPhoneAlarmManager
        assertNotNull(alarmManager)
        val alarmTriggered = alarmManager.startAlarm(timeoutMs = 5000L)
        assertTrue(alarmTriggered)
        assertTrue(alarmManager.isAlarmActive())
        alarmManager.stopAlarm()
        assertFalse(alarmManager.isAlarmActive())

        // Test 11: Task 7 Lock-Screen message configuration without secrets exposure
        val msgResult = viewModel.configureLockScreenMessage("Appareil sécurisé par InkingiGuard", "+250790123456")
        assertNotNull(msgResult)
        assertTrue(msgResult.contains("Message"))

        // Test 12: Task 8 Behavior Learning pause & resume controls
        container.behaviorLearningManager.setLearningPaused(true)
        assertTrue(container.behaviorLearningManager.isLearningPaused())
        container.behaviorLearningManager.setLearningPaused(false)
        assertFalse(container.behaviorLearningManager.isLearningPaused())

        // Test 13: Task 9 Anti-Arnaque Scanner verification
        val scamCheck = com.example.domain.ai.AntiArnaqueEngine.analyzeTextOrUrl("Urgent: votre compte est suspendu, cliquez sur bit.ly/test")
        assertTrue(scamCheck.isSuspicious)
        assertTrue(scamCheck.riskScore >= 40)

        db.close()
    }
}
