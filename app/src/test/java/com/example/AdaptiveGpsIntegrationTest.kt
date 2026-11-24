package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.SecurityState
import com.example.data.local.dao.LocationDao
import com.example.data.local.entity.LocationHistoryEntity
import com.example.domain.location.LocationIntelligenceEngine
import com.example.domain.monitoring.AdaptiveMonitoringPolicy
import com.example.domain.service.RecoveryMonitoringService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AdaptiveGpsIntegrationTest {

    private lateinit var context: Context
    private lateinit var policy: AdaptiveMonitoringPolicy

    private val fakeLocationDao = object : LocationDao {
        override fun getLastLocation(): Flow<LocationHistoryEntity?> = flowOf(null)
        override fun getLocationHistory(limit: Int): Flow<List<LocationHistoryEntity>> = flowOf(emptyList())
        override fun getLocationsInRange(startTime: Long, endTime: Long): Flow<List<LocationHistoryEntity>> = flowOf(emptyList())
        override suspend fun getLatestBreadcrumb(): LocationHistoryEntity? = null
        override fun getUnsyncedCount(): Flow<Int> = flowOf(0)
        override suspend fun insertLocation(location: LocationHistoryEntity) {}
        override suspend fun getUnsyncedLocations(): List<LocationHistoryEntity> = emptyList()
        override suspend fun markSynced(ids: List<Long>) {}
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        policy = AdaptiveMonitoringPolicy()
    }

    @Test
    fun `confirmed theft states produce 10 second GPS sampling interval`() {
        val states = listOf(
            SecurityState.OWNER_CONFIRMED_STOLEN,
            SecurityState.STOLEN_MODE,
            SecurityState.RECOVERY_MODE
        )

        for (state in states) {
            val strategy = policy.determineStrategy(
                state = state,
                batteryPercentage = 80,
                isCharging = false
            )
            assertEquals("State $state must require 10s GPS interval", 10_000L, strategy.locationIntervalMillis)
            assertFalse(strategy.isBatteryConservationActive)
        }
    }

    @Test
    fun `danger and suspected lost states produce 20 second GPS sampling interval`() {
        val states = listOf(
            SecurityState.DANGER,
            SecurityState.SUSPECTED_LOST
        )

        for (state in states) {
            val strategy = policy.determineStrategy(
                state = state,
                batteryPercentage = 75,
                isCharging = false
            )
            assertEquals("State $state must require 20s GPS interval", 20_000L, strategy.locationIntervalMillis)
            assertFalse(strategy.isBatteryConservationActive)
        }
    }

    @Test
    fun `critical battery below 15 percent engages battery preservation with 60 second interval`() {
        val strategy = policy.determineStrategy(
            state = SecurityState.NORMAL,
            batteryPercentage = 14,
            isCharging = false
        )
        assertEquals(60_000L, strategy.locationIntervalMillis)
        assertTrue(strategy.isBatteryConservationActive)
    }

    @Test
    fun `warning state produces 60 second GPS sampling interval`() {
        val strategy = policy.determineStrategy(
            state = SecurityState.WARNING,
            batteryPercentage = 80,
            isCharging = false
        )
        assertEquals(60_000L, strategy.locationIntervalMillis)
        assertFalse(strategy.isBatteryConservationActive)
    }

    @Test
    fun `normal and recovered states produce 300 second GPS sampling interval`() {
        val states = listOf(
            SecurityState.NORMAL,
            SecurityState.RECOVERED
        )

        for (state in states) {
            val strategy = policy.determineStrategy(
                state = state,
                batteryPercentage = 80,
                isCharging = false
            )
            assertEquals("State $state must require 300s GPS interval", 300_000L, strategy.locationIntervalMillis)
            assertFalse(strategy.isBatteryConservationActive)
        }
    }

    @Test
    fun `location intelligence engine tracks and dynamically updates active interval`() {
        val engine = LocationIntelligenceEngine(context, fakeLocationDao)
        assertEquals("Initial interval defaults to 300s", 300_000L, engine.activeIntervalMillis)
        assertFalse(engine.isTracking)

        // Apply new monitoring interval
        val appliedDanger = engine.applyMonitoringInterval(20_000L)
        assertTrue(appliedDanger)
        assertEquals(20_000L, engine.activeIntervalMillis)

        // Apply stolen interval
        val appliedStolen = engine.applyMonitoringInterval(10_000L)
        assertTrue(appliedStolen)
        assertEquals(10_000L, engine.activeIntervalMillis)

        // Calling apply with the same interval returns true without redundant work
        val appliedSame = engine.applyMonitoringInterval(10_000L)
        assertTrue(appliedSame)
        assertEquals(10_000L, engine.activeIntervalMillis)

        // Stopping updates resets tracking flag
        engine.stopLocationUpdates()
        assertFalse(engine.isTracking)
    }

    @Test
    fun `recovery monitoring service uses 10s default theft interval`() {
        assertEquals(10_000L, RecoveryMonitoringService.DEFAULT_THEFT_INTERVAL_MILLIS)
        assertEquals("EXTRA_INTERVAL_MILLIS", RecoveryMonitoringService.EXTRA_INTERVAL_MILLIS)
    }
}
