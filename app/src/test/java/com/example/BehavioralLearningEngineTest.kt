package com.example

import com.example.core.brain.baseline.BaselineProfile
import com.example.core.brain.baseline.BehavioralLearningEngine
import com.example.core.brain.baseline.BehavioralObservation
import com.example.core.brain.correlation.EventCorrelationEngine
import com.example.core.brain.gateways.MockCloudSyncGateway
import com.example.core.brain.gateways.MockEvidenceStorageGateway
import com.example.core.brain.gateways.MockGpsLocationGateway
import com.example.core.brain.gateways.MockNotificationAlertGateway
import com.example.core.brain.gateways.MockSensorTelemetryGateway
import com.example.core.brain.model.EventCategory
import com.example.core.brain.model.OntologyDevice
import com.example.core.brain.model.OntologyOwner
import com.example.core.brain.model.PolicyDecision
import com.example.core.brain.orchestration.SecurityBrain
import com.example.core.brain.policy.SecurityPolicyEngine
import com.example.core.brain.risk.ConfigurableRiskScoringEngine
import com.example.core.model.EventSeverity
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
class BehavioralLearningEngineTest {

    private lateinit var engine: BehavioralLearningEngine

    private val testDevice = OntologyDevice(
        deviceId = "DEV-LEARN-NODE-01",
        ownerId = "officer@inkingiguard.com",
        name = "Pixel 8 Pro Node",
        model = "Pixel 8 Pro",
        osVersion = "Android 14"
    )

    private val testOwner = OntologyOwner(
        ownerId = "officer@inkingiguard.com",
        email = "officer@inkingiguard.com",
        displayName = "Security Officer",
        enrolledDeviceIds = listOf("DEV-LEARN-NODE-01")
    )

    @Before
    fun setUp() {
        // minRequiredSamples = 10, staleThreshold = 7 days
        engine = BehavioralLearningEngine(
            minRequiredSamples = 10,
            staleThresholdMillis = 7 * 24 * 60 * 60 * 1000L
        )
    }

    // =========================================================================
    // 1. INSUFFICIENT SAMPLES TESTS
    // =========================================================================

    @Test
    fun testInsufficientSamplesDoesNotEstablishBaseline() {
        val baseTime = 1_000_000L

        // Record 5 observations (less than minRequiredSamples = 10)
        for (i in 1..5) {
            engine.recordObservation(
                BehavioralObservation(
                    simCarrier = "MTN Rwanda",
                    networkType = "WIFI_HQ",
                    isNetworkConnected = true,
                    isRebootAnomaly = false,
                    failedAuthAttempts = 0,
                    accelerationMagnitude = 9.8f,
                    latitude = -1.9441,
                    longitude = 30.0619,
                    isInsideTrustedZone = true,
                    timestamp = baseTime + (i * 60_000L)
                )
            )
        }

        val profile = engine.getProfile()
        assertEquals(5, profile.sampleCount)
        assertFalse("Baseline must not be established with only 5 samples", profile.isBaselineEstablished)
        assertTrue("Confidence must be fractional (<0.5) when samples are insufficient", profile.confidence < 0.5f)
        assertFalse(profile.isStale)
        assertFalse(profile.isPaused)

        // Even with a completely foreign observation, deviations MUST be empty because baseline is unestablished
        val testObs = BehavioralObservation(
            simCarrier = "UNKNOWN_ROGUE_CARRIER",
            networkType = "FOREIGN_CELL",
            failedAuthAttempts = 5,
            accelerationMagnitude = 30.0f,
            latitude = 50.0,
            longitude = 10.0,
            isInsideTrustedZone = false,
            timestamp = baseTime + 400_000L
        )

        val deviations = engine.evaluateDeviations(testObs, now = baseTime + 400_000L)
        assertTrue("Insufficient sample profile must return empty deviations to prevent false alarms", deviations.isEmpty())
    }

    @Test
    fun testSafelyHandlesMissingPermissionsAndNullTelemetry() {
        // Safe by design: handles null telemetry signals without crashing or corrupting profile
        val emptyObs = BehavioralObservation(
            simCarrier = null,
            networkType = null,
            accelerationMagnitude = null,
            latitude = null,
            longitude = null,
            isInsideTrustedZone = null,
            timestamp = 500_000L
        )

        engine.recordObservation(emptyObs)
        val profile = engine.getProfile()
        assertEquals(1, profile.sampleCount)
        assertTrue(profile.knownCarriers.isEmpty())
        assertTrue(profile.knownNetworkTypes.isEmpty())
        assertEquals(null, profile.centroidLatitude)
        assertEquals(null, profile.centroidLongitude)

        val deviations = engine.evaluateDeviations(emptyObs, now = 500_000L)
        assertTrue(deviations.isEmpty())
    }

