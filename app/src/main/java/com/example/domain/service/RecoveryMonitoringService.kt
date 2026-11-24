package com.example.domain.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.data.local.InkingiDatabase
import com.example.data.local.entity.LocationHistoryEntity
import com.example.data.repository.DeviceSettingsRepository
import com.example.domain.location.LocationBreadcrumbPolicy
import com.example.domain.sync.LocationSyncWorker
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class ServiceStartResult {
    object Started : ServiceStartResult()
    data class BlockedPermission(val reason: String) : ServiceStartResult()
    data class BlockedBackgroundRestriction(val reason: String) : ServiceStartResult()
    data class Failed(val reason: String) : ServiceStartResult()
}

/**
 * Android Foreground Service for Anti-Theft Suivi Vol:
 * - Registers active LocationManager + FusedLocationProviderClient updates (not getLastKnownLocation polling only)
 * - Operates with START_STICKY to continue background tracking when screen is off within Android OS limits
 * - Separates fresh active fixes (isFreshFix = true) from cached LAST_KNOWN positions
 * - Never fabricates fake fallback coordinates when GPS is unavailable
 * - Persists deduplicated breadcrumbs to Room and syncs offline backlog via WorkManager
 */
class RecoveryMonitoringService : Service(), LocationListener {

    private data class TrackedFix(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val altitude: Double,
        val speed: Float,
        val bearing: Float,
        val timestamp: Long,
        val provider: String,
        val isFreshFix: Boolean,
        val locationSource: String
    )

    companion object {
        const val CHANNEL_ID = "inkingi_recovery_channel"
        const val NOTIFICATION_ID = 2001
        private const val TAG = "RecoveryMonitoringSvc"
        const val EXTRA_CONTACT_NUMBER = "CONTACT_NUMBER"
        const val EXTRA_INTERVAL_MILLIS = "EXTRA_INTERVAL_MILLIS"
        const val DEFAULT_THEFT_INTERVAL_MILLIS = 10_000L

        private val _lastServiceError = MutableStateFlow<String?>(null)
        val lastServiceError: StateFlow<String?> = _lastServiceError.asStateFlow()

        private val _runtimeUnexpectedStop = MutableStateFlow(false)
        val runtimeUnexpectedStop: StateFlow<Boolean> = _runtimeUnexpectedStop.asStateFlow()

        fun hasLocationPermission(context: Context): Boolean {
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            return fine || coarse
        }

        fun hasBackgroundLocationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                androidx.core.content.ContextCompat.checkSelfPermission(
                    context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }

        fun startServiceWithResult(
            context: Context,
            contactNumber: String? = null,
            intervalMillis: Long = DEFAULT_THEFT_INTERVAL_MILLIS
        ): ServiceStartResult {
            if (!hasLocationPermission(context)) {
                val msg = "Autorisation GPS manquante : impossible de démarrer le service de balise."
                _lastServiceError.value = msg
                _runtimeUnexpectedStop.value = true
                Log.w(TAG, "Location permission not granted. Deferring RecoveryMonitoringService startup.")
                return ServiceStartResult.BlockedPermission(msg)
            }
            val validContact = contactNumber?.trim()
                ?.takeIf { DeviceSettingsRepository.isValidRecoveryPhone(it) }
            val intent = Intent(context, RecoveryMonitoringService::class.java).apply {
                if (validContact != null) {
                    putExtra(EXTRA_CONTACT_NUMBER, validContact)
                }
                putExtra(EXTRA_INTERVAL_MILLIS, intervalMillis)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                _lastServiceError.value = null
                _runtimeUnexpectedStop.value = false
                ServiceStartResult.Started
            } catch (e: SecurityException) {
                val msg = "Accès GPS restreint par Android (${e.message ?: "SecurityException"})."
                _lastServiceError.value = msg
                _runtimeUnexpectedStop.value = true
                Log.e(TAG, "SecurityException starting RecoveryMonitoringService: ${e.message}", e)
                ServiceStartResult.BlockedPermission(msg)
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                ) {
                    val msg = "Démarrage en arrière-plan bloqué par Android 12–16 (activez 'Toujours autoriser' et ouvrez l'app)."
                    _lastServiceError.value = msg
                    _runtimeUnexpectedStop.value = true
                    Log.e(TAG, "ForegroundServiceStartNotAllowedException: ${e.message}", e)
                    ServiceStartResult.BlockedBackgroundRestriction(msg)
                } else {
                    val msg = "Échec démarrage balise antivol : ${e.message ?: "erreur système"}."
                    _lastServiceError.value = msg
                    _runtimeUnexpectedStop.value = true
                    Log.e(TAG, "Failed to start RecoveryMonitoringService: ${e.message}", e)
                    ServiceStartResult.Failed(msg)
                }
            }
        }

