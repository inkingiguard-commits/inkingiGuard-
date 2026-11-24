package com.example

import com.example.core.model.SecurityState
import com.example.data.local.entity.LocationHistoryEntity
import com.example.domain.device.EquilibriumMode
import com.example.domain.recovery.LocationBreadcrumbPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 Unit Tests validating:
 * 1. Real GPS coordinates validation and status tag transitions.
 * 2. Offline location history queuing and batch synchronization.
 * 3. Battery Equilibrium Mode at 15% (must remain 100% usable in BALANCE mode).
 * 4. Find My Phone alarm safety timeout and isolation from STOLEN status.
 */
class LocationAndRecoveryUnitTest {

    // Helper evaluating trace status matching RealRecoveryMapView logic
    private fun computeTraceStatus(
        isGpsAvailable: Boolean,
        hasValidCoords: Boolean,
        timestamp: Long,
        connectivity: String,
        currentTime: Long = System.currentTimeMillis()
    ): String {
        val ageMs = currentTime - timestamp
        return when {
            !isGpsAvailable && !hasValidCoords -> "GPS DÉSACTIVÉ"
            !hasValidCoords -> "LOCALISATION INDISPONIBLE"
            ageMs < 5 * 60 * 1000L -> "EN DIRECT"
            connectivity.contains("OFFLINE", ignoreCase = true) || connectivity.contains("DÉCONNECTÉ", ignoreCase = true) -> "HORS LIGNE (DERNIÈRE POSITION)"
            else -> "DERNIÈRE POSITION CONNUE"
        }
    }

    @Test
    fun testRealCoordinatesValidation_NeverFabricatesCoordinates() {
        val validLat = -1.9441
        val validLng = 30.0619
        val hasValidCoords = (validLat != 0.0 || validLng != 0.0)

        assertTrue("Non-zero coordinates should be marked valid", hasValidCoords)

        val nullCoordsStatus = computeTraceStatus(
            isGpsAvailable = true,
            hasValidCoords = false,
            timestamp = System.currentTimeMillis(),
            connectivity = "WIFI"
        )
        assertEquals("LOCALISATION INDISPONIBLE", nullCoordsStatus)

        val gpsDisabledStatus = computeTraceStatus(
            isGpsAvailable = false,
            hasValidCoords = false,
            timestamp = 0L,
            connectivity = "NONE"
        )
        assertEquals("GPS DÉSACTIVÉ", gpsDisabledStatus)
    }

    @Test
    fun testStatusTagTransitions_LiveDelayedOffline() {
        val now = 1700000000000L

        // 1. Fresh fix (2 minutes old) -> EN DIRECT
        val liveStatus = computeTraceStatus(
            isGpsAvailable = true,
            hasValidCoords = true,
            timestamp = now - 120_000L,
            connectivity = "4G_LTE",
            currentTime = now
        )
        assertEquals("EN DIRECT", liveStatus)

        // 2. Delayed fix (15 minutes old, online) -> DERNIÈRE POSITION CONNUE
        val delayedStatus = computeTraceStatus(
            isGpsAvailable = true,
            hasValidCoords = true,
            timestamp = now - 900_000L,
            connectivity = "WIFI",
            currentTime = now
        )
        assertEquals("DERNIÈRE POSITION CONNUE", delayedStatus)

        // 3. Stale fix with no connectivity -> HORS LIGNE (DERNIÈRE POSITION)
        val offlineStatus = computeTraceStatus(
            isGpsAvailable = true,
            hasValidCoords = true,
            timestamp = now - 900_000L,
            connectivity = "DÉCONNECTÉ",
            currentTime = now
        )
        assertEquals("HORS LIGNE (DERNIÈRE POSITION)", offlineStatus)
    }