    // =========================================================================
    // 2. NORMAL OBSERVATIONS TESTS
    // =========================================================================

    private fun trainNominalBaseline(startTime: Long = 1_000_000L) {
        for (i in 1..12) {
            engine.recordObservation(
                BehavioralObservation(
                    simCarrier = "MTN Rwanda",
                    networkType = "WIFI_HQ",
                    isNetworkConnected = true,
                    isRebootAnomaly = false,
                    failedAuthAttempts = 0,
                    accelerationMagnitude = 9.8f,
                    latitude = -1.9441,
                    longitude = 30.0619,
                    isInsideTrustedZone = true,
                    timestamp = startTime + (i * 60_000L)
                )
            )
        }
    }

    @Test
    fun testNormalObservationsProduceNoDeviations() {
        val baseTime = 2_000_000L
        trainNominalBaseline(baseTime)

        val profile = engine.getProfile()
        assertTrue("Baseline must be established after 12 samples (min=10)", profile.isBaselineEstablished)
        assertEquals(12, profile.sampleCount)
        assertTrue("Confidence must be at least 0.6", profile.confidence >= 0.6f)
        assertTrue("MTN Rwanda must be in known carriers", profile.knownCarriers.contains("MTN Rwanda"))
        assertTrue("WIFI_HQ must be in known network types", profile.knownNetworkTypes.contains("WIFI_HQ"))

        // An observation matching normal patterns
        val normalObs = BehavioralObservation(
            simCarrier = "MTN Rwanda",
            networkType = "WIFI_HQ",
            isNetworkConnected = true,
            isRebootAnomaly = false,
            failedAuthAttempts = 0,
            accelerationMagnitude = 9.82f,
            latitude = -1.9442,
            longitude = 30.0620,
            isInsideTrustedZone = true,
            timestamp = baseTime + 800_000L
        )

        val deviations = engine.evaluateDeviations(normalObs, now = baseTime + 800_000L)
        assertTrue("Nominal baseline observation must produce zero deviations", deviations.isEmpty())
    }

    // =========================================================================
    // 3. ANOMALOUS OBSERVATIONS TESTS
    // =========================================================================

    @Test
    fun testDetectsSimCarrierDeviationExplainably() {
        val baseTime = 3_000_000L
        trainNominalBaseline(baseTime)

        val rogueSimObs = BehavioralObservation(
            simCarrier = "AIRTEL_FOREIGN_SIM",
            networkType = "WIFI_HQ",
            isNetworkConnected = true,
            isRebootAnomaly = false,
            failedAuthAttempts = 0,
            accelerationMagnitude = 9.8f,
            timestamp = baseTime + 900_000L
        )

        val deviations = engine.evaluateDeviations(rogueSimObs, now = baseTime + 900_000L)
        assertEquals(1, deviations.size)
        val dev = deviations[0]
        assertEquals("BEHAVIORAL_SIM_DEVIATION", dev.factorKey)
        assertEquals(EventCategory.SIM_TELEPHONY, dev.category)
        assertEquals(EventSeverity.CRITICAL, dev.severity)
        assertEquals(30, dev.deviationScore)
        assertTrue("Explanation must mention observed SIM", dev.explanation.contains("AIRTEL_FOREIGN_SIM"))
        assertTrue("Explanation must mention learned baseline", dev.explanation.contains("MTN Rwanda"))
    }

    @Test
    fun testDetectsAuthenticationSpikeAnomaly() {
        val baseTime = 4_000_000L
        trainNominalBaseline(baseTime)

        val authSpikeObs = BehavioralObservation(
            simCarrier = "MTN Rwanda",
            networkType = "WIFI_HQ",
            failedAuthAttempts = 4, // Exceeds learned average of 0.0 by > 2.0
            timestamp = baseTime + 900_000L
        )

        val deviations = engine.evaluateDeviations(authSpikeObs, now = baseTime + 900_000L)
        assertEquals(1, deviations.size)
        val dev = deviations[0]
        assertEquals("BEHAVIORAL_AUTH_SPIKE", dev.factorKey)
        assertEquals(EventCategory.AUTHENTICATION_ACCESS, dev.category)
        assertEquals(EventSeverity.WARNING, dev.severity)
        assertEquals(35, dev.deviationScore)
        assertTrue(dev.explanation.contains("4 authentication failures"))
    }

