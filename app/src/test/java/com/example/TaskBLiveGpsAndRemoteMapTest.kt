package com.example

import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.data.local.InkingiDatabase
import com.example.data.local.entity.LocationHistoryEntity
import com.example.data.repository.DeviceSettingsRepository
import com.example.domain.location.LocationBreadcrumbPolicy
import com.example.domain.location.LocationIntelligenceEngine
import com.example.domain.receiver.BootCompletedReceiver
import com.example.domain.service.RecoveryMonitoringService
import com.example.domain.sync.LocationSyncWorker
import com.example.presentation.recovery.RecoveryMapStatusFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskBLiveGpsAndRemoteMapTest {

    private lateinit var context: Context
    private lateinit var db: InkingiDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(DeviceSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(DeviceSettingsRepository.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()

        val wmConfig = Configuration.Builder().build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, wmConfig)

        db = Room.inMemoryDatabaseBuilder(context, InkingiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `coordinate validation rejects zero, NaN, infinite, and out-of-range coordinates`() {
        assertFalse(LocationBreadcrumbPolicy.isValidCoordinate(null, null))
        assertFalse(LocationBreadcrumbPolicy.isValidCoordinate(0.0, 0.0))
        assertFalse(LocationBreadcrumbPolicy.isValidCoordinate(Double.NaN, 30.0))
        assertFalse(LocationBreadcrumbPolicy.isValidCoordinate(-1.94, Double.POSITIVE_INFINITY))
        assertFalse(LocationBreadcrumbPolicy.isValidCoordinate(91.0, 30.0))
        assertFalse(LocationBreadcrumbPolicy.isValidCoordinate(-1.94, -181.0))

        assertTrue(LocationBreadcrumbPolicy.isValidCoordinate(-1.9441, 30.0619))
    }

    @Test
    fun `freshness policy never treats cached LAST_KNOWN or aged fixes as live`() {
        val now = 1_700_000_000_000L

        assertTrue(
            "Cached last-known fix must be classified as stale even with current timestamp",
            LocationBreadcrumbPolicy.isLocationStale(timestampMs = now, isFreshFix = false, nowMs = now)
        )
        assertTrue(
            "Fresh fix older than maxLiveAgeMs must be classified as stale",
            LocationBreadcrumbPolicy.isLocationStale(timestampMs = now - 125_000L, isFreshFix = true, nowMs = now)
        )
        assertFalse(
            "Recent verified fresh fix must be live",
            LocationBreadcrumbPolicy.isLocationStale(timestampMs = now - 5_000L, isFreshFix = true, nowMs = now)
        )

        val staleLabel = RecoveryMapStatusFormatter.resolveTraceStatus(
            hasValidCoords = true,
            isGpsAvailable = true,
            timestampMs = now - 10_000L,
            isFreshFix = false,
            connectivity = "WIFI",
            isEn = true,
            nowMs = now
        )
        assertEquals("LAST KNOWN POSITION", staleLabel)

        val liveLabel = RecoveryMapStatusFormatter.resolveTraceStatus(
            hasValidCoords = true,
            isGpsAvailable = true,
            timestampMs = now - 5_000L,
            isFreshFix = true,
            connectivity = "WIFI",
            isEn = true,
            nowMs = now
        )
        assertEquals("LIVE", liveLabel)

        val offlineLabel = RecoveryMapStatusFormatter.resolveTraceStatus(
            hasValidCoords = true,
            isGpsAvailable = true,
            timestampMs = now - 5_000L,
            isFreshFix = true,
            connectivity = "OFFLINE",
            isEn = true,
            nowMs = now
        )
        assertEquals("OFFLINE (LAST KNOWN)", offlineLabel)
    }

    @Test
    fun `breadcrumb deduplication skips redundant stationary samples but stores movement and interval points`() {
        val baseTime = 1_700_000_000_000L
        val first = LocationHistoryEntity(
            id = 1L,
            latitude = -1.94410,
            longitude = 30.06190,
            accuracy = 20f,
            altitude = 1480.0,
            speed = 0f,
            bearing = 0f,
            timestamp = baseTime,
            isInsideTrustedZone = true,
            isFreshFix = true
        )

        // Nearby stationary sample within 15m and 30s -> skip
        assertFalse(
            LocationBreadcrumbPolicy.shouldPersistBreadcrumb(
                previous = first,
                newLat = -1.94411,
                newLng = 30.06191,
                newAccuracy = 18f,
                newTimeMs = baseTime + 5_000L
            )
        )

        // Significant movement (>25m) -> persist immediately
        assertTrue(
            LocationBreadcrumbPolicy.shouldPersistBreadcrumb(
                previous = first,
                newLat = -1.94450,
                newLng = 30.06230,
                newAccuracy = 18f,
                newTimeMs = baseTime + 5_000L
            )
        )

        // Stationary but >= 30s elapsed -> persist checkpoint
        assertTrue(
            LocationBreadcrumbPolicy.shouldPersistBreadcrumb(
                previous = first,
                newLat = -1.94410,
                newLng = 30.06190,
                newAccuracy = 20f,
                newTimeMs = baseTime + 31_000L
            )
        )

        // Sharp accuracy improvement (>= 50% sharper) -> persist
        assertTrue(
            LocationBreadcrumbPolicy.shouldPersistBreadcrumb(
                previous = first,
                newLat = -1.94410,
                newLng = 30.06190,
                newAccuracy = 8f,
                newTimeMs = baseTime + 2_000L
            )
        )
    }

    @Test
    fun `LocationIntelligenceEngine separates cached getLastKnownLocation from live onLocationChanged fixes`() = runBlocking {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
            .grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        val dao = db.locationDao()
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLm = Shadows.shadowOf(lm)
        shadowLm.setProviderEnabled(LocationManager.GPS_PROVIDER, true)

        val cached = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = -1.9500
            longitude = 30.0500
            accuracy = 25f
            time = System.currentTimeMillis() - 600_000L
        }
        shadowLm.setLastKnownLocation(LocationManager.GPS_PROVIDER, cached)

        val engine = LocationIntelligenceEngine(
            context = context,
            locationDao = dao,
            batteryPercentageProvider = { 72 },
            deviceIdProvider = { "IG-TEST001" }
        )
        engine.startLocationUpdates(10_000L)

        // Wait briefly for coroutine on IO if any
        val seedEvent = engine.lastLocation.value
        assertNotNull(seedEvent)
        assertFalse("Initial cached seed must have isFreshFix = false", seedEvent!!.isFreshFix)
        assertEquals("LAST_KNOWN", seedEvent.locationSource)
        assertNull("Cached seed must not pollute persisted movement breadcrumbs", dao.getLatestBreadcrumb())

        // Now deliver an active fresh GPS fix
        val liveFix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = -1.9510
            longitude = 30.0510
            accuracy = 6f
            altitude = 1490.0
            speed = 3.2f
            bearing = 180f
            time = System.currentTimeMillis()
        }
        engine.onLocationChanged(liveFix)
        kotlinx.coroutines.delay(150L)

        val freshEvent = engine.lastLocation.value
        assertNotNull(freshEvent)
        assertTrue("Active GPS callback must set isFreshFix = true", freshEvent!!.isFreshFix)
        assertEquals("GPS_LIVE", freshEvent.locationSource)
        assertEquals(1490.0, freshEvent.altitude, 0.1)

        val stored = dao.getLatestBreadcrumb()
        assertNotNull("Fresh GPS fix must be persisted into Room breadcrumbs", stored)
        assertTrue(stored!!.isFreshFix)
        assertEquals(72, stored.batteryLevel)
        assertEquals("IG-TEST001", stored.deviceId)
        assertFalse(stored.syncedWithBackend)
    }

    @Test
    fun `Room LocationDao filters movement breadcrumbs by selected date range and tracks unsynced count`() = runBlocking {
        val dao = db.locationDao()
        val dayOneBase = 1_700_000_000_000L
        val dayTwoBase = dayOneBase + 86_400_000L

        dao.insertLocation(
            LocationHistoryEntity(
                latitude = -1.940,
                longitude = 30.060,
                accuracy = 10f,
                altitude = 1450.0,
                speed = 1.2f,
                bearing = 90f,
                timestamp = dayOneBase + 1_000L,
                isInsideTrustedZone = true,
                syncedWithBackend = false,
                isFreshFix = true
            )
        )
        dao.insertLocation(
            LocationHistoryEntity(
                latitude = -1.942,
                longitude = 30.062,
                accuracy = 12f,
                altitude = 1455.0,
                speed = 2.0f,
                bearing = 120f,
                timestamp = dayTwoBase + 2_000L,
                isInsideTrustedZone = false,
                syncedWithBackend = false,
                isFreshFix = true
            )
        )

        assertEquals(2, dao.getUnsyncedCount().first())

        val dayOnePoints = dao.getLocationsInRange(dayOneBase, dayOneBase + 86_399_999L).first()
        assertEquals(1, dayOnePoints.size)
        assertEquals(-1.940, dayOnePoints[0].latitude, 0.0001)

        val dayTwoPoints = dao.getLocationsInRange(dayTwoBase, dayTwoBase + 86_399_999L).first()
        assertEquals(1, dayTwoPoints.size)
        assertEquals(-1.942, dayTwoPoints[0].latitude, 0.0001)

        val unsyncedIds = dao.getUnsyncedLocations().map { it.id }
        dao.markSynced(unsyncedIds)
        assertEquals(0, dao.getUnsyncedCount().first())
    }

    @Test
    fun `LocationSyncWorker enqueues uniquely with WorkManager and executes safely`() = runBlocking {
        val enqueued = LocationSyncWorker.enqueue(context)
        assertTrue(enqueued)

        val infos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(LocationSyncWorker.UNIQUE_WORK_NAME)
            .get()
        assertFalse(infos.isEmpty())
        assertTrue(
            infos.first().state == WorkInfo.State.ENQUEUED ||
                infos.first().state == WorkInfo.State.RUNNING
        )

        val worker = TestListenableWorkerBuilder<LocationSyncWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `BootCompletedReceiver restarts RecoveryMonitoringService and sync on BOOT_COMPLETED only when STOLEN_MODE is active`() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadowApp = Shadows.shadowOf(app)
        val receiver = BootCompletedReceiver()

        // Disarmed state -> must NOT start tracking service
        DeviceSettingsRepository.setStolenTrackingActive(context, false)
        shadowApp.grantPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION)
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowApp.nextStartedService)

        // Active STOLEN_MODE -> must restart RecoveryMonitoringService & enqueue WorkManager sync
        DeviceSettingsRepository.setStolenTrackingActive(context, true, "STOLEN_MODE")
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))
        val startedIntent = shadowApp.nextStartedService
        assertNotNull(startedIntent)
        assertEquals(RecoveryMonitoringService::class.java.name, startedIntent?.component?.className)
        assertEquals(
            RecoveryMonitoringService.DEFAULT_THEFT_INTERVAL_MILLIS,
            startedIntent?.getLongExtra(RecoveryMonitoringService.EXTRA_INTERVAL_MILLIS, -1L)
        )

        // Also handle MY_PACKAGE_REPLACED
        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        val replacedIntent = shadowApp.nextStartedService
        assertNotNull(replacedIntent)
        assertEquals(RecoveryMonitoringService::class.java.name, replacedIntent?.component?.className)
    }

    @Test
    fun `DeviceSettingsRepository persists unified non-hardcoded deviceId across both pref stores`() {
        val id = DeviceSettingsRepository.getDeviceId(context)
        assertTrue(id.startsWith("IG-"))
        assertNotEquals("IG-D98E1455", id)

        val legacyId = context.getSharedPreferences(DeviceSettingsRepository.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(DeviceSettingsRepository.KEY_DEVICE_ID, null)
        assertEquals(id, legacyId)
    }
}
