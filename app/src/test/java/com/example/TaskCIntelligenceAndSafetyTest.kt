package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.brain.baseline.BehavioralLearningEngine
import com.example.core.brain.baseline.BehavioralObservation
import com.example.core.brain.correlation.EventCorrelationEngine
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
import com.example.core.model.CorrelationThreatState
import com.example.core.model.EventSeverity
import com.example.core.model.IncidentStatus
import com.example.core.model.SecurityState
import com.example.core.model.UserRole
import com.example.data.local.InkingiDatabase
import com.example.data.local.entity.BehaviorEventEntity
import com.example.data.repository.DeviceSettingsRepository
import com.example.di.InkingiContainer
import com.example.domain.ai.BehaviorLearningManager
import com.example.presentation.InkingiViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskCIntelligenceAndSafetyTest {

    private lateinit var context: Context
    private lateinit var db: InkingiDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(DeviceSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("inkingi_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("inkingi_security_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(com.example.domain.security.OfflineSecurityVault.VAULT_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, InkingiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testLearnedFrequentHoursAndZonesReplaceHardcodedWindow() = runBlocking {
        val dao = db.behaviorEventDao()
        val manager = BehaviorLearningManager(dao, "DEV-NIGHT-SHIFT")
        val now = System.currentTimeMillis()
        val dayMs = BehaviorLearningManager.DAY_MILLIS

        // Simulate a night-shift worker using the phone at 23:00 and 00:00 at "Hopital" for 7 distinct days
        for (d in 1..7) {
            val baseDay = now - (d * dayMs)
            dao.insertEvent(BehaviorEventEntity(timestamp = baseDay, heure = 23, zone_nom = "Hopital", action = "APP_OPEN", device_id = "DEV-NIGHT-SHIFT"))
            dao.insertEvent(BehaviorEventEntity(timestamp = baseDay + 3_600_000L, heure = 0, zone_nom = "Hopital", action = "ZONE_TRANSITION", device_id = "DEV-NIGHT-SHIFT"))
        }

        manager.computeAndSyncBaseline(now)
        val baseline = manager.baselineFlow.value

        assertTrue("Night-shift baseline should be established", baseline.isBaselineEstablished)
        assertTrue("Hour 23 must be learned", 23 in baseline.learnedFrequentHours)
        assertTrue("Hour 0 must be learned", 0 in baseline.learnedFrequentHours)
        assertTrue("Zone Hopital must be learned", "Hopital" in baseline.learnedFrequentZones)

        // Old hardcoded 06:00-22:00 window would have flagged 23h and accepted 14h!
        // Real learned baseline accepts 23h at Hopital and flags unlearned 14h or unknown zone:
        val (withinNight, _) = manager.isWithinBaseline(23, "Hopital", UserRole.OWNER, "en")
        assertTrue("Learned 23h at Hopital must be within baseline", withinNight)

        val (withinDaytime, dayMsg) = manager.isWithinBaseline(14, "Hopital", UserRole.OWNER, "en")
        assertFalse("Unlearned 14h must be flagged outside night-shift baseline", withinDaytime)
        assertTrue(dayMsg.contains("Outside baseline"))

        val (withinUnknownZone, zoneMsg) = manager.isWithinBaseline(23, "Parc", UserRole.OWNER, "en")
        assertFalse("Unlearned zone Parc must be flagged", withinUnknownZone)
        assertTrue(zoneMsg.contains("Outside baseline"))
    }

    @Test
    fun testAntiPoisoningRollingWindowDecayAndDeviceIsolation() = runBlocking {
        val dao = db.behaviorEventDao()
        val manager = BehaviorLearningManager(dao, "DEV-OWNER-1")
        val now = System.currentTimeMillis()
        val dayMs = BehaviorLearningManager.DAY_MILLIS

        // 1. Insert legitimate 7-day baseline at hour 9 in "Maison"
        for (d in 1..7) {
            val baseDay = now - (d * dayMs)
            dao.insertEvent(BehaviorEventEntity(timestamp = baseDay, heure = 9, zone_nom = "Maison", action = "APP_OPEN", device_id = "DEV-OWNER-1"))
            dao.insertEvent(BehaviorEventEntity(timestamp = baseDay + 600_000L, heure = 9, zone_nom = "Maison", action = "CHARGING_CONNECTED", device_id = "DEV-OWNER-1"))
        }

        // 2. Single-day attacker burst (20 observations today at hour 4 in "RogueZone") -> must NOT poison learned hours/zones
        for (k in 0 until 20) {
            dao.insertEvent(BehaviorEventEntity(timestamp = now - (k * 10_000L), heure = 4, zone_nom = "RogueZone", action = "APP_OPEN", device_id = "DEV-OWNER-1"))
        }

        // 3. Stale observation older than 30 days (day 40) at hour 17 in "OldOffice" -> must fall outside rolling window
        dao.insertEvent(BehaviorEventEntity(timestamp = now - (40L * dayMs), heure = 17, zone_nom = "OldOffice", action = "APP_OPEN", device_id = "DEV-OWNER-1"))
        dao.insertEvent(BehaviorEventEntity(timestamp = now - (41L * dayMs), heure = 17, zone_nom = "OldOffice", action = "APP_OPEN", device_id = "DEV-OWNER-1"))

        // 4. Another device's observations -> must NOT leak into DEV-OWNER-1
        for (d in 1..7) {
            dao.insertEvent(BehaviorEventEntity(timestamp = now - (d * dayMs), heure = 19, zone_nom = "OtherDeviceZone", action = "APP_OPEN", device_id = "DEV-OTHER-2"))
            dao.insertEvent(BehaviorEventEntity(timestamp = now - (d * dayMs) + 1000L, heure = 19, zone_nom = "OtherDeviceZone", action = "APP_OPEN", device_id = "DEV-OTHER-2"))
        }

        manager.computeAndSyncBaseline(now)
        val profile = manager.baselineFlow.value

        assertTrue(9 in profile.learnedFrequentHours)
        assertTrue("Maison" in profile.learnedFrequentZones)
        assertFalse("Single-day burst hour 4 must NOT poison baseline", 4 in profile.learnedFrequentHours)
        assertFalse("Single-day burst zone must NOT poison baseline", "RogueZone" in profile.learnedFrequentZones)
        assertFalse("Expired >30-day hour 17 must be pruned", 17 in profile.learnedFrequentHours)
        assertFalse("Expired >30-day zone must be pruned", "OldOffice" in profile.learnedFrequentZones)
        assertFalse("Other device hour must stay isolated", 19 in profile.learnedFrequentHours)
        assertFalse("Other device zone must stay isolated", "OtherDeviceZone" in profile.learnedFrequentZones)
    }

    @Test
    fun testMissingZonesNeverInventDeviations() = runBlocking {
        val dao = db.behaviorEventDao()
        val manager = BehaviorLearningManager(dao, "DEV-NO-ZONES")
        val now = System.currentTimeMillis()
        val dayMs = BehaviorLearningManager.DAY_MILLIS

        // 7 days of valid hours (hour 10) with no named zones ("Zone inconnue")
        for (d in 1..7) {
            dao.insertEvent(BehaviorEventEntity(timestamp = now - (d * dayMs), heure = 10, zone_nom = "Zone inconnue", action = "APP_OPEN", device_id = "DEV-NO-ZONES"))
            dao.insertEvent(BehaviorEventEntity(timestamp = now - (d * dayMs) + 60_000L, heure = 10, zone_nom = "Zone inconnue", action = "CHARGING_CONNECTED", device_id = "DEV-NO-ZONES"))
        }

        manager.computeAndSyncBaseline(now)
        val profile = manager.baselineFlow.value
        assertTrue(profile.learnedFrequentHours.contains(10))
        assertTrue(profile.learnedFrequentZones.isEmpty())

        // Known learned hour + arbitrary zone must NOT invent a false zone anomaly when no zones were learned
        val (within, _) = manager.isWithinBaseline(10, "Centre Ville", UserRole.OWNER, "en")
        assertTrue("Must not invent zone deviation when learnedFrequentZones is empty", within)
    }

    @Test
    fun testLearningPausePersistsAcrossRestartAndStopsBothLearnersWhileKeepingSecurityActive() = runBlocking {
        val container1 = InkingiContainer(context, db)
        val vm1 = InkingiViewModel(container1)

        assertFalse(vm1.uiState.value.isLearningPaused)
        vm1.pauseBaselineLearning()
        assertTrue(vm1.uiState.value.isLearningPaused)
        assertTrue(DeviceSettingsRepository.isLearningPaused(context))
        assertTrue(container1.behaviorLearningManager.isLearningPaused())
        assertTrue(container1.securityBrain.baselineProfile.value.isPaused)

        // Simulate app/process restart with a new InkingiContainer & InkingiViewModel sharing the same prefs/db
        val container2 = InkingiContainer(context, db)
        val vm2 = InkingiViewModel(container2)

        assertTrue("Paused state must survive restart", vm2.uiState.value.isLearningPaused)
        assertTrue(container2.behaviorLearningManager.isLearningPaused())
        assertTrue(container2.securityBrain.baselineProfile.value.isPaused)

        // While paused, neither BehaviorLearningManager nor BehavioralLearningEngine records new observations
        val beforeRoomCount = db.behaviorEventDao().getTotalEventCount()
        val recordedRoom = container2.behaviorLearningManager.recordObservation("Maison", UserRole.OWNER, "ZONE_TRANSITION")
        assertFalse(recordedRoom)
        assertEquals(beforeRoomCount, db.behaviorEventDao().getTotalEventCount())

        val beforeEngineSamples = container2.securityBrain.baselineProfile.value.sampleCount
        container2.securityBrain.evaluateAndRecordBehavioralObservation(
            BehavioralObservation(simCarrier = "MTN", networkType = "WIFI", role = UserRole.OWNER)
        )
        assertEquals(beforeEngineSamples, container2.securityBrain.baselineProfile.value.sampleCount)

        // Core security & GPS & STOLEN_MODE protection remain fully active even while learning is paused
        assertTrue("Owner PIN enrollment must succeed", vm2.enrollMasterPin("8492"))
        assertTrue("Owner theft confirmation must still work when learning is paused", vm2.confirmStolenByOwner("8492"))
        repeat(10) {
            delay(50)
            ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
            if (DeviceSettingsRepository.isStolenTrackingActive(context)) return@repeat
        }
        assertTrue(DeviceSettingsRepository.isStolenTrackingActive(context))

        vm2.disarmWithPin("8492")
        repeat(10) {
            delay(50)
            ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
            if (!DeviceSettingsRepository.isStolenTrackingActive(context)) return@repeat
        }
        vm2.resumeBaselineLearning()
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertFalse(vm2.uiState.value.isLearningPaused)
        assertFalse(DeviceSettingsRepository.isLearningPaused(context))
    }

    @Test
    fun testPolicyAndBrainThresholdsAlignedAndUnifiedScoringDrivesUiState() = runBlocking {
        val policyEngine = SecurityPolicyEngine()

        // Score 75 must be REJECTED for SUSPECTED_LOST (aligned threshold is 80)
        val underThreshold = policyEngine.evaluateAction(
            ProposedAction(
                actionType = ActionType.CHANGE_SECURITY_STATE,
                deviceId = "DEV-1",
                requestingActor = "SecurityBrain",
                targetState = SecurityState.SUSPECTED_LOST,
                currentRiskScore = 75
            )
        )
        assertEquals(PolicyDecision.REJECTED, underThreshold.decision)

        // Score 80 is APPROVED
        val atThreshold = policyEngine.evaluateAction(
            ProposedAction(
                actionType = ActionType.CHANGE_SECURITY_STATE,
                deviceId = "DEV-1",
                requestingActor = "SecurityBrain",
                targetState = SecurityState.SUSPECTED_LOST,
                currentRiskScore = 80
            )
        )
        assertEquals(PolicyDecision.APPROVED, atThreshold.decision)

        // Single strong signal (AUTH_FAILURES = 40 pts, distinctFactorCount = 1) must NOT reach SUSPECTED_LOST
        val brain = SecurityBrain(
            device = OntologyDevice("DEV-1", "owner@test.com", "Phone", "Pixel", "15"),
            owner = OntologyOwner("owner@test.com", "owner@test.com", "Owner")
        )
        val outcomeSingle = brain.ingestSecurityEvent(
            event = NormalizedSecurityEvent(
                eventId = "E1",
                deviceId = "DEV-1",
                ownerId = "owner@test.com",
                category = EventCategory.AUTHENTICATION_ACCESS,
                source = "Auth",
                severity = EventSeverity.WARNING,
                baseRiskImpact = 40,
                title = "Failed auth",
                details = "4 failures"
            ),
            signals = ConfigurableRiskScoringEngine.RawSignals(failedAuthAttempts = 4)
        )
        assertEquals(40, outcomeSingle.updatedRiskEvaluation.totalScore)
        assertEquals(SecurityState.WARNING, brain.currentState.value)

        // Behavioral deviation in GUEST role is suppressed and capped cleanly
        val container = InkingiContainer(context, db)
        val vm = InkingiViewModel(container)
        vm.switchUserRole(UserRole.GUEST)
        vm.simulateSimAlteration()
        vm.simulateDisplacementOutsideZone()
        delay(450)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        assertEquals(container.securityBrain.currentThreatState.value, vm.uiState.value.threatState)
        assertEquals(
            "UI Risk score must exactly match SecurityBrain score (no +20 overwrite)",
            container.securityBrain.currentRiskEvaluation.value.totalScore,
            vm.uiState.value.riskAssessment.score
        )
        assertFalse(
            "Guest session must never escalate to SUSPECTED_LOST or STOLEN_MODE",
            vm.uiState.value.securityState == SecurityState.SUSPECTED_LOST || vm.uiState.value.securityState == SecurityState.STOLEN_MODE
        )
    }

    @Test
    fun testAutonomousEmergencyLockGuard1AndBatteryAndDedup() = runBlocking {
        val container = InkingiContainer(context, db)
        val vm = InkingiViewModel(container)

        // Guard 1: In NORMAL state, even with healthy battery, autonomous lock is rejected
        vm.simulateBatteryPercentage(60, isCharging = false)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        assertNull("NORMAL state must never allow autonomous lock", vm.triggerAutonomousEmergencyLock())

        // Set battery to <= 15% BEFORE triggering SUSPECTED_LOST so automatic transition check also respects Guard 4
        vm.simulateBatteryPercentage(15, isCharging = false)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)

        // Trigger multi-signal convergence (>= 80, >= 2 independent signals) via SecurityBrain -> SUSPECTED_LOST
        val evt = NormalizedSecurityEvent(
            eventId = "E-CRIT",
            deviceId = container.deviceSettingsRepository.deviceProfile.value.deviceId,
            ownerId = "owner@test.com",
            category = EventCategory.SIM_TELEPHONY,
            source = "SimEngine",
            severity = EventSeverity.CRITICAL,
            baseRiskImpact = 30,
            title = "SIM + Auth + Perimeter Convergence",
            details = "Multi-signal theft risk"
        )
        val critSignals = ConfigurableRiskScoringEngine.RawSignals(
            isSimChanged = true,       // 30
            failedAuthAttempts = 4,    // 40
            isOutsideTrustedZone = true // 20 -> total 90, 3 factors
        )
        container.securityBrain.ingestSecurityEvent(evt, critSignals)
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS)
        assertEquals(SecurityState.SUSPECTED_LOST, vm.uiState.value.securityState)

        // Guard 4: Even in SUSPECTED_LOST with score 90, battery <= 15% strictly prohibits locking and does NOT consume dedup slot
        assertNull("Battery <= 15% must block autonomous lock", vm.triggerAutonomousEmergencyLock())

        // Battery > 15% in SUSPECTED_LOST with score >= 80 & >= 2 factors passes guards (returns real lock capability result, not null)
        vm.simulateBatteryPercentage(45, isCharging = false)
        ShadowLooper.idleMainLooper(200, TimeUnit.MILLISECONDS)
        val firstAttempt = vm.triggerAutonomousEmergencyLock()
        assertNotNull("All 4 guards satisfied -> should attempt real hardware lock or report real Unavailable", firstAttempt)

        // Guard 3: Second call for the same active incident cluster must be suppressed as duplicate
        val duplicateAttempt = vm.triggerAutonomousEmergencyLock()
        assertNull("Duplicate lock in the same incident cluster must be suppressed", duplicateAttempt)
    }

    @Test
    fun testEventCorrelationEngineConcurrentMutexSafety() = runBlocking {
        val correlationEngine = EventCorrelationEngine(
            correlationWindowMillis = 15 * 60 * 1000L,
            fingerprintDao = db.eventFingerprintDao()
        )
        // Exact multiple of 30_000L so all burst events (+0..900ms) fall into the same 30s deduplication bucket
        val baseTime = 300_000_000L

        // Fire 20 concurrent events: 10 bursts of identical SIM event (should dedup to 1) + 10 distinct categories
        val jobs = (0 until 10).map { i ->
            async {
                val dupBurst = NormalizedSecurityEvent(
                    eventId = "DUP-$i",
                    deviceId = "DEV-CONC",
                    ownerId = "owner@test.com",
                    category = EventCategory.SIM_TELEPHONY,
                    source = "Telephony",
                    timestamp = baseTime + (i * 100L),
                    severity = EventSeverity.CRITICAL,
                    baseRiskImpact = 30,
                    title = "SIM Ejected",
                    details = "Concurrent burst $i"
                )
                correlationEngine.processEvent(dupBurst, currentRiskScore = 60)
            }
        } + (0 until 10).map { j ->
            async {
                val distinctEvt = NormalizedSecurityEvent(
                    eventId = "DIST-$j",
                    deviceId = "DEV-CONC",
                    ownerId = "owner@test.com",
                    category = EventCategory.GEOLOCATION_DISPLACEMENT,
                    source = "Gps",
                    timestamp = baseTime + 500L + (j * 100L),
                    severity = EventSeverity.WARNING,
                    baseRiskImpact = 20,
                    title = "Perimeter Exit $j",
                    details = "Move $j"
                )
                correlationEngine.processEvent(distinctEvt, currentRiskScore = 75)
            }
        }

        val outcomes = jobs.awaitAll()
        assertEquals(20, outcomes.size)

        val simProcessed = outcomes.take(10).count { !it.isDuplicateEvent }
        assertEquals("Only 1 SIM burst event should pass 30s deduplication", 1, simProcessed)

        val active = correlationEngine.getActiveIncidentForDevice("DEV-CONC")
        assertNotNull("Concurrent cluster should yield one active incident", active)
        assertEquals(11, active!!.associatedEvents.size)

        val resolved = correlationEngine.resolveIncident(active.incidentId, baseTime + 5000L)
        assertNotNull(resolved)
        assertEquals(IncidentStatus.RESOLVED, resolved!!.status)
        assertNull(correlationEngine.getActiveIncidentForDevice("DEV-CONC"))
    }
}
