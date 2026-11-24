package com.example.domain.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import com.example.core.model.LocationEvent
import com.example.core.model.TrustedZone
import com.example.data.local.dao.LocationDao
import com.example.data.local.entity.LocationHistoryEntity
import com.example.data.repository.DeviceSettingsRepository
import com.example.domain.sync.LocationSyncWorker
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure Kotlin validation, deduplication, and staleness policy for GPS fixes and breadcrumbs.
 */
object LocationBreadcrumbPolicy {
    const val LIVE_FIX_MAX_AGE_MS: Long = 120_000L
    const val DEFAULT_MIN_DISTANCE_METERS: Float = 15f
    const val DEFAULT_MIN_INTERVAL_MS: Long = 30_000L

    fun isValidCoordinate(lat: Double?, lng: Double?): Boolean {
        if (lat == null || lng == null) return false
        if (!lat.isFinite() || !lng.isFinite()) return false
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return false
        if (lat == 0.0 && lng == 0.0) return false
        return true
    }

    fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Float {
        val earthRadiusM = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (earthRadiusM * c).toFloat()
    }

    /**
     * Avoids storing excessive duplicate breadcrumbs when stationary while still preserving:
     * - first recorded fix
     * - significant movement (>= minDistanceMeters)
     * - periodic movement/heartbeat checkpoints (>= minIntervalMs)
     * - major GPS accuracy improvement (at least 50% sharper)
     */
    fun shouldPersistBreadcrumb(
        previous: LocationHistoryEntity?,
        newLat: Double,
        newLng: Double,
        newAccuracy: Float,
        newTimeMs: Long,
        minDistanceMeters: Float = DEFAULT_MIN_DISTANCE_METERS,
        minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS
    ): Boolean {
        if (!isValidCoordinate(newLat, newLng)) return false
        if (previous == null) return true

        val timeDelta = newTimeMs - previous.timestamp
        if (timeDelta < 0L) return false

        val movedMeters = distanceMeters(previous.latitude, previous.longitude, newLat, newLng)
        if (movedMeters >= minDistanceMeters) return true
        if (timeDelta >= minIntervalMs) return true
        if (previous.accuracy > 0f && newAccuracy > 0f && newAccuracy <= previous.accuracy * 0.5f) {
            return true
        }
        return false
    }

    fun isLocationStale(
        timestampMs: Long,
        isFreshFix: Boolean,
        nowMs: Long = System.currentTimeMillis(),
        maxLiveAgeMs: Long = LIVE_FIX_MAX_AGE_MS
    ): Boolean {
        if (!isFreshFix || timestampMs <= 0L) return true
        val age = nowMs - timestampMs
        return age < 0L || age > maxLiveAgeMs
    }
}

/**
 * Location Intelligence Engine.
 * Interacts with Android FusedLocationProviderClient + LocationManager to obtain fresh GPS fixes,
 * distinguishes live fixes from cached getLastKnownLocation() seeds, deduplicates breadcrumbs,
 * and triggers WorkManager offline sync on unuploaded records.
 */
