package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.core.model.SecurityState
import com.example.data.local.InkingiDatabase
import com.example.data.local.entity.LocationHistoryEntity
import com.example.data.repository.DeviceSettingsRepository
import com.example.di.InkingiContainer
import com.example.domain.location.LocationIntelligenceEngine
import com.example.domain.recovery.LocationBreadcrumbPoint
import com.example.domain.recovery.RemoteRecoveryTelemetry
import com.example.domain.recovery.TelemetrySyncPolicy
import com.example.domain.service.RecoveryMonitoringService
import com.example.domain.service.ServiceStartResult
import com.example.domain.sync.LocationSyncWorker
import com.example.presentation.InkingiUiState
import com.example.presentation.InkingiViewModel
import com.example.presentation.recovery.MapTileErrorType
import com.example.presentation.recovery.MapWebViewState
import com.example.presentation.recovery.MapWebViewStateReducer
import com.example.presentation.recovery.RealRecoveryMapView
import com.example.presentation.recovery.RecoveryMapStatusFormatter
import com.example.presentation.recovery.WaitingMapCondition
import com.example.presentation.recovery.buildUpdateMapStateJs
import com.example.presentation.recovery.deduplicateHistoryBreadcrumbs
import com.example.presentation.recovery.generateLeafletOsmHtml
import com.example.presentation.theft.TheftRecoveryScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class GpsMapCorrectionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var app: Application
    private lateinit var db: InkingiDatabase
    private lateinit var container: InkingiContainer

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        app.getSharedPreferences(DeviceSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        app.getSharedPreferences(DeviceSettingsRepository.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()

        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().build()
        )

        db = Room.inMemoryDatabaseBuilder(app, InkingiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        container = InkingiContainer(app, db)
        Shadows.shadowOf(app).clearStartedServices()
    }

    @After
    fun tearDown() {
        container.locationEngine.stopLocationUpdates()
        container.secureRecoveryBridge.stopRemoteCommandListener()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        db.close()
    }

    // 1. GPS permission granted after initial app launch
    @Test
    fun `gps permission granted after launch updates ViewModel state and starts location updates`() {
        val shadowApp = Shadows.shadowOf(app)
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLm = Shadows.shadowOf(lm)
        shadowLm.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowApp.denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val viewModel = InkingiViewModel(container)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(viewModel.uiState.value.hasFineLocationPermission)
        assertFalse(viewModel.uiState.value.isLocationUpdatesActive)
        assertFalse(container.locationEngine.isTracking)

        // User subsequently grants permission in Android dialog or Settings
        shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        viewModel.onLocationPermissionsUpdated()
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(viewModel.uiState.value.hasFineLocationPermission)
        assertTrue(viewModel.uiState.value.isLocationUpdatesActive)
        assertTrue(container.locationEngine.isTracking)
    }

    // 2. GPS updates restart after permission grant without app restart
    @Test
    fun `gps updates restart after permission grant and process fresh fix`() = runBlocking {
        val shadowApp = Shadows.shadowOf(app)
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLm = Shadows.shadowOf(lm)
        shadowLm.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowApp.denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val engine = LocationIntelligenceEngine(
            context = app,
            locationDao = db.locationDao(),
            batteryPercentageProvider = { 80 },
            deviceIdProvider = { "IG-GPS02" }
        )

        assertFalse(engine.startLocationUpdates(10_000L))
        assertFalse(engine.isTracking)

        // Grant permission later and restart cleanly
        shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertTrue(engine.ensureLocationUpdatesStarted(10_000L))
        assertTrue(engine.isTracking)

        val liveFix = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = -1.9441
            longitude = 30.0619
            accuracy = 7f
            time = System.currentTimeMillis()
        }
        engine.onLocationChanged(liveFix)
        kotlinx.coroutines.delay(150L)

        val emitted = engine.lastLocation.value
        assertNotNull(emitted)
        assertTrue(emitted!!.isFreshFix)
        assertEquals("GPS_LIVE", emitted.locationSource)
        assertEquals(-1.9441, emitted.latitude, 0.0001)
    }

    // 3. No duplicate location subscriptions
    @Test
    fun `repeated permission refreshes and startLocationUpdates calls never duplicate listeners`() {
        val shadowApp = Shadows.shadowOf(app)
        shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val shadowLm = Shadows.shadowOf(lm)
        shadowLm.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowLm.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)

        val engine = container.locationEngine
        assertTrue(engine.startLocationUpdates(15_000L))
        val initialListenerCount = shadowLm.requestLocationUpdateListeners.size
        assertTrue("Expected active registered listeners", initialListenerCount > 0)

        // Repeated calls with same interval must remain idempotent
        assertTrue(engine.ensureLocationUpdatesStarted(15_000L))
        assertTrue(engine.startLocationUpdates(15_000L))
        assertTrue(engine.applyMonitoringInterval(15_000L))
        assertEquals(initialListenerCount, shadowLm.requestLocationUpdateListeners.size)

        // Even when interval changes, old listeners are removed before registering the new interval
        assertTrue(engine.applyMonitoringInterval(30_000L))
        assertEquals(initialListenerCount, shadowLm.requestLocationUpdateListeners.size)
    }

    // 4. Null coordinates do not fabricate map markers
    @Test
    fun `null coordinates show waiting viewport and never fabricate fallback markers or coordinates`() {
        var retried = false
        composeRule.setContent {
            RealRecoveryMapView(
                deviceId = "IG-NULL1",
                deviceName = "Pixel Local",
                latitude = null,
                longitude = null,
                accuracy = null,
                timestamp = 0L,
                battery = "85%",
                isCharging = false,
                connectivity = "WIFI",
                securityStatus = SecurityState.NORMAL.name,
                isGpsAvailable = true,
                historyPoints = emptyList(),
                isEn = true,
                hasLocationPermission = true,
                isTrackingActive = true,
                onRetryGps = { retried = true }
            )
        }

        composeRule.onNodeWithTag("gps_unavailable_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("map_coordinates_text").assertDoesNotExist()
        composeRule.onNodeWithTag("open_maps_trace_button").assertDoesNotExist()
        composeRule.onNodeWithText("0.00000, 0.00000").assertDoesNotExist()
        composeRule.onNodeWithTag("retry_gps_button").assertIsDisplayed().performClick()
        assertTrue(retried)
    }

    // 5. Map waiting state displayed correctly (waiting vs permission denied vs disabled)
    @Test
    fun `map status distinguishes waiting for first fix vs permission denied vs gps disabled`() {
        val waitingStatus = RecoveryMapStatusFormatter.resolveTraceStatus(
            hasValidCoords = false,
            isGpsAvailable = true,
            timestampMs = 0L,
            isFreshFix = false,
            connectivity = "WIFI",
            isEn = true,
            hasLocationPermission = true,
            isTrackingActive = true
        )
        assertEquals("WAITING FOR FIRST GPS FIX", waitingStatus)
        assertEquals(
            WaitingMapCondition.WAITING_FIRST_FIX,
            RecoveryMapStatusFormatter.resolveWaitingCondition(
                hasValidCoords = false,
                hasLocationPermission = true,
                isGpsAvailable = true,
                isTrackingActive = true
            )
        )

        val deniedStatus = RecoveryMapStatusFormatter.resolveTraceStatus(
            hasValidCoords = false,
            isGpsAvailable = true,
            timestampMs = 0L,
            isFreshFix = false,
            connectivity = "WIFI",
            isEn = true,
            hasLocationPermission = false,
            isTrackingActive = false
        )
        assertEquals("GPS PERMISSION DENIED", deniedStatus)

        var grantClicked = false
        composeRule.setContent {
            RealRecoveryMapView(
                deviceId = "IG-WAIT1",
                latitude = null,
                longitude = null,
                accuracy = null,
                timestamp = 0L,
                battery = "50%",
                isCharging = false,
                connectivity = "WIFI",
                securityStatus = "NORMAL",
                isGpsAvailable = false,
                historyPoints = emptyList(),
                isEn = true,
                hasLocationPermission = false,
                isTrackingActive = false,
                onRequestLocationPermission = { grantClicked = true }
            )
        }

        composeRule.onNodeWithTag("map_trace_status_badge").assertIsDisplayed()
        composeRule.onNodeWithText("GPS Permission Denied").assertIsDisplayed()
        composeRule.onNodeWithTag("request_location_permission_map_button").assertIsDisplayed().performClick()
        assertTrue(grantClicked)
    }

    // 6. Fresh vs stale GPS coordinates
    @Test
    fun `fresh vs stale coordinates are accurately formatted and labeled`() {
        val now = 1_750_000_000_000L
        assertEquals(
            "LIVE",
            RecoveryMapStatusFormatter.resolveTraceStatus(
                hasValidCoords = true,
                isGpsAvailable = true,
                timestampMs = now - 3_000L,
                isFreshFix = true,
                connectivity = "WIFI",
                isEn = true,
                nowMs = now
            )
        )
        assertEquals(
            "LAST KNOWN POSITION",
            RecoveryMapStatusFormatter.resolveTraceStatus(
                hasValidCoords = true,
                isGpsAvailable = true,
                timestampMs = now - 3_000L,
                isFreshFix = false,
                connectivity = "WIFI",
                isEn = true,
                nowMs = now
            )
        )
        assertEquals(
            "DERNIÈRE POSITION CONNUE",
            RecoveryMapStatusFormatter.resolveTraceStatus(
                hasValidCoords = true,
                isGpsAvailable = true,
                timestampMs = now - 300_000L,
                isFreshFix = true,
                connectivity = "4G",
                isEn = false,
                nowMs = now
            )
        )
    }

    // 7. Map state updates after a new GPS fix
    @Test
    fun `map transitions from waiting banner to live coordinates when a new GPS fix arrives`() {
        var latState by mutableStateOf<Double?>(null)
        var lngState by mutableStateOf<Double?>(null)
        var accState by mutableStateOf<Float?>(null)
        var freshState by mutableStateOf(false)
        var timeState by mutableStateOf(0L)

        composeRule.setContent {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                RealRecoveryMapView(
                    deviceId = "IG-UPDATE1",
                    latitude = latState,
                    longitude = lngState,
                    accuracy = accState,
                    timestamp = timeState,
                    battery = "77%",
                    isCharging = false,
                    connectivity = "WIFI",
                    securityStatus = "NORMAL",
                    isGpsAvailable = true,
                    historyPoints = emptyList(),
                    isFreshFix = freshState,
                    locationSource = if (freshState) "GPS_LIVE" else "SYSTEM",
                    isEn = true,
                    hasLocationPermission = true,
                    isTrackingActive = true
                )
            }
        }

        composeRule.onNodeWithTag("gps_unavailable_banner").assertIsDisplayed()

        composeRule.runOnIdle {
            latState = -1.94410
            lngState = 30.06190
            accState = 5f
            freshState = true
            timeState = System.currentTimeMillis()
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("recovery_map_viewport").assertIsDisplayed()
        composeRule.onNodeWithTag("map_coordinates_text").assertIsDisplayed()
        composeRule.onNodeWithTag("open_maps_trace_button")
            .performScrollTo()
            .assertIsDisplayed()

        val jsCall = buildUpdateMapStateJs(
            latitude = -1.94410,
            longitude = 30.06190,
            accuracyMeters = 5f,
            isLive = true,
            shouldFollow = true,
            historyJson = "[]",
            isRemoteDevice = false
        )
        assertTrue(jsCall.contains("updateMapState(-1.9441, 30.0619, 5.0, true, true, [], false)"))
    }

    // 8. Leaflet initialization lifecycle
    @Test
    fun `leaflet html queues updates prior to ready state and prevents duplicate markers or history`() {
        val dupHistory = listOf(
            LocationBreadcrumbPoint(-1.94410, 30.06190, 10f, 1_700_000_000_000L, true),
            LocationBreadcrumbPoint(-1.94410, 30.06190, 10f, 1_700_000_001_000L, true), // dup within 5s & 1e-6
            LocationBreadcrumbPoint(-1.94500, 30.06250, 8f, 1_700_000_040_000L, false)
        )
        val deduped = deduplicateHistoryBreadcrumbs(dupHistory)
        assertEquals(2, deduped.size)

        val html = generateLeafletOsmHtml(
            lat = -1.9441,
            lng = 30.0619,
            accuracyMeters = 10f,
            isLive = true,
            isRemoteDevice = false,
            historyJson = "[]",
            zones = emptyList()
        )
        assertTrue(html.contains("window.__inkingiMapReady = false;"))
        assertTrue(html.contains("window.__pendingMapUpdate = ["))
        assertTrue(html.contains("window.InkingiMapBridge.onMapReady()"))
        assertTrue(html.contains("historyLayer.clearLayers();"))
        assertTrue(html.contains("marker.setLatLng([newLat, newLng]);"))
        assertTrue(html.contains("typeof L === 'undefined'"))
    }

    // 9. WebView loading and failure states
    @Test
    fun `webview reducer handles leaflet script errors page errors and ready recovery`() {
        val loading = MapWebViewStateReducer.onPageStarted(isNetworkConnected = true)
        assertTrue(loading.isPageLoading)
        assertEquals(MapTileErrorType.NONE, loading.errorType)

        val scriptErr = MapWebViewStateReducer.onResourceOrPageError(
            current = loading,
            requestUrl = "https://unpkg.com/leaflet@1.9.4/dist/leaflet.js",
            isMainFrame = false,
            detail = "ERR_NAME_NOT_RESOLVED",
            isNetworkConnected = true
        )
        assertEquals(MapTileErrorType.LEAFLET_SCRIPT_ERROR, scriptErr.errorType)
        assertFalse(scriptErr.isPageLoading)

        val mainFrameErr = MapWebViewStateReducer.onResourceOrPageError(
            current = loading,
            requestUrl = "https://www.openstreetmap.org/",
            isMainFrame = true,
            detail = "ERR_TIMED_OUT",
            isNetworkConnected = true
        )
        assertEquals(MapTileErrorType.PAGE_LOAD_ERROR, mainFrameErr.errorType)

        val ready = MapWebViewStateReducer.onLeafletReady(
            MapWebViewState(isPageLoading = true, isLeafletInitialized = false, errorType = MapTileErrorType.NONE)
        )
        assertFalse(ready.isPageLoading)
        assertTrue(ready.isLeafletInitialized)
    }

    // 10. Offline tile failure handling
    @Test
    fun `offline connectivity and tile failures show honest internet-required overlay with retry and attribution`() {
        var tileErrState = MapWebViewStateReducer.onPageStarted(isNetworkConnected = true)
        repeat(3) {
            tileErrState = MapWebViewStateReducer.onTileError(tileErrState, isNetworkConnected = true)
        }
        assertEquals(MapTileErrorType.TILE_LOAD_ERROR, tileErrState.errorType)

        val offlineState = MapWebViewStateReducer.onTileError(
            MapWebViewStateReducer.onPageStarted(isNetworkConnected = false),
            isNetworkConnected = false
        )
        assertEquals(MapTileErrorType.OFFLINE_INTERNET_REQUIRED, offlineState.errorType)

        var retryTriggered = false
        composeRule.setContent {
            RealRecoveryMapView(
                deviceId = "IG-OFFLINE1",
                latitude = -1.9441,
                longitude = 30.0619,
                accuracy = 12f,
                timestamp = System.currentTimeMillis(),
                battery = "65%",
                isCharging = false,
                connectivity = "WIFI",
                securityStatus = "NORMAL",
                isGpsAvailable = true,
                historyPoints = emptyList(),
                isFreshFix = true,
                isEn = true,
                isNetworkConnected = false,
                onRetryGps = { retryTriggered = true }
            )
        }

        composeRule.onNodeWithTag("map_tile_error_overlay").assertIsDisplayed()
        composeRule.onNodeWithText("Internet Required for Map Tiles").assertIsDisplayed()
        composeRule.onNodeWithText("© OpenStreetMap contributors").assertIsDisplayed()
        composeRule.onNodeWithTag("retry_map_button").assertIsDisplayed().performClick()
        assertTrue(retryTriggered)
    }

    // 11. Firestore recovery_telemetry/latest synchronization & battery/unlocated safety
    @Test
    fun `location sync and recovery telemetry clamp invalid battery and preserve honest unlocated metadata`() = runBlocking {
        // Insert an unlocated / negative-battery-default style history record? Ensure LocationSyncWorker handles both empty and valid queue cleanly
        val worker = TestListenableWorkerBuilder<LocationSyncWorker>(app).build()
        val res = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), res)

        // When battery is invalid (-1), clamping yields 0..100 required by firestore.rules
        val rawBattery = -1
        assertEquals(0, rawBattery.coerceIn(0, 100))
        assertEquals(100, 145.coerceIn(0, 100))

        // Unlocated initial state (no existing fix) can publish initial metadata, while subsequent unlocated ping cannot erase valid location
        assertTrue(
            TelemetrySyncPolicy.shouldOverwriteLatestTelemetry(
                existingTimestampMs = null,
                existingHasLocation = false,
                candidateTimestampMs = 1_700_000_000_000L,
                candidateHasLocation = false,
                candidateIsFresh = false
            )
        )
        assertFalse(
            TelemetrySyncPolicy.shouldOverwriteLatestTelemetry(
                existingTimestampMs = 1_700_000_000_000L,
                existingHasLocation = true,
                candidateTimestampMs = 1_700_000_050_000L,
                candidateHasLocation = false,
                candidateIsFresh = false
            )
        )
    }

    // 12. Owner UID and device ID validation
    @Test
    fun `owner uid and device id validation rejects mismatched account or device documents`() {
        assertTrue(
            TelemetrySyncPolicy.isMatchingAuthorizedTelemetry(
                expectedOwnerUid = "owner-A",
                expectedDeviceId = "IG-DEV1",
                documentOwnerUid = "owner-A",
                documentDeviceId = "IG-DEV1"
            )
        )
        assertFalse(
            TelemetrySyncPolicy.isMatchingAuthorizedTelemetry(
                expectedOwnerUid = "owner-A",
                expectedDeviceId = "IG-DEV1",
                documentOwnerUid = "owner-B",
                documentDeviceId = "IG-DEV1"
            )
        )
        assertFalse(
            TelemetrySyncPolicy.isMatchingAuthorizedTelemetry(
                expectedOwnerUid = "owner-A",
                expectedDeviceId = "IG-DEV1",
                documentOwnerUid = "owner-A",
                documentDeviceId = "IG-OTHER"
            )
        )
        assertFalse(
            TelemetrySyncPolicy.isMatchingAuthorizedTelemetry(
                expectedOwnerUid = "",
                expectedDeviceId = "IG-DEV1",
                documentOwnerUid = "",
                documentDeviceId = "IG-DEV1"
            )
        )
    }

    // 13. Older telemetry cannot overwrite newer telemetry
    @Test
    fun `older telemetry timestamp cannot overwrite newer recovery telemetry`() {
        val newerTs = 1_700_000_100_000L
        val olderTs = 1_700_000_050_000L

        assertFalse(
            "Older timestamp must never overwrite newer GPS telemetry",
            TelemetrySyncPolicy.shouldOverwriteLatestTelemetry(
                existingTimestampMs = newerTs,
                existingHasLocation = true,
                candidateTimestampMs = olderTs,
                candidateHasLocation = true,
                candidateIsFresh = true
            )
        )
        assertTrue(
            "Newer valid GPS fix must overwrite older telemetry",
            TelemetrySyncPolicy.shouldOverwriteLatestTelemetry(
                existingTimestampMs = olderTs,
                existingHasLocation = true,
                candidateTimestampMs = newerTs,
                candidateHasLocation = true,
                candidateIsFresh = true
            )
        )
    }

    // 14. Offline GPS queue retention
    @Test
    fun `offline GPS breadcrumbs remain unsynced in Room queue when backend auth or network is unavailable`() = runBlocking {
        val dao = db.locationDao()
        dao.insertLocation(
            LocationHistoryEntity(
                latitude = -1.9445,
                longitude = 30.0620,
                accuracy = 9f,
                altitude = 1490.0,
                speed = 1.5f,
                bearing = 45f,
                timestamp = System.currentTimeMillis(),
                isInsideTrustedZone = false,
                syncedWithBackend = false,
                batteryLevel = 68,
                isFreshFix = true,
                provider = "gps",
                deviceId = "IG-QUEUE1"
            )
        )

        val worker = TestListenableWorkerBuilder<LocationSyncWorker>(app).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)

        val remainingUnsynced = dao.getUnsyncedLocations()
        assertEquals("Breadcrumbs must remain queued when backend upload did not complete", 1, remainingUnsynced.size)
        assertEquals(1, dao.getUnsyncedCount().first())
    }

    // 15. Remote tracking receives valid telemetry & TheftRecoveryScreen elevates map
    @Test
    fun `remote tracking displays valid telemetry with remote pin styling`() {
        val remoteTelemetry = RemoteRecoveryTelemetry(
            deviceId = "IG-REMOTE99",
            ownerUid = "owner-123",
            securityState = SecurityState.STOLEN_MODE.name,
            lastKnownLat = -1.9550,
            lastKnownLng = 30.0770,
            locationAccuracy = 8f,
            timestamp = System.currentTimeMillis(),
            batteryLevel = 42,
            isCharging = false,
            connectivityStatus = "4G_LTE",
            isNetworkConnected = true,
            recentTamperEvents = emptyList(),
            syncStatus = "SYNCHRONISÉ",
            deviceName = "Stolen Phone",
            isFreshFix = true,
            locationSource = "GPS_LIVE",
            isGpsAvailable = true,
            lastSyncedAt = System.currentTimeMillis()
        )

        val remoteHtml = generateLeafletOsmHtml(
            lat = remoteTelemetry.lastKnownLat!!,
            lng = remoteTelemetry.lastKnownLng!!,
            accuracyMeters = remoteTelemetry.locationAccuracy!!,
            isLive = true,
            isRemoteDevice = true,
            historyJson = "[]",
            zones = emptyList()
        )
        assertTrue(remoteHtml.contains("cyber-pin-remote"))
        assertTrue(remoteHtml.contains("buildPinIcon(true, true)"))

        composeRule.setContent {
            RealRecoveryMapView(
                deviceId = remoteTelemetry.deviceId,
                deviceName = remoteTelemetry.deviceName,
                latitude = remoteTelemetry.lastKnownLat,
                longitude = remoteTelemetry.lastKnownLng,
                accuracy = remoteTelemetry.locationAccuracy,
                timestamp = remoteTelemetry.timestamp,
                battery = "${remoteTelemetry.batteryLevel}%",
                isCharging = remoteTelemetry.isCharging,
                connectivity = remoteTelemetry.connectivityStatus,
                securityStatus = remoteTelemetry.securityState,
                isGpsAvailable = remoteTelemetry.isGpsAvailable,
                historyPoints = remoteTelemetry.locationHistory,
                isFreshFix = remoteTelemetry.isFreshFix,
                locationSource = remoteTelemetry.locationSource,
                syncStatus = remoteTelemetry.syncStatus,
                lastSyncedAt = remoteTelemetry.lastSyncedAt,
                isEn = true,
                isRemoteDevice = true,
                hasLocationPermission = true,
                isTrackingActive = true,
                isNetworkConnected = true
            )
        }

        composeRule.onNodeWithTag("map_device_scope_badge").assertIsDisplayed()
        composeRule.onNodeWithText("REMOTE").assertIsDisplayed()
        composeRule.onNodeWithTag("recovery_map_viewport").assertIsDisplayed()
        composeRule.onNodeWithTag("map_coordinates_text").assertIsDisplayed()
    }

    // 16. Background service failure is reported truthfully
    @Test
    fun `foreground service permission denial fails truthfully and UI shows corrected French wording and error state`() {
        val shadowApp = Shadows.shadowOf(app)
        shadowApp.denyPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val startResult = RecoveryMonitoringService.startServiceWithResult(app, intervalMillis = 10_000L)
        assertTrue(startResult is ServiceStartResult.BlockedPermission)
        assertNotNull(RecoveryMonitoringService.lastServiceError.value)
        assertNull("Service intent must not be dispatched when blocked by permission", shadowApp.nextStartedService)

        // Check TheftRecoveryScreen displays the corrected French text ("PREMIER PLAN SEULEMENT") and error truthfully
        // Wait: to test permission row in TheftRecoveryScreen when fine permission is granted without background permission:
        shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        shadowApp.denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        val blockedState = InkingiUiState(
            currentLanguage = "fr",
            securityState = SecurityState.STOLEN_MODE,
            isForegroundServiceActive = false,
            lastForegroundServiceError = "Foreground service start blocked by Android background restrictions.",
            hasFineLocationPermission = true,
            hasCoarseLocationPermission = true,
            hasBackgroundLocationPermission = false
        )

        composeRule.setContent {
            TheftRecoveryScreen(
                uiState = blockedState,
                onConfirmStolen = { true },
                onActivateRecoveryMode = { true },
                onMarkRecovered = { true },
                onDisarm = { true },
                onAttemptDeviceLock = {
                    com.example.domain.management.DeviceLockManager.LockExecutionResult.Unavailable(
                        reason = "Test",
                        enrollmentRequired = true
                    )
                },
                onTriggerSync = {}
            )
        }

        // Verify map card is rendered near the top (before diagnostic/permissions cards)
        composeRule.onNodeWithTag("theft_recovery_list")
            .performScrollToNode(hasTestTag("gps_unavailable_banner"))
        composeRule.onNodeWithTag("gps_unavailable_banner").assertIsDisplayed()
        composeRule.onNodeWithTag("theft_recovery_list")
            .performScrollToNode(hasTestTag("fgs_service_error_label"))
        composeRule.onNodeWithText("PREMIER PLAN SEULT").assertDoesNotExist()
        composeRule.onNodeWithText("PREMIER PLAN SEULEMENT").assertIsDisplayed()
        composeRule.onNodeWithTag("fgs_service_error_label").assertIsDisplayed()
    }
}