    @Test
    fun testDetectsUnexpectedRebootAnomaly() {
        val baseTime = 5_000_000L
        trainNominalBaseline(baseTime)

        val rebootObs = BehavioralObservation(
            simCarrier = "MTN Rwanda",
            networkType = "WIFI_HQ",
            isRebootAnomaly = true,
            timestamp = baseTime + 900_000L
        )

        val deviations = engine.evaluateDeviations(rebootObs, now = baseTime + 900_000L)
        assertEquals(1, deviations.size)
        val dev = deviations[0]
        assertEquals("BEHAVIORAL_REBOOT_ANOMALY", dev.factorKey)
        assertEquals(EventCategory.SYSTEM_LIFECYCLE, dev.category)
        assertEquals(EventSeverity.WARNING, dev.severity)
        assertEquals(20, dev.deviationScore)
    }

    @Test
    fun testDetectsKineticDisruptionAnomaly() {
        val baseTime = 6_000_000L
        trainNominalBaseline(baseTime)

        // Baseline motion variance is low (~0.1-1.0), mean is 9.8.
        // A violent snatch / high impact at 25.0 m/s^2 is > 3 sigma.
        val kineticObs = BehavioralObservation(
            simCarrier = "MTN Rwanda",
            networkType = "WIFI_HQ",
            accelerationMagnitude = 26.5f,
            timestamp = baseTime + 900_000L
        )

        val deviations = engine.evaluateDeviations(kineticObs, now = baseTime + 900_000L)
        assertEquals(1, deviations.size)
        val dev = deviations[0]
        assertEquals("BEHAVIORAL_KINETIC_DISRUPTION", dev.factorKey)
        assertEquals(EventCategory.KINETIC_MOTION, dev.category)
        assertEquals(EventSeverity.WARNING, dev.severity)
        assertEquals(15, dev.deviationScore)
        assertTrue(dev.explanation.contains("26.5 m/s²"))
    }

    @Test
    fun testDetectsLocationDispersionOutsideCentroidAndTrustedZone() {
        val baseTime = 7_000_000L
        trainNominalBaseline(baseTime)

        // Coordinates at Kigali HQ are roughly lat=-1.9441, lon=30.0619
        // A location far away in Rubavu (-1.6792, 29.2608), >100 km away and marked outside trusted zone
        val locationObs = BehavioralObservation(
            simCarrier = "MTN Rwanda",
            networkType = "WIFI_HQ",
            latitude = -1.6792,
            longitude = 29.2608,
            isInsideTrustedZone = false,
            timestamp = baseTime + 900_000L
        )

        val deviations = engine.evaluateDeviations(locationObs, now = baseTime + 900_000L)
        assertEquals(1, deviations.size)
        val dev = deviations[0]
        assertEquals("BEHAVIORAL_LOCATION_DISPERSION", dev.factorKey)
        assertEquals(EventCategory.GEOLOCATION_DISPLACEMENT, dev.category)
        assertEquals(EventSeverity.WARNING, dev.severity)
        assertEquals(20, dev.deviationScore)
    }

    @Test
    fun testDetectsRadioLinkSeveredAnomaly() {
        val baseTime = 8_000_000L
        trainNominalBaseline(baseTime)

        val severedObs = BehavioralObservation(
            simCarrier = "MTN Rwanda",
            networkType = "WIFI_HQ",
            isNetworkConnected = false,
            timestamp = baseTime + 900_000L
        )

        val deviations = engine.evaluateDeviations(severedObs, now = baseTime + 900_000L)
        assertEquals(1, deviations.size)
        val dev = deviations[0]
        assertEquals("BEHAVIORAL_RADIO_SEVERED", dev.factorKey)
        assertEquals(EventCategory.NETWORK_CONNECTIVITY, dev.category)
        assertEquals(10, dev.deviationScore)
    }

    // =========================================================================
    // 4. STALE DATA TESTS
    // =========================================================================