class LocationIntelligenceEngine(
    private val context: Context,
    private val locationDao: LocationDao,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val batteryPercentageProvider: () -> Int = { -1 },
    private val deviceIdProvider: () -> String = { DeviceSettingsRepository.getDeviceId(context) }
) : LocationListener {

    companion object {
        private const val TAG = "LocationIntelEngine"
    }

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val fusedClient: FusedLocationProviderClient? by lazy {
        try {
            LocationServices.getFusedLocationProviderClient(context)
        } catch (_: Exception) {
            null
        }
    }

    private var fusedCallback: LocationCallback? = null
    private var freshLocationCancellationSignal: android.os.CancellationSignal? = null

    private val _lastLocation = MutableStateFlow<LocationEvent?>(null)
    val lastLocation: StateFlow<LocationEvent?> = _lastLocation.asStateFlow()

    private val _trustedZones = MutableStateFlow<List<TrustedZone>>(emptyList())
    val trustedZones: StateFlow<List<TrustedZone>> = _trustedZones.asStateFlow()

    private val _isGpsAvailable = MutableStateFlow(true)
    val isGpsAvailable: StateFlow<Boolean> = _isGpsAvailable.asStateFlow()

    private var currentIntervalMillis: Long = 300_000L
    private var isUpdatesActive: Boolean = false
    private var isLocationListenerRegistered: Boolean = false
    private var lastRegisteredIntervalMillis: Long = -1L

    val activeIntervalMillis: Long
        get() = currentIntervalMillis

    val isTracking: Boolean
        get() = isUpdatesActive

    fun hasFineLocationPermission(): Boolean {
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun hasLocationPermission(): Boolean {
        val fine = hasFineLocationPermission()
        val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasBackgroundLocationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    fun isAnyProviderEnabled(): Boolean {
        val lm = locationManager ?: return false
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Safely ensures active location updates are running once permission has been granted,
     * recovering from an earlier permission-denied failure without creating duplicate listeners.
     */
    @Synchronized
    fun ensureLocationUpdatesStarted(intervalMillis: Long = currentIntervalMillis): Boolean {
        if (!hasLocationPermission()) {
            _isGpsAvailable.value = false
            isUpdatesActive = false
            removeActiveListeners()
            return false
        }
        val providerEnabled = isAnyProviderEnabled()
        _isGpsAvailable.value = providerEnabled
        if (isUpdatesActive &&
            (isLocationListenerRegistered || fusedCallback != null) &&
            lastRegisteredIntervalMillis == intervalMillis
        ) {
            return providerEnabled
        }
        return startLocationUpdates(intervalMillis)
    }

    fun updateTrustedZones(zones: List<TrustedZone>) {
        _trustedZones.value = zones
        _lastLocation.value?.let { current ->
            evaluateLocation(
                latitude = current.latitude,
                longitude = current.longitude,
                accuracy = current.accuracy,
                altitude = current.altitude,
                speed = current.speed,
                bearing = current.bearing,
                provider = current.provider,
                timestampMs = current.timestamp,
                isFreshFix = current.isFreshFix,
                locationSource = current.locationSource,
                persistBreadcrumb = false
            )
        }
    }

    /**
     * Dynamically updates the GPS sampling interval based on AdaptiveMonitoringPolicy.
     * Reschedules providers cleanly if tracking is currently active.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun applyMonitoringInterval(intervalMillis: Long): Boolean {
        if (currentIntervalMillis == intervalMillis && isUpdatesActive && (isLocationListenerRegistered || fusedCallback != null)) {
            return true
        }
        currentIntervalMillis = intervalMillis
        if (!isUpdatesActive) {
            return true
        }
        stopLocationUpdates()
        return startLocationUpdates(currentIntervalMillis)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startLocationUpdates(intervalMillis: Long = currentIntervalMillis): Boolean {
        currentIntervalMillis = intervalMillis
        val lm = locationManager ?: return false
        if (!hasLocationPermission()) {
            _isGpsAvailable.value = false
            isUpdatesActive = false
            removeActiveListeners()
            return false
        }
        if (isUpdatesActive &&
            (isLocationListenerRegistered || fusedCallback != null) &&
            lastRegisteredIntervalMillis == intervalMillis
        ) {
            val isEnabled = isAnyProviderEnabled()
            _isGpsAvailable.value = isEnabled
            return isEnabled
        }
        return try {
            val isGpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            _isGpsAvailable.value = isGpsEnabled || isNetworkEnabled

            removeActiveListeners()

            if (!isGpsEnabled && !isNetworkEnabled) {
                isUpdatesActive = false
                lastRegisteredIntervalMillis = -1L
                return false
            }

            val minDistance = if (intervalMillis <= 20_000L) 2f else 5f
            val looper = Looper.getMainLooper()

            if (isGpsEnabled) {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    intervalMillis,
                    minDistance,
                    this,
                    looper
                )
                isLocationListenerRegistered = true
            }
            if (isNetworkEnabled) {
                val netInterval = maxOf(intervalMillis, 10_000L)
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    netInterval,
                    minDistance * 2,
                    this,
                    looper
                )
                isLocationListenerRegistered = true
            }

            registerFusedUpdates(intervalMillis, looper)

            isUpdatesActive = isLocationListenerRegistered || fusedCallback != null
            lastRegisteredIntervalMillis = if (isUpdatesActive) intervalMillis else -1L

            // Seed initial reference ONLY as non-fresh LAST_KNOWN (never marked live or stored as new breadcrumb)
            val lastGps = if (isGpsEnabled) lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) else null
            val lastNet = if (isNetworkEnabled) lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) else null
            val lastKnown = pickNewerLocation(lastGps, lastNet)

            if (lastKnown != null && LocationBreadcrumbPolicy.isValidCoordinate(lastKnown.latitude, lastKnown.longitude)) {
                val cachedTs = lastKnown.time.takeIf { it > 0L } ?: System.currentTimeMillis()
                evaluateLocation(
                    latitude = lastKnown.latitude,
                    longitude = lastKnown.longitude,
                    accuracy = lastKnown.accuracy,
                    altitude = lastKnown.altitude,
                    speed = lastKnown.speed,
                    bearing = lastKnown.bearing,
                    provider = lastKnown.provider ?: "system",
                    timestampMs = cachedTs,
                    isFreshFix = false,
                    locationSource = "LAST_KNOWN",
                    persistBreadcrumb = false
                )
            }

            // Always request a genuine fresh location fix when starting active tracking
            if (isGpsEnabled || isNetworkEnabled) {
                requestFreshLocationFix()
            }
            isUpdatesActive
        } catch (_: SecurityException) {
            removeActiveListeners()
            _isGpsAvailable.value = false
            isUpdatesActive = false
            lastRegisteredIntervalMillis = -1L
            false
        } catch (_: Exception) {
            removeActiveListeners()
            _isGpsAvailable.value = false
            isUpdatesActive = false
            lastRegisteredIntervalMillis = -1L
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun requestFreshLocationFix() {
        if (!hasLocationPermission()) {
            _isGpsAvailable.value = false
            return
        }
        try {
            fusedClient?.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                ?.addOnSuccessListener { location ->
                    if (location != null) {
                        recordFreshFix(location, "FUSED_LIVE")
                    }
                }
        } catch (_: Exception) {}

        val lm = locationManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val provider = when {
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                    else -> null
                }
                if (provider != null) {
                    freshLocationCancellationSignal?.cancel()
                    val signal = android.os.CancellationSignal()
                    freshLocationCancellationSignal = signal
                    lm.getCurrentLocation(
                        provider,
                        signal,
                        context.mainExecutor
                    ) { loc ->
                        if (loc != null) {
                            recordFreshFix(loc, if (provider == LocationManager.GPS_PROVIDER) "GPS_LIVE" else "NETWORK_LIVE")
                        }
                    }
                }
            }
        } catch (_: SecurityException) {
            _isGpsAvailable.value = false
        } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    private fun registerFusedUpdates(intervalMillis: Long, looper: Looper) {
        val client = fusedClient ?: return
        try {
            val priority = if (intervalMillis <= 30_000L) {
                Priority.PRIORITY_HIGH_ACCURACY
            } else {
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }
            val request = LocationRequest.Builder(priority, intervalMillis)
                .setMinUpdateIntervalMillis((intervalMillis / 2L).coerceAtLeast(5_000L))
                .setMinUpdateDistanceMeters(if (intervalMillis <= 20_000L) 2f else 5f)
                .build()

            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val fresh = result.lastLocation ?: return
                    recordFreshFix(fresh, "FUSED_LIVE")
                }
            }
            fusedCallback = callback
            client.requestLocationUpdates(request, callback, looper)
        } catch (e: Exception) {
            Log.d(TAG, "FusedLocation fallback to LocationManager only: ${e.message}")
        }
    }

    private fun removeActiveListeners() {
        try {
            freshLocationCancellationSignal?.cancel()
        } catch (_: Exception) {}
        freshLocationCancellationSignal = null
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}
        isLocationListenerRegistered = false
        fusedCallback?.let { cb ->
            try {
                fusedClient?.removeLocationUpdates(cb)
            } catch (_: Exception) {}
        }
        fusedCallback = null
        lastRegisteredIntervalMillis = -1L
    }

    @Synchronized
    fun stopLocationUpdates() {
        isUpdatesActive = false
        removeActiveListeners()
    }

    override fun onLocationChanged(location: Location) {
        val src = when (location.provider) {
            LocationManager.GPS_PROVIDER -> "GPS_LIVE"
            LocationManager.NETWORK_PROVIDER -> "NETWORK_LIVE"
            else -> "GPS_LIVE"
        }
        recordFreshFix(location, src)
    }

    internal fun recordFreshFix(location: Location, sourceLabel: String) {
        if (!LocationBreadcrumbPolicy.isValidCoordinate(location.latitude, location.longitude)) return
        val fixTimestamp = System.currentTimeMillis()
        evaluateLocation(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            speed = if (location.hasSpeed()) location.speed else 0f,
            bearing = if (location.hasBearing()) location.bearing else 0f,
            provider = location.provider ?: "gps",
            timestampMs = fixTimestamp,
            isFreshFix = true,
            locationSource = sourceLabel,
            persistBreadcrumb = true
        )
    }

    private fun pickNewerLocation(a: Location?, b: Location?): Location? {
        if (a == null) return b
        if (b == null) return a
        return if (a.time >= b.time) a else b
    }

    private fun evaluateLocation(
        latitude: Double,
        longitude: Double,
        accuracy: Float,
        altitude: Double,
        speed: Float,
        bearing: Float,
        provider: String,
        timestampMs: Long = System.currentTimeMillis(),
        isFreshFix: Boolean = true,
        locationSource: String = "GPS_LIVE",
        persistBreadcrumb: Boolean = true
    ) {
        if (!LocationBreadcrumbPolicy.isValidCoordinate(latitude, longitude)) return

        val zones = _trustedZones.value
        var isInsideAny = zones.isEmpty()
        var closestZone: TrustedZone? = null
        var minDistance = Float.MAX_VALUE

        val results = FloatArray(1)
        for (zone in zones.filter { it.isEnabled }) {
            Location.distanceBetween(latitude, longitude, zone.latitude, zone.longitude, results)
            val dist = results[0]
            if (dist < minDistance) {
                minDistance = dist
                closestZone = zone
            }
            if (dist <= zone.radiusMeters) {
                isInsideAny = true
            }
        }

        val locationEvent = LocationEvent(
            latitude = latitude,
            longitude = longitude,
            accuracy = accuracy,
            altitude = altitude,
            speed = speed,
            bearing = bearing,
            timestamp = timestampMs,
            provider = provider,
            isInsideTrustedZone = isInsideAny,
            closestZoneName = closestZone?.name,
            distanceToClosestZoneMeters = if (closestZone != null) minDistance else 0f,
            isFreshFix = isFreshFix,
            locationSource = locationSource
        )

        _lastLocation.value = locationEvent

        if (!persistBreadcrumb) return

        // Persist deduplicated fresh breadcrumb to local database and schedule deferred sync
        scope.launch {
            try {
                val previous = locationDao.getLatestBreadcrumb()
                if (LocationBreadcrumbPolicy.shouldPersistBreadcrumb(
                        previous = previous,
                        newLat = latitude,
                        newLng = longitude,
                        newAccuracy = accuracy,
                        newTimeMs = timestampMs
                    )
                ) {
                    locationDao.insertLocation(
                        LocationHistoryEntity(
                            latitude = latitude,
                            longitude = longitude,
                            accuracy = accuracy,
                            altitude = altitude,
                            speed = speed,
                            bearing = bearing,
                            timestamp = timestampMs,
                            isInsideTrustedZone = isInsideAny,
                            syncedWithBackend = false,
                            provider = provider,
                            isFreshFix = isFreshFix,
                            batteryLevel = batteryPercentageProvider(),
                            deviceId = deviceIdProvider()
                        )
                    )
                    LocationSyncWorker.enqueue(context)
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * For manual diagnostic simulation only (explicitly tagged as simulation, never live GPS).
     */
    fun setSimulatedLocation(lat: Double, lng: Double, insideZone: Boolean) {
        _lastLocation.value = LocationEvent(
            latitude = lat,
            longitude = lng,
            accuracy = 12f,
            altitude = 150.0,
            speed = 1.2f,
            bearing = 45f,
            timestamp = System.currentTimeMillis(),
            provider = "simulation",
            isInsideTrustedZone = insideZone,
            closestZoneName = if (insideZone) "Registered Home" else "Unknown Perimeter",
            distanceToClosestZoneMeters = if (insideZone) 25f else 1850f,
            isFreshFix = false,
            locationSource = "SIMULATION"
        )
    }

    override fun onProviderEnabled(provider: String) {
        _isGpsAvailable.value = true
    }

    override fun onProviderDisabled(provider: String) {
        val lm = locationManager
        val gpsOn = lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
        val netOn = lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ?: false
        _isGpsAvailable.value = gpsOn || netOn
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
}