        fun startService(
            context: Context,
            contactNumber: String? = null,
            intervalMillis: Long = DEFAULT_THEFT_INTERVAL_MILLIS
        ): Boolean {
            return startServiceWithResult(context, contactNumber, intervalMillis) is ServiceStartResult.Started
        }

        fun updateInterval(context: Context, intervalMillis: Long): Boolean {
            if (!hasLocationPermission(context)) return false
            val intent = Intent(context, RecoveryMonitoringService::class.java).apply {
                putExtra(EXTRA_INTERVAL_MILLIS, intervalMillis)
            }
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e is android.app.ForegroundServiceStartNotAllowedException
                ) {
                    _lastServiceError.value = "Mise à jour intervalle bloquée en arrière-plan par Android."
                }
                Log.e(TAG, "Failed to update interval in RecoveryMonitoringService: ${e.message}", e)
                false
            }
        }

        fun stopService(context: Context, preservedStateName: String? = null): Boolean {
            val intent = Intent(context, RecoveryMonitoringService::class.java)
            return try {
                context.stopService(intent)
                val stateToKeep = preservedStateName
                    ?: DeviceSettingsRepository.getPersistedSecurityStateName(context)
                        ?.takeIf { it == "RECOVERED" }
                DeviceSettingsRepository.setStolenTrackingActiveCommitted(context, false, stateToKeep)
                _lastServiceError.value = null
                _runtimeUnexpectedStop.value = false
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop RecoveryMonitoringService: ${e.message}", e)
                false
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trackingJob: Job? = null
    private var contactNumber: String = ""
    private var lockScreenMessage: String = ""

    @Volatile
    private var currentIntervalMillis: Long = DEFAULT_THEFT_INTERVAL_MILLIS

    @Volatile
    private var latestTrackedFix: TrackedFix? = null

    private var locationManager: LocationManager? = null
    private var fusedClient: FusedLocationProviderClient? = null
    private var fusedCallback: LocationCallback? = null

    override fun onCreate() {
        super.onCreate()
        contactNumber = DeviceSettingsRepository.getSavedRecoveryContact(this)
        lockScreenMessage = DeviceSettingsRepository.getSavedLockScreenMessage(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        fusedClient = try {
            LocationServices.getFusedLocationProviderClient(this)
        } catch (_: Exception) {
            null
        }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val savedContact = DeviceSettingsRepository.getSavedRecoveryContact(this)
        lockScreenMessage = DeviceSettingsRepository.getSavedLockScreenMessage(this)
        val extraContact = (intent?.getStringExtra(EXTRA_CONTACT_NUMBER)
            ?: intent?.getStringExtra("CONTACT_NUMBER"))
            ?.trim()
            ?.takeIf { DeviceSettingsRepository.isValidRecoveryPhone(it) }
        contactNumber = extraContact ?: savedContact

        val requestedInterval = intent?.getLongExtra(EXTRA_INTERVAL_MILLIS, -1L) ?: -1L
        val intervalChanged = requestedInterval > 0 && currentIntervalMillis != requestedInterval
        if (requestedInterval > 0) {
            currentIntervalMillis = requestedInterval
        }

        if (!hasLocationPermission(this)) {
            Log.w(TAG, "Aborting RecoveryMonitoringService: location permission missing.")
            _lastServiceError.value = "Autorisation GPS manquante : service arrêté par Android."
            _runtimeUnexpectedStop.value = true
            stopSelf()
            return START_NOT_STICKY
        }

        val currentState = DeviceSettingsRepository.getPersistedSecurityStateName(this) ?: "STOLEN_MODE"
        DeviceSettingsRepository.setStolenTrackingActive(this, true, currentState)

        val notification = buildNotification()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            _lastServiceError.value = null
            _runtimeUnexpectedStop.value = false
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException in startForeground with type location: ${e.message}", e)
            _lastServiceError.value = "Droit de localisation en arrière-plan insuffisant (${e.message ?: "SecurityException"})."
            _runtimeUnexpectedStop.value = true
            stopSelf()
            return START_NOT_STICKY
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e is android.app.ForegroundServiceStartNotAllowedException
            ) {
                _lastServiceError.value = "Service de localisation refusé en arrière-plan par Android 12–16."
            } else {
                _lastServiceError.value = "Erreur démarrage foreground service : ${e.message ?: "inconnue"}."
            }
            _runtimeUnexpectedStop.value = true
            Log.e(TAG, "Error in startForeground with type location: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intervalChanged || fusedCallback == null) {
            registerActiveLocationUpdates(currentIntervalMillis)
        }

        if (trackingJob?.isActive != true || intervalChanged) {
            startStolenTrackingLoop()
        }

        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun registerActiveLocationUpdates(intervalMillis: Long) {
        unregisterActiveLocationUpdates()
        val looper = Looper.getMainLooper()
        val lm = locationManager
        val minDist = if (intervalMillis <= 20_000L) 2f else 5f

        try {
            if (lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) {
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, intervalMillis, minDist, this, looper)
            }
            if (lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    maxOf(intervalMillis, 10_000L),
                    minDist * 2f,
                    this,
                    looper
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unable to register LocationManager updates: ${e.message}")
        }

        try {
            val priority = if (intervalMillis <= 30_000L) {
                Priority.PRIORITY_HIGH_ACCURACY
            } else {
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }
            val request = LocationRequest.Builder(priority, intervalMillis)
                .setMinUpdateIntervalMillis((intervalMillis / 2L).coerceAtLeast(5_000L))
                .setMinUpdateDistanceMeters(minDist)
                .build()
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    val fresh = result.lastLocation ?: return
                    handleFreshLocationFix(fresh, "FUSED_LIVE")
                }
            }
            fusedCallback = callback
            fusedClient?.requestLocationUpdates(request, callback, looper)
            // Actively trigger an immediate fresh fix (do not wait passively for cached lastKnownLocation)
            fusedClient?.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                ?.addOnSuccessListener { loc ->
                    if (loc != null) handleFreshLocationFix(loc, "FUSED_LIVE")
                }
        } catch (e: Exception) {
            Log.d(TAG, "FusedLocation unavailable; using LocationManager: ${e.message}")
        }
    }

    private fun unregisterActiveLocationUpdates() {
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {}
        fusedCallback?.let { cb ->
            try {
                fusedClient?.removeLocationUpdates(cb)
            } catch (_: Exception) {}
        }
        fusedCallback = null
    }

    override fun onLocationChanged(location: Location) {
        val src = when (location.provider) {
            LocationManager.GPS_PROVIDER -> "GPS_LIVE"
            LocationManager.NETWORK_PROVIDER -> "NETWORK_LIVE"
            else -> "GPS_LIVE"
        }
        handleFreshLocationFix(location, src)
    }

    private fun handleFreshLocationFix(location: Location, sourceLabel: String) {
        if (!LocationBreadcrumbPolicy.isValidCoordinate(location.latitude, location.longitude)) return
        val fixTime = System.currentTimeMillis()
        val fix = TrackedFix(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            altitude = if (location.hasAltitude()) location.altitude else 0.0,
            speed = if (location.hasSpeed()) location.speed else 0f,
            bearing = if (location.hasBearing()) location.bearing else 0f,
            timestamp = fixTime,
            provider = location.provider ?: "gps",
            isFreshFix = true,
            locationSource = sourceLabel
        )
        latestTrackedFix = fix

        serviceScope.launch {
            persistAndDispatchFix(fix, isNewFreshFix = true)
        }
    }

    @SuppressLint("MissingPermission")
    private fun queryCachedLastKnownFix(): TrackedFix? {
        val lm = locationManager ?: return null
        return try {
            val gps = if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            } else null
            val net = if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else null
            val best = when {
                gps != null && net != null -> if (gps.time >= net.time) gps else net
                else -> gps ?: net
            } ?: return null

            if (!LocationBreadcrumbPolicy.isValidCoordinate(best.latitude, best.longitude)) return null
            TrackedFix(
                latitude = best.latitude,
                longitude = best.longitude,
                accuracy = best.accuracy,
                altitude = if (best.hasAltitude()) best.altitude else 0.0,
                speed = if (best.hasSpeed()) best.speed else 0f,
                bearing = if (best.hasBearing()) best.bearing else 0f,
                timestamp = best.time.takeIf { it > 0L } ?: System.currentTimeMillis(),
                provider = best.provider ?: "last_known",
                isFreshFix = false,
                locationSource = "LAST_KNOWN"
            )
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun startStolenTrackingLoop() {
        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            while (isActive) {
                try {
                    val activeFix = latestTrackedFix ?: queryCachedLastKnownFix()
                    // Note: cached fallback is NEVER marked as fresh and never inserted as a new breadcrumb
                    persistAndDispatchFix(activeFix, isNewFreshFix = false)
                } catch (e: Exception) {
                    Log.w(TAG, "Error in stolen tracking loop: ${e.message}")
                }
                delay(currentIntervalMillis)
            }
        }
    }

    private suspend fun persistAndDispatchFix(fix: TrackedFix?, isNewFreshFix: Boolean) {
        val deviceId = DeviceSettingsRepository.getDeviceId(this)
        val deviceName = DeviceSettingsRepository.getDeviceName(this)
        val securityState = DeviceSettingsRepository.getPersistedSecurityStateName(this) ?: "STOLEN_MODE"
        val (batteryLevel, isCharging) = readBatteryState()
        val (isOnline, connectivityType) = readNetworkState()
        val lm = locationManager
        val isGpsAvailable = (lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true) ||
            (lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true)

        val isFreshNow = fix != null && !LocationBreadcrumbPolicy.isLocationStale(
            timestampMs = fix.timestamp,
            isFreshFix = fix.isFreshFix
        )

        var insertedBreadcrumb: LocationHistoryEntity? = null
        if (isNewFreshFix && fix != null && fix.isFreshFix) {
            try {
                val dao = InkingiDatabase.getInstance(this).locationDao()
                val previous = dao.getLatestBreadcrumb()
                if (LocationBreadcrumbPolicy.shouldPersistBreadcrumb(
                        previous = previous,
                        newLat = fix.latitude,
                        newLng = fix.longitude,
                        newAccuracy = fix.accuracy,
                        newTimeMs = fix.timestamp
                    )
                ) {
                    val entity = LocationHistoryEntity(
                        latitude = fix.latitude,
                        longitude = fix.longitude,
                        accuracy = fix.accuracy,
                        altitude = fix.altitude,
                        speed = fix.speed,
                        bearing = fix.bearing,
                        timestamp = fix.timestamp,
                        isInsideTrustedZone = false,
                        syncedWithBackend = false,
                        provider = fix.provider,
                        isFreshFix = true,
                        batteryLevel = batteryLevel,
                        deviceId = deviceId
                    )
                    dao.insertLocation(entity)
                    insertedBreadcrumb = dao.getLatestBreadcrumb()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not insert breadcrumb into Room LocationDao: ${e.message}")
            }
        }

        val isFirebaseReady = try {
            FirebaseApp.getApps(this).isNotEmpty()
        } catch (_: Exception) {
            false
        }
        val ownerUid = try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Exception) {
            null
        }

        if (!isFirebaseReady || ownerUid.isNullOrBlank() || !isOnline) {
            LocationSyncWorker.enqueue(applicationContext)
            return
        }

        val now = System.currentTimeMillis()
        val hasCoords = fix != null && LocationBreadcrumbPolicy.isValidCoordinate(fix.latitude, fix.longitude)
        val telemetryTimestamp = fix?.timestamp ?: now
        val safeBattery = batteryLevel.coerceIn(0, 100)

        val telemetryPayload = hashMapOf<String, Any?>(
            "deviceId" to deviceId,
            "ownerUid" to ownerUid,
            "deviceName" to deviceName,
            "securityState" to securityState,
            "status" to securityState,
            "hasLocation" to hasCoords,
            "lastKnownLat" to if (hasCoords) fix?.latitude else null,
            "lastKnownLng" to if (hasCoords) fix?.longitude else null,
            "locationAccuracy" to if (hasCoords) fix?.accuracy else null,
            "altitude" to if (hasCoords) fix?.altitude else null,
            "speed" to if (hasCoords) fix?.speed else null,
            "bearing" to if (hasCoords) fix?.bearing else null,
            "isFreshFix" to isFreshNow,
            "locationSource" to (fix?.locationSource ?: "UNAVAILABLE"),
            "isGpsAvailable" to isGpsAvailable,
            "timestamp" to telemetryTimestamp,
            "batteryLevel" to safeBattery,
            "battery" to "$safeBattery%",
            "isCharging" to isCharging,
            "connectivityStatus" to connectivityType,
            "isNetworkConnected" to isOnline,
            "contactNumber" to contactNumber,
            "provider" to (fix?.provider ?: "gps_foreground_service"),
            "syncStatus" to "SYNCHRONISÉ",
            "lastSyncedAt" to now,
            "updatedAt" to now
        )

        val summaryPayload = hashMapOf<String, Any?>(
            "deviceId" to deviceId,
            "ownerUid" to ownerUid,
            "deviceName" to deviceName,
            "securityState" to securityState,
            "hasLocation" to hasCoords,
            "lastKnownLat" to if (hasCoords) fix?.latitude else null,
            "lastKnownLng" to if (hasCoords) fix?.longitude else null,
            "locationAccuracy" to if (hasCoords) fix?.accuracy else null,
            "timestamp" to telemetryTimestamp,
            "isFreshFix" to isFreshNow,
            "locationSource" to (fix?.locationSource ?: "UNAVAILABLE"),
            "isGpsAvailable" to isGpsAvailable,
            "batteryLevel" to safeBattery,
            "isCharging" to isCharging,
            "connectivityStatus" to connectivityType,
            "isNetworkConnected" to isOnline,
            "lastSyncedAt" to now,
            "updatedAt" to now
        )

        try {
            val db = FirebaseFirestore.getInstance()
            val deviceRef = db.collection("users")
                .document(ownerUid)
                .collection("devices")
                .document(deviceId)
            val latestRef = deviceRef.collection("recovery_telemetry").document("latest")

            val existingLatest = try { latestRef.get().await() } catch (_: Exception) { null }
            val existingTs = existingLatest?.getLong("timestamp")
            val existingHasLoc = existingLatest?.let {
                LocationBreadcrumbPolicy.isValidCoordinate(
                    it.getDouble("lastKnownLat") ?: it.getDouble("lat"),
                    it.getDouble("lastKnownLng") ?: it.getDouble("lng")
                )
            } ?: false

            val shouldWriteLatest = com.example.domain.recovery.TelemetrySyncPolicy.shouldOverwriteLatestTelemetry(
                existingTimestampMs = existingTs,
                existingHasLocation = existingHasLoc,
                candidateTimestampMs = telemetryTimestamp,
                candidateHasLocation = hasCoords,
                candidateIsFresh = isFreshNow
            )

            deviceRef.set(summaryPayload, SetOptions.merge()).await()
            if (shouldWriteLatest) {
                latestRef.set(telemetryPayload).await()
            }

            if (insertedBreadcrumb != null) {
                val bcDocId = "bc_${insertedBreadcrumb.timestamp}_${insertedBreadcrumb.id}"
                val bcMap = mapOf(
                    "id" to bcDocId,
                    "deviceId" to deviceId,
                    "ownerUid" to ownerUid,
                    "latitude" to insertedBreadcrumb.latitude,
                    "longitude" to insertedBreadcrumb.longitude,
                    "accuracy" to insertedBreadcrumb.accuracy,
                    "altitude" to insertedBreadcrumb.altitude,
                    "speed" to insertedBreadcrumb.speed,
                    "bearing" to insertedBreadcrumb.bearing,
                    "batteryLevel" to insertedBreadcrumb.batteryLevel,
                    "timestamp" to insertedBreadcrumb.timestamp,
                    "isFreshFix" to insertedBreadcrumb.isFreshFix,
                    "provider" to insertedBreadcrumb.provider,
                    "isInsideTrustedZone" to insertedBreadcrumb.isInsideTrustedZone,
                    "dateKey" to LocationSyncWorker.formatUtcDateKey(insertedBreadcrumb.timestamp)
                )
                deviceRef.collection("location_breadcrumbs")
                    .document(bcDocId)
                    .set(bcMap, SetOptions.merge())
                    .await()
                InkingiDatabase.getInstance(this).locationDao().markSynced(listOf(insertedBreadcrumb.id))
            }

            DeviceSettingsRepository.saveLastSuccessfulSyncTimestamp(this, now)
            Log.i(TAG, "Synchronized recovery telemetry for device $deviceId (fresh=$isFreshNow)")
        } catch (e: Exception) {
            Log.w(TAG, "Error dispatching owner-scoped telemetry; scheduling WorkManager retry: ${e.message}")
            LocationSyncWorker.enqueue(applicationContext)
        }
    }

    private fun readBatteryState(): Pair<Int, Boolean> {
        return try {
            val batteryIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else {
                val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 80
            }
            val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            pct.coerceIn(0, 100) to charging
        } catch (_: Exception) {
            80 to false
        }
    }

    private fun readNetworkState(): Pair<Boolean, String> {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false to "OFFLINE"
            val network = cm.activeNetwork ?: return false to "OFFLINE"
            val caps = cm.getNetworkCapabilities(network) ?: return false to "OFFLINE"
            val connected = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val label = when {
                !connected -> "OFFLINE"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WIFI"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "CELLULAR"
                else -> "ONLINE"
            }
            connected to label
        } catch (_: Exception) {
            false to "OFFLINE"
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "InkingiGuard Suivi Vol",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Balise de localisation active pour téléphone volé."
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val contentText = when {
            contactNumber.isNotBlank() -> "Volé — Contacter d'urgence $contactNumber"
            lockScreenMessage.isNotBlank() -> lockScreenMessage
            else -> "Volé — Suivi de localisation actif"
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("APPAREIL DÉCLARÉ VOLÉ")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_security)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)

        if (lockScreenMessage.isNotBlank()) {
            val expanded = if (contactNumber.isNotBlank()) {
                "$lockScreenMessage\nContacter d'urgence : $contactNumber"
            } else {
                lockScreenMessage
            }
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(expanded))
        }
        return builder.build()
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onDestroy() {
        super.onDestroy()
        unregisterActiveLocationUpdates()
        trackingJob?.cancel()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