    @Test
    fun testStaleDataSuppressesFalsePositiveAnomalies() {
        val baseTime = 9_000_000L
        trainNominalBaseline(baseTime)

        assertTrue("Profile should be fresh initially", engine.isFresh(baseTime + 10_000L))

        // Advance time by 8 days (> 7 days stale threshold)
        val eightDaysLater = baseTime + (8 * 24 * 60 * 60 * 1000L)
        assertFalse("Profile must be considered stale after 8 days of inactivity", engine.isFresh(eightDaysLater))

        val obs = BehavioralObservation(
            simCarrier = "ROGUE_SIM_NEW",
            timestamp = eightDaysLater
        )

        // When evaluating on stale data, engine marks isStale=true and suppresses deviations
        val deviations = engine.evaluateDeviations(obs, now = eightDaysLater)
        assertTrue("Deviations must be suppressed on stale baseline to avoid obsolete false alarms", deviations.isEmpty())
        assertTrue("Profile must be marked stale", engine.getProfile().isStale)

        // When a fresh observation is recorded, staleness is cleared
        engine.recordObservation(
            BehavioralObservation(
                simCarrier = "MTN Rwanda",
                networkType = "WIFI_HQ",
                timestamp = eightDaysLater + 1000L
            )
        )
        assertFalse("Recording fresh observation clears stale state", engine.getProfile().isStale)
    }

    // =========================================================================
    // 5. PAUSE AND RESET TESTS
    // =========================================================================

    @Test
    fun testPauseAndResumeBehavior() {
        val baseTime = 10_000_000L
        trainNominalBaseline(baseTime)

        engine.pause()
        assertTrue("Engine must report paused state", engine.isPaused())
        assertTrue(engine.getProfile().isPaused)

        // While paused, observations are NOT recorded
        val currentCount = engine.getProfile().sampleCount
        engine.recordObservation(
            BehavioralObservation(
                simCarrier = "NEW_CARRIER",
                timestamp = baseTime + 950_000L
            )
        )
        assertEquals("Sample count must not increment while paused", currentCount, engine.getProfile().sampleCount)

        // While paused, evaluateDeviations returns empty list
        val deviations = engine.evaluateDeviations(
            BehavioralObservation(
                simCarrier = "ROGUE_SIM",
                failedAuthAttempts = 5,
                timestamp = baseTime + 960_000L
            ),
            now = baseTime + 960_000L
        )
        assertTrue("Paused engine must yield zero deviations", deviations.isEmpty())

        // Resume engine
        engine.resume()
        assertFalse("Engine must no longer be paused", engine.isPaused())
        assertFalse(engine.getProfile().isPaused)

        // After resume, observations are recorded again
        engine.recordObservation(
            BehavioralObservation(
                simCarrier = "MTN Rwanda",
                timestamp = baseTime + 970_000L
            )
        )
        assertEquals(currentCount + 1, engine.getProfile().sampleCount)
    }

    @Test
    fun testResetClearsBaselineCompletely() {
        val baseTime = 11_000_000L
        trainNominalBaseline(baseTime)
        assertTrue(engine.getProfile().isBaselineEstablished)
        assertTrue(engine.getProfile().knownCarriers.isNotEmpty())

        engine.reset()

        val resetProfile = engine.getProfile()
        assertEquals(0, resetProfile.sampleCount)
        assertFalse(resetProfile.isBaselineEstablished)
        assertEquals(0.0f, resetProfile.confidence, 0.001f)
        assertTrue(resetProfile.knownCarriers.isEmpty())
        assertTrue(resetProfile.knownNetworkTypes.isEmpty())
        assertEquals(null, resetProfile.centroidLatitude)
        assertEquals(null, resetProfile.centroidLongitude)
        assertEquals(0.0, resetProfile.maxObservedRadiusMeters, 0.001)
    }

    // =========================================================================
    // 6. ENSURING ANOMALIES ALONE CANNOT CONFIRM THEFT
    // =========================================================================