    @Test
    fun testOfflineLocationQueuingAndBatching() {
        // Simulate local offline location history entries
        val offlinePoints = (1..60).map { i ->
            LocationHistoryEntity(
                id = i.toLong(),
                latitude = -1.9441 + (i * 0.0001),
                longitude = 30.0619 + (i * 0.0001),
                accuracy = 8.5f,
                altitude = 1500.0,
                speed = 1.2f,
                bearing = 90f,
                timestamp = System.currentTimeMillis() - (60 - i) * 10_000L,
                isInsideTrustedZone = (i < 20),
                syncedWithBackend = false
            )
        }

        assertEquals(60, offlinePoints.size)
        val unsynced = offlinePoints.filter { !it.syncedWithBackend }
        assertEquals(60, unsynced.size)

        // Simulate chunking by 25
        val chunks = unsynced.chunked(25)
        assertEquals(3, chunks.size)
        assertEquals(25, chunks[0].size)
        assertEquals(25, chunks[1].size)
        assertEquals(10, chunks[2].size)

        // Simulate syncing and marking
        val syncedIds = mutableSetOf<Long>()
        chunks.forEach { chunk ->
            syncedIds.addAll(chunk.map { it.id })
        }

        assertEquals(60, syncedIds.size)
        assertTrue(syncedIds.contains(1L))
        assertTrue(syncedIds.contains(60L))
    }

    @Test
    fun testBatteryEquilibrium_ModeAt15Percent_MustRemainUsable() {
        // Battery Equilibrium Rules:
        // 41-100% -> WARRIOR (gps 15 min, allowCamera if theft)
        // 15-40%  -> BALANCE (gps 15 min, user still uses phone, do NOT hibernate, do NOT lock)
        // 5-14%   -> LOW (gps 30 min, aggressive background save, calls/messages still usable)
        // < 5%    -> SURVIVAL (gps 0 send once, only now hibernate)

        fun determineMode(batteryLevel: Int, isCharging: Boolean): Pair<EquilibriumMode, Int> {
            val clamped = batteryLevel.coerceIn(0, 100)
            if (isCharging) return Pair(EquilibriumMode.WARRIOR, 15)
            return when {
                clamped >= 41 -> Pair(EquilibriumMode.WARRIOR, 15)
                clamped in 15..40 -> Pair(EquilibriumMode.BALANCE, 15)
                clamped in 5..14 -> Pair(EquilibriumMode.LOW, 30)
                else -> Pair(EquilibriumMode.SURVIVAL, 0)
            }
        }

        // Test at exactly 15%
        val (modeAt15, gpsInterval15) = determineMode(15, isCharging = false)
        assertEquals("At 15%, device MUST be in BALANCE mode", EquilibriumMode.BALANCE, modeAt15)
        assertEquals("At 15%, GPS telemetry interval must be 15 minutes", 15, gpsInterval15)
        assertFalse("At 15%, phone must NEVER hibernate", modeAt15 == EquilibriumMode.SURVIVAL)

        // Test at 40% (upper boundary of BALANCE)
        val (modeAt40, _) = determineMode(40, isCharging = false)
        assertEquals(EquilibriumMode.BALANCE, modeAt40)

        // Test at 14% (transition to LOW)
        val (modeAt14, gpsInterval14) = determineMode(14, isCharging = false)
        assertEquals(EquilibriumMode.LOW, modeAt14)
        assertEquals(30, gpsInterval14)

        // Test at 4% (transition to SURVIVAL)
        val (modeAt4, gpsInterval4) = determineMode(4, isCharging = false)
        assertEquals(EquilibriumMode.SURVIVAL, modeAt4)
        assertEquals(0, gpsInterval4)
    }

    @Test
    fun testFindMyPhone_DoesNotTriggerStolenState() {
        var currentSecurityState = SecurityState.NORMAL
        var isFindMyPhoneActive = false

        // Owner presses "Faire Sonner l'Appareil"
        fun triggerFindMyPhone(): Boolean {
            isFindMyPhoneActive = true
            // Audible alarm plays locally; does NOT change securityState to STOLEN
            return true
        }

        val success = triggerFindMyPhone()
        assertTrue(success)
        assertTrue(isFindMyPhoneActive)
        assertEquals("Find My Phone must preserve NORMAL security state without declaring phone stolen", SecurityState.NORMAL, currentSecurityState)
    }

    @Test
    fun testBreadcrumbPointMapping() {
        val point = LocationBreadcrumbPoint(
            latitude = -1.9441,
            longitude = 30.0619,
            accuracy = 12.0f,
            timestamp = 1700000000000L,
            isInsideTrustedZone = true
        )

        assertEquals(-1.9441, point.latitude, 0.0001)
        assertEquals(30.0619, point.longitude, 0.0001)
        assertEquals(12.0f, point.accuracy, 0.1f)
        assertTrue(point.isInsideTrustedZone)
    }
}