    @Test
    fun testAnomaliesAloneCannotConfirmTheftOrEngageStolenMode() = runBlocking {
        val correlationEngine = EventCorrelationEngine(correlationWindowMillis = 15 * 60 * 1000L)
        val riskScoringEngine = ConfigurableRiskScoringEngine()
        val policyEngine = SecurityPolicyEngine()
        val learningEngine = BehavioralLearningEngine(minRequiredSamples = 5)

        val securityBrain = SecurityBrain(
            device = testDevice,
            owner = testOwner,
            correlationEngine = correlationEngine,
            riskScoringEngine = riskScoringEngine,
            policyEngine = policyEngine,
            behavioralLearningEngine = learningEngine,
            cloudSyncGateway = MockCloudSyncGateway(),
            alertGateway = MockNotificationAlertGateway(),
            evidenceGateway = MockEvidenceStorageGateway()
        )

        val baseTime = 12_000_000L

        // Train baseline in SecurityBrain
        for (i in 1..8) {
            securityBrain.ingestBehavioralObservation(
                BehavioralObservation(
                    simCarrier = "MTN Rwanda",
                    networkType = "WIFI_OFFICE",
                    isNetworkConnected = true,
                    failedAuthAttempts = 0,
                    accelerationMagnitude = 9.8f,
                    latitude = -1.9441,
                    longitude = 30.0619,
                    isInsideTrustedZone = true,
                    timestamp = baseTime + (i * 60_000L)
                )
            )
        }

        assertTrue("Baseline established in SecurityBrain", securityBrain.baselineProfile.value.isBaselineEstablished)
        assertEquals(SecurityState.NORMAL, securityBrain.currentState.value)

        // Inject severe combined behavioral anomaly: rogue SIM, 5 auth failures, severe kinetic snatch, outside zone
        val highlyAnomalousObs = BehavioralObservation(
            simCarrier = "THIEF_SIM_CARD_UNKNOWN",
            networkType = "WIFI_OFFICE",
            isNetworkConnected = false,
            isRebootAnomaly = true,
            failedAuthAttempts = 5,
            accelerationMagnitude = 28.0f,
            latitude = -1.5000,
            longitude = 29.0000,
            isInsideTrustedZone = false,
            timestamp = baseTime + 600_000L
        )

        val outcomes = securityBrain.ingestBehavioralObservation(highlyAnomalousObs, now = baseTime + 600_000L)
        assertTrue("Anomalies must be generated and ingested", outcomes.isNotEmpty())

        val currentRisk = securityBrain.currentRiskEvaluation.value.totalScore
        assertTrue("Risk score must escalate under anomalies (current=$currentRisk)", currentRisk >= 50)

        // CRITICAL INVARIANT: State may escalate to WARNING, DANGER, or SUSPECTED_LOST,
        // but NEVER STOLEN_MODE or OWNER_CONFIRMED_STOLEN!
        val stateAfterAnomalies = securityBrain.currentState.value
        assertFalse(
            "Anomalies ALONE must NEVER trigger STOLEN_MODE automatically",
            stateAfterAnomalies == SecurityState.STOLEN_MODE
        )
        assertFalse(
            "Anomalies ALONE must NEVER trigger OWNER_CONFIRMED_STOLEN automatically",
            stateAfterAnomalies == SecurityState.OWNER_CONFIRMED_STOLEN
        )
        assertTrue(
            "State should escalate to DANGER or SUSPECTED_LOST based on risk evidence",
            stateAfterAnomalies == SecurityState.DANGER || stateAfterAnomalies == SecurityState.SUSPECTED_LOST
        )

        // Attempting to declare stolen WITHOUT explicit owner confirmation must FAIL CLOSED
        val unconfirmedResult = securityBrain.requestDeclareStolen(
            isOwnerConfirmed = false,
            authorizationToken = "ANY_TOKEN"
        )
        assertEquals(
            "Policy must challenge or reject unconfirmed theft declarations",
            PolicyDecision.CHALLENGE_REQUIRED,
            unconfirmedResult.decision
        )
        assertFalse(
            "State remains unconfirmed stolen without owner verification",
            securityBrain.currentState.value == SecurityState.STOLEN_MODE
        )

        // Only with explicit owner confirmation and authorization token does it activate STOLEN_MODE
        val confirmedResult = securityBrain.requestDeclareStolen(
            isOwnerConfirmed = true,
            authorizationToken = "VERIFIED_MASTER_PIN_AUTH"
        )
        assertEquals(PolicyDecision.APPROVED, confirmedResult.decision)
        assertEquals(SecurityState.STOLEN_MODE, securityBrain.currentState.value)
    }

    // =========================================================================
    // 7. PRIVACY-PRESERVING ZERO PRIVATE DATA VERIFICATION
    // =========================================================================

    @Test
    fun testPrivacyPreservingDesignContainsNoPrivateData() {
        // Assert that BehavioralObservation telemetry structure only holds security telemetry signals
        val obs = BehavioralObservation(
            simCarrier = "MTN",
            networkType = "LTE",
            isNetworkConnected = true,
            isRebootAnomaly = false,
            failedAuthAttempts = 0,
            accelerationMagnitude = 9.8f,
            latitude = -1.94,
            longitude = 30.06,
            isInsideTrustedZone = true
        )

        assertNotNull(obs)
        // Baseline profile only computes aggregated statistics:
        val profile = BaselineProfile()
        assertTrue(profile.knownCarriers.isEmpty())
        assertEquals(0, profile.sampleCount)
        assertEquals(0.0f, profile.confidence, 0.001f)
    }

    // =========================================================================
    // 8. BASELINE PERSISTENCE AND RELOAD VIA VAULT VERIFICATION
    // =========================================================================

    @Test
    fun testBaselinePersistenceAndReloadPreservesStateAccurately() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val vault = com.example.domain.security.OfflineSecurityVault(context)

        // 1. Train first engine instance backed by real vault
        val engine1 = BehavioralLearningEngine(
            minRequiredSamples = 10,
            persistenceVault = vault
        )

        val baseTime = 15_000_000L
        for (i in 1..15) {
            engine1.recordObservation(
                BehavioralObservation(
                    simCarrier = "MTN Rwanda",
                    networkType = "WIFI_OFFICE",
                    isNetworkConnected = true,
                    failedAuthAttempts = 0,
                    accelerationMagnitude = 9.81f,
                    latitude = -1.9441,
                    longitude = 30.0619,
                    isInsideTrustedZone = true,
                    timestamp = baseTime + (i * 60_000L)
                )
            )
        }

        val profile1 = engine1.getProfile()
        assertEquals(15, profile1.sampleCount)
        assertTrue(profile1.isBaselineEstablished)
        assertTrue(profile1.knownCarriers.contains("MTN Rwanda"))
        assertTrue(profile1.knownNetworkTypes.contains("WIFI_OFFICE"))
        assertEquals(-1.9441, profile1.centroidLatitude!!, 0.0001)

        // 2. Instantiate a second fresh engine instance pointing to the same vault (simulating process restart)
        val engine2 = BehavioralLearningEngine(
            minRequiredSamples = 10,
            persistenceVault = vault
        )

        val profile2 = engine2.getProfile()
        assertEquals("Sample count must be restored after reload", 15, profile2.sampleCount)
        assertTrue("Baseline established state must be preserved", profile2.isBaselineEstablished)
        assertEquals("Confidence must match", profile1.confidence, profile2.confidence, 0.01f)
        assertTrue(profile2.knownCarriers.contains("MTN Rwanda"))
        assertTrue(profile2.knownNetworkTypes.contains("WIFI_OFFICE"))
        assertEquals(profile1.centroidLatitude!!, profile2.centroidLatitude!!, 0.0001)
        assertEquals(profile1.motionMeanMagnitude, profile2.motionMeanMagnitude, 0.01f)
        assertFalse(profile2.isPaused)
        assertFalse(profile2.isStale)
    }

    @Test
    fun testBehaviorLearningDataFlowAndDistinctCalendarDays() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.InkingiDatabase::class.java
        ).allowMainThreadQueries().build()

        val dao = db.behaviorEventDao()
        val manager = com.example.domain.ai.BehaviorLearningManager(dao, "DEV-TEST-01")

        runBlocking {
            // 1. Zero observations initially -> 0/7 and 0 obs
            manager.loadInitialBaseline()
            var baseline = manager.baselineFlow.value
            assertEquals(0, baseline.learningDays)
            assertEquals(0, baseline.totalObservations)
            assertFalse(baseline.isBaselineEstablished)

            val (withinInit, msgInit) = manager.isWithinBaseline(12, "Maison", com.example.core.model.UserRole.OWNER)
            assertTrue(withinInit)
            assertTrue(msgInit.contains("0/7 jours réels, 0 obs"))

            // 2. Guest observation does not modify owner baseline or count
            manager.recordObservation("Maison", com.example.core.model.UserRole.GUEST, "GUEST_ACTIVITY")
            baseline = manager.baselineFlow.value
            assertEquals("Guest activity must not increment owner learning days", 0, baseline.learningDays)
            assertEquals("Guest activity must not increment owner observations", 0, baseline.totalObservations)

            // 3. Trusted Person observation does not modify owner baseline
            manager.recordObservation("Maison", com.example.core.model.UserRole.TRUSTED_PERSON, "TRUSTED_ACTIVITY")
            baseline = manager.baselineFlow.value
            assertEquals("Trusted person activity must not increment owner learning days", 0, baseline.learningDays)
            assertEquals("Trusted person activity must not increment owner observations", 0, baseline.totalObservations)

            // 4. OWNER observation today -> 1/7 days and 1 observation
            val recorded = manager.recordObservation("Maison", com.example.core.model.UserRole.OWNER, "APP_OPEN")
            assertTrue("Genuine OWNER observation must be recorded", recorded)
            baseline = manager.baselineFlow.value
            assertEquals(1, baseline.learningDays)
            assertEquals(1, baseline.totalObservations)

            // 5. Multiple OWNER observations today -> still 1/7 days, count increments
            manager.recordObservation("Maison", com.example.core.model.UserRole.OWNER, "CHARGING_CONNECTED")
            manager.recordObservation("Bureau", com.example.core.model.UserRole.OWNER, "ZONE_TRANSITION")
            baseline = manager.baselineFlow.value
            assertEquals("Multiple observations on the same day must still count as 1 day", 1, baseline.learningDays)
            assertEquals(3, baseline.totalObservations)

            // 6. Test calendar days progression across 7 distinct days
            val dayMillis = 86400000L
            val now = System.currentTimeMillis()
            for (day in 1..6) {
                // Insert directly for genuine past calendar days
                dao.insertEvent(
                    com.example.data.local.entity.BehaviorEventEntity(
                        timestamp = now + (day * dayMillis),
                        heure = 10,
                        zone_nom = "Maison",
                        action = "ZONE_TRANSITION"
                    )
                )
                dao.insertEvent(
                    com.example.data.local.entity.BehaviorEventEntity(
                        timestamp = now + (day * dayMillis) + 3600000L,
                        heure = 11,
                        zone_nom = "Bureau",
                        action = "LOCATION_ACTIVITY"
                    )
                )
            }

            manager.computeAndSyncBaseline()
            baseline = manager.baselineFlow.value
            assertEquals("7 distinct calendar days must yield 7/7", 7, baseline.learningDays)
            assertEquals(15, baseline.totalObservations) // 3 + 12
            assertTrue("Baseline is established after 7 distinct days and >= 14 observations", baseline.isBaselineEstablished)

            // 7. Test baseline evaluation explainability
            val (withinNormal, msgNormal) = manager.isWithinBaseline(10, "Maison", com.example.core.model.UserRole.OWNER)
            assertTrue(withinNormal)
            assertTrue(msgNormal.contains("Dans baseline"))

            // Deviation alone gives warning (+20% risk) but NEVER declares theft
            val (withinDev, msgDev) = manager.isWithinBaseline(3, "Zone Inconnue", com.example.core.model.UserRole.OWNER)
            assertFalse(withinDev)
            assertTrue(msgDev.contains("Hors baseline (+20% risque)"))
        }

        db.close()
    }

    @Test
    fun testMidnightBoundaryDistinctLocalDays() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.InkingiDatabase::class.java
        ).allowMainThreadQueries().build()

        val dao = db.behaviorEventDao()
        val manager = com.example.domain.ai.BehaviorLearningManager(dao, "DEV-TEST-02")

        runBlocking {
            val cal = java.util.Calendar.getInstance()

            // 1. Event at 23:55 (Day 1)
            cal.set(2026, java.util.Calendar.OCTOBER, 29, 23, 55, 0)
            cal.set(java.util.Calendar.MILLISECOND, 0)
            val tsDay1Night = cal.timeInMillis

            dao.insertEvent(
                com.example.data.local.entity.BehaviorEventEntity(
                    timestamp = tsDay1Night,
                    heure = 23,
                    zone_nom = "Maison",
                    action = "APP_OPEN"
                )
            )

            manager.loadInitialBaseline()
            assertEquals("One late-night event must be 1 day", 1, manager.baselineFlow.value.learningDays)

            // 2. Event at 00:05 (Day 2 - 10 minutes later across midnight)
            cal.set(2026, java.util.Calendar.OCTOBER, 30, 0, 5, 0)
            val tsDay2Morning = cal.timeInMillis

            dao.insertEvent(
                com.example.data.local.entity.BehaviorEventEntity(
                    timestamp = tsDay2Morning,
                    heure = 0,
                    zone_nom = "Maison",
                    action = "APP_OPEN"
                )
            )

            manager.computeAndSyncBaseline()
            assertEquals(
                "Events across local midnight boundary must count as 2 distinct calendar days",
                2,
                manager.baselineFlow.value.learningDays
            )

            // 3. Second event on Day 2 at 02:00 AM (same day) must NOT increase day count
            cal.set(2026, java.util.Calendar.OCTOBER, 30, 2, 0, 0)
            val tsDay2Later = cal.timeInMillis

            dao.insertEvent(
                com.example.data.local.entity.BehaviorEventEntity(
                    timestamp = tsDay2Later,
                    heure = 2,
                    zone_nom = "Maison",
                    action = "ZONE_TRANSITION"
                )
            )

            manager.computeAndSyncBaseline()
            assertEquals(
                "Multiple events on Day 2 must still remain 2 distinct calendar days",
                2,
                manager.baselineFlow.value.learningDays
            )
            assertEquals(3, manager.baselineFlow.value.totalObservations)
        }

        db.close()
    }

    @Test
    fun testAutomaticTelemetryHeartbeatDoesNotInflateObservations() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.InkingiDatabase::class.java
        ).allowMainThreadQueries().build()

        val dao = db.behaviorEventDao()
        val manager = com.example.domain.ai.BehaviorLearningManager(dao, "DEV-TEST-03")

        runBlocking {
            // 1. Genuine user action APP_OPEN is recorded
            val appOpenRecorded = manager.recordObservation("Maison", com.example.core.model.UserRole.OWNER, "APP_OPEN")
            assertTrue("APP_OPEN must be recorded", appOpenRecorded)
            assertEquals(1, manager.baselineFlow.value.totalObservations)

            // 2. Genuine transition ZONE_TRANSITION is recorded
            val transitionRecorded = manager.recordObservation("Bureau", com.example.core.model.UserRole.OWNER, "ZONE_TRANSITION")
            assertTrue("ZONE_TRANSITION must be recorded", transitionRecorded)
            assertEquals(2, manager.baselineFlow.value.totalObservations)

            // 3. Routine automatic telemetry evaluation loops / heartbeats must NOT be recorded
            val evalLoopIgnored = manager.recordObservation("Bureau", com.example.core.model.UserRole.OWNER, "TELEMETRY_EVAL")
            assertFalse("Routine TELEMETRY_EVAL heartbeat must NOT be recorded", evalLoopIgnored)

            val heartbeatIgnored = manager.recordObservation("Bureau", com.example.core.model.UserRole.OWNER, "TELEMETRY_HEARTBEAT")
            assertFalse("Routine TELEMETRY_HEARTBEAT must NOT be recorded", heartbeatIgnored)

            val autoParamIgnored = manager.recordObservation(
                zoneName = "Bureau",
                role = com.example.core.model.UserRole.OWNER,
                action = "LOCATION_ACTIVITY",
                isAutomaticTelemetry = true
            )
            assertFalse("isAutomaticTelemetry = true must NOT be recorded", autoParamIgnored)

            // Count must still be 2, not falsely inflated by automatic loops
            assertEquals("Automatic heartbeats must not inflate observations", 2, manager.baselineFlow.value.totalObservations)
        }

        db.close()
    }

    @Test
    fun testAppRestartAndInitialBaselineLoading() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.example.data.local.InkingiDatabase::class.java
        ).allowMainThreadQueries().build()

        val dao = db.behaviorEventDao()

        runBlocking {
            // Populate database with 3 events across 2 days
            val dayMillis = 86400000L
            val now = System.currentTimeMillis()
            dao.insertEvent(com.example.data.local.entity.BehaviorEventEntity(timestamp = now, heure = 9, zone_nom = "Maison", action = "APP_OPEN"))
            dao.insertEvent(com.example.data.local.entity.BehaviorEventEntity(timestamp = now + 3600000L, heure = 10, zone_nom = "Bureau", action = "ZONE_TRANSITION"))
            dao.insertEvent(com.example.data.local.entity.BehaviorEventEntity(timestamp = now + dayMillis, heure = 9, zone_nom = "Maison", action = "APP_OPEN"))

            // Simulate app restart: create a new manager instance
            val restartedManager = com.example.domain.ai.BehaviorLearningManager(dao, "DEV-TEST-04")
            assertEquals("Before loadInitialBaseline, state starts default", 0, restartedManager.baselineFlow.value.learningDays)

            // Load initial baseline on startup
            restartedManager.loadInitialBaseline()
            val baseline = restartedManager.baselineFlow.value
            assertEquals("Restarted manager must load persisted distinct days from Room", 2, baseline.learningDays)
            assertEquals("Restarted manager must load persisted total observations from Room", 3, baseline.totalObservations)
        }

        db.close()
    }
}


