package com.example.domain.recovery

import android.content.Context
import android.util.Log
import com.example.core.model.CommandStatus
import com.example.core.model.CommandType
import com.example.core.model.DeviceHealthInfo
import com.example.core.model.LocationEvent
import com.example.core.model.NetworkSecurityInfo
import com.example.core.model.SecurityCommand
import com.example.core.model.SecurityEvent
import com.example.core.model.SecurityState
import com.example.data.local.dao.LocationDao
import com.example.data.local.dao.SecurityCommandDao
import com.example.data.local.entity.LocationHistoryEntity
import com.example.data.local.entity.SecurityCommandEntity
import com.example.data.repository.DeviceSettingsRepository
import com.example.domain.command.SecurityCommandProcessor
import com.example.domain.location.LocationBreadcrumbPolicy
import com.example.domain.sync.LocationSyncWorker
import com.example.domain.sync.SecurityEventQueue
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Timestamped location breadcrumb for remote map movement history.
 */
data class LocationBreadcrumbPoint(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val timestamp: Long,
    val isInsideTrustedZone: Boolean,
    val altitude: Double = 0.0,
    val speed: Float = 0f,
    val bearing: Float = 0f,
    val batteryLevel: Int = -1,
    val isFreshFix: Boolean = false,
    val provider: String = "gps"
)

data class RemoteRecoveryTelemetry(
    val deviceId: String,
    val ownerUid: String,
    val securityState: String,
    val lastKnownLat: Double?,
    val lastKnownLng: Double?,
    val locationAccuracy: Float?,
    val timestamp: Long,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val connectivityStatus: String,
    val isNetworkConnected: Boolean,
    val recentTamperEvents: List<String>,
    val syncStatus: String = "LOCAL",
    val locationHistory: List<LocationBreadcrumbPoint> = emptyList(),
    val deviceName: String = "",
    val altitude: Double? = null,
    val speed: Float? = null,
    val bearing: Float? = null,
    val isFreshFix: Boolean = false,
    val locationSource: String = "LAST_KNOWN",
    val isGpsAvailable: Boolean = true,
    val lastSyncedAt: Long? = null
)

data class RemoteTrackedDeviceSummary(
    val deviceId: String,
    val deviceName: String,
    val ownerUid: String,
    val securityState: String,
    val lastKnownLat: Double?,
    val lastKnownLng: Double?,
    val locationAccuracy: Float?,
    val timestamp: Long,
    val isFreshFix: Boolean,
    val locationSource: String,
    val isGpsAvailable: Boolean,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val connectivityStatus: String,
    val isNetworkConnected: Boolean,
    val updatedAt: Long
)

/**
 * Pure monotonic telemetry ordering and freshness policy shared between
 * SecureRecoveryBridge, LocationSyncWorker, and RecoveryMonitoringService.
 */
object TelemetrySyncPolicy {
    fun shouldOverwriteLatestTelemetry(
        existingTimestampMs: Long?,
        existingHasLocation: Boolean,
        candidateTimestampMs: Long,
        candidateHasLocation: Boolean,
        candidateIsFresh: Boolean
    ): Boolean {
        if (candidateTimestampMs <= 0L) return false
        if (existingTimestampMs == null || existingTimestampMs <= 0L) return true
        if (existingHasLocation && !candidateHasLocation) return false
        if (!existingHasLocation && candidateHasLocation) return true
        if (candidateTimestampMs > existingTimestampMs) return true
        if (candidateTimestampMs == existingTimestampMs && candidateIsFresh) return true
        return false
    }

    fun isMatchingAuthorizedTelemetry(
        expectedOwnerUid: String,
        expectedDeviceId: String,
        documentOwnerUid: String?,
        documentDeviceId: String?
    ): Boolean {
        val cleanUid = expectedOwnerUid.trim()
        val cleanDev = expectedDeviceId.trim()
        if (cleanUid.isBlank() || cleanDev.isBlank()) return false
        if (!documentOwnerUid.isNullOrBlank() && documentOwnerUid.trim() != cleanUid) return false
        if (!documentDeviceId.isNullOrBlank() && documentDeviceId.trim() != cleanDev) return false
        return true
    }
}

/**
 * Secure Remote Recovery & Find-My-Phone Bridge.
 *
 * Enforces:
 * 1. Owner-scoped isolation under:
 *    - users/{ownerUid}/devices/{deviceId}
 *    - users/{ownerUid}/devices/{deviceId}/recovery_telemetry/latest
 *    - users/{ownerUid}/devices/{deviceId}/location_breadcrumbs/{breadcrumbId}
 *    - users/{ownerUid}/devices/{deviceId}/commands/{commandId}
 * 2. Cross-user prevention: Only the signed-in owner UID can query or update their own devices.
 * 3. Offline Resilience: Queues unsynced breadcrumbs in Room and dispatches WorkManager LocationSyncWorker.
 * 4. Freshness Integrity: Separates verified fresh GPS fixes from LAST_KNOWN or stale positions.
 */
class SecureRecoveryBridge(
    private val context: Context,
    private val deviceId: String,
    private val commandProcessor: SecurityCommandProcessor,
    private val commandDao: SecurityCommandDao,
    private val eventQueue: SecurityEventQueue? = null,
    private val locationDao: LocationDao? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val authenticatedOwnerUidProvider: (() -> String?)? = null,
    private val activeDeviceIdProvider: (() -> String)? = null,
    private val clockProvider: () -> Long = { System.currentTimeMillis() },
    private val remoteStatusUpdater: (suspend (ownerUid: String, deviceId: String, commandId: String, status: CommandStatus, resultNote: String) -> Unit)? = null
) {
    companion object {
        private const val TAG = "SecureRecoveryBridge"
        const val COMMAND_EXPIRATION_WINDOW_MS = 10 * 60 * 1000L // 10 minutes max validity
        private const val MAX_FUTURE_CLOCK_SKEW_MS = 300_000L
        private const val MAX_PAYLOAD_LENGTH = 512
    }

    internal data class RemoteCommandEnvelope(
        val documentId: String,
        val fieldId: String? = null,
        val pathOwnerUid: String,
        val documentOwnerUid: String? = null,
        val issuedBy: String?,
        val targetDeviceId: String?,
        val commandTypeRaw: String?,
        val statusRaw: String? = CommandStatus.PENDING.name,
        val createdTimestamp: Long?,
        val expirationTimestamp: Long?,
        val payload: Any? = "",
        val ownerConfirmed: Boolean? = null
    )

    private val remoteCommandMutex = Mutex()

    private val firestoreOrNull: FirebaseFirestore?
        get() = try {
            if (FirebaseApp.getApps(context).isNotEmpty()) FirebaseFirestore.getInstance() else null
        } catch (_: Exception) {
            null
        }

    private val authOrNull: FirebaseAuth?
        get() = try {
            if (FirebaseApp.getApps(context).isNotEmpty()) FirebaseAuth.getInstance() else null
        } catch (_: Exception) {
            null
        }

    private var commandListenerRegistration: ListenerRegistration? = null
    private val _lastSyncedTelemetry = MutableStateFlow<RemoteRecoveryTelemetry?>(null)
    val lastSyncedTelemetry: StateFlow<RemoteRecoveryTelemetry?> = _lastSyncedTelemetry.asStateFlow()

    private val _syncStatusState = MutableStateFlow("IDLE")
    val syncStatusState: StateFlow<String> = _syncStatusState.asStateFlow()

    private val _lastSuccessfulSyncAt = MutableStateFlow(DeviceSettingsRepository.getLastSuccessfulSyncTimestamp(context))
    val lastSuccessfulSyncAt: StateFlow<Long?> = _lastSuccessfulSyncAt.asStateFlow()

    /**
     * Publishes local telemetry securely to the owner-scoped Firestore path.
     * Never writes 0.0 / fabricated coordinates when GPS is unavailable.
     * Falls back to WorkManager offline sync if offline or unauthenticated.
     */
    suspend fun syncTelemetry(
        state: SecurityState,
        location: LocationEvent?,
        health: DeviceHealthInfo?,
        network: NetworkSecurityInfo?,
        recentEvents: List<SecurityEvent>,
        isGpsAvailable: Boolean = true
    ): RemoteRecoveryTelemetry = withContext(Dispatchers.IO) {
        val currentUid = try {
            (authenticatedOwnerUidProvider?.invoke() ?: authOrNull?.currentUser?.uid)?.trim()
        } catch (_: Exception) {
            null
        }
        val activeDeviceId = (activeDeviceIdProvider?.invoke()
            ?: DeviceSettingsRepository.getDeviceId(context).ifBlank { deviceId }).trim()
        val deviceName = DeviceSettingsRepository.getDeviceName(context)
        val now = clockProvider()
        val hasValidCoords = location != null && LocationBreadcrumbPolicy.isValidCoordinate(location.latitude, location.longitude)
        val locTimestamp = if (hasValidCoords) (location?.timestamp ?: now) else now
        val isFreshNow = hasValidCoords && location != null && !LocationBreadcrumbPolicy.isLocationStale(
            timestampMs = locTimestamp,
            isFreshFix = location.isFreshFix,
            nowMs = now
        )
        val safeBattery = (health?.batteryPercentage ?: 80).coerceIn(0, 100)

        val recentTamperTitles = recentEvents.take(5).map { "${it.title}: ${it.description}" }

        val breadcrumbs = if (hasValidCoords && location != null) {
            listOf(
                LocationBreadcrumbPoint(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    timestamp = locTimestamp,
                    isInsideTrustedZone = location.isInsideTrustedZone,
                    altitude = location.altitude,
                    speed = location.speed,
                    bearing = location.bearing,
                    batteryLevel = safeBattery,
                    isFreshFix = isFreshNow,
                    provider = location.provider
                )
            )
        } else emptyList()

        val initialSyncStatus = if (!currentUid.isNullOrBlank()) "LOCAL" else "LOCAL_NON_AUTHENTIFIÉ"

        val telemetry = RemoteRecoveryTelemetry(
            deviceId = activeDeviceId,
            ownerUid = currentUid ?: "",
            securityState = state.name,
            lastKnownLat = if (hasValidCoords) location?.latitude else null,
            lastKnownLng = if (hasValidCoords) location?.longitude else null,
            locationAccuracy = if (hasValidCoords) location?.accuracy else null,
            timestamp = locTimestamp,
            batteryLevel = safeBattery,
            isCharging = health?.isCharging ?: false,
            connectivityStatus = if (network?.isConnected == true) network.networkType else "DÉCONNECTÉ",
            isNetworkConnected = network?.isConnected ?: true,
            recentTamperEvents = recentTamperTitles,
            syncStatus = initialSyncStatus,
            locationHistory = breadcrumbs,
            deviceName = deviceName,
            altitude = if (hasValidCoords) location?.altitude else null,
            speed = if (hasValidCoords) location?.speed else null,
            bearing = if (hasValidCoords) location?.bearing else null,
            isFreshFix = isFreshNow,
            locationSource = if (hasValidCoords) (location?.locationSource ?: "SYSTEM") else "UNAVAILABLE",
            isGpsAvailable = isGpsAvailable,
            lastSyncedAt = _lastSuccessfulSyncAt.value
        )

        val currentCached = _lastSyncedTelemetry.value
        if (currentCached == null ||
            currentCached.deviceId != activeDeviceId ||
            TelemetrySyncPolicy.shouldOverwriteLatestTelemetry(
                existingTimestampMs = currentCached.timestamp,
                existingHasLocation = LocationBreadcrumbPolicy.isValidCoordinate(currentCached.lastKnownLat, currentCached.lastKnownLng),
                candidateTimestampMs = locTimestamp,
                candidateHasLocation = hasValidCoords,
                candidateIsFresh = isFreshNow
            )
        ) {
            _lastSyncedTelemetry.value = telemetry
        }

        if (currentUid.isNullOrBlank()) {
            _syncStatusState.value = "LOCAL_NON_AUTHENTIFIÉ"
            return@withContext telemetry
        }

        val db = firestoreOrNull
        if (db == null || network?.isConnected == false) {
            _syncStatusState.value = "OFFLINE_QUEUED"
            val queuedTelemetry = telemetry.copy(syncStatus = "LOCAL_BUFFERED")
            _lastSyncedTelemetry.value = queuedTelemetry
            LocationSyncWorker.enqueue(context)
            return@withContext queuedTelemetry
        }

        try {
            val firestoreMap = hashMapOf<String, Any?>(
                "deviceId" to activeDeviceId,
                "ownerUid" to currentUid,
                "deviceName" to deviceName,
                "securityState" to state.name,
                "hasLocation" to hasValidCoords,
                "lastKnownLat" to if (hasValidCoords) location?.latitude else null,
                "lastKnownLng" to if (hasValidCoords) location?.longitude else null,
                "locationAccuracy" to if (hasValidCoords) location?.accuracy else null,
                "altitude" to if (hasValidCoords) location?.altitude else null,
                "speed" to if (hasValidCoords) location?.speed else null,
                "bearing" to if (hasValidCoords) location?.bearing else null,
                "isFreshFix" to isFreshNow,
                "locationSource" to if (hasValidCoords) (location?.locationSource ?: "SYSTEM") else "UNAVAILABLE",
                "isGpsAvailable" to isGpsAvailable,
                "timestamp" to locTimestamp,
                "batteryLevel" to safeBattery,
                "isCharging" to (health?.isCharging ?: false),
                "connectivityStatus" to if (network?.isConnected == true) network.networkType else "OFFLINE",
                "isNetworkConnected" to (network?.isConnected ?: true),
                "recentTamperEvents" to recentTamperTitles,
                "locationHistory" to breadcrumbs.map {
                    mapOf(
                        "lat" to it.latitude,
                        "lng" to it.longitude,
                        "accuracy" to it.accuracy,
                        "altitude" to it.altitude,
                        "speed" to it.speed,
                        "bearing" to it.bearing,
                        "batteryLevel" to it.batteryLevel,
                        "isFreshFix" to it.isFreshFix,
                        "provider" to it.provider,
                        "time" to it.timestamp,
                        "inside" to it.isInsideTrustedZone
                    )
                },
                "syncStatus" to "SYNCHRONISÉ",
                "lastSyncedAt" to now,
                "updatedAt" to now,
                "serverUpdatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
            )

            val deviceRef = db.collection("users")
                .document(currentUid)
                .collection("devices")
                .document(activeDeviceId)
            val latestRef = deviceRef.collection("recovery_telemetry").document("latest")

            val existingLatest = try { latestRef.get().await() } catch (_: Exception) { null }
            val existingTs = existingLatest?.getLong("timestamp")
            val existingHasLoc = existingLatest?.let {
                LocationBreadcrumbPolicy.isValidCoordinate(
                    it.getDouble("lastKnownLat") ?: it.getDouble("lat"),
                    it.getDouble("lastKnownLng") ?: it.getDouble("lng")
                )
            } ?: false

            val shouldWriteFullLatest = TelemetrySyncPolicy.shouldOverwriteLatestTelemetry(
                existingTimestampMs = existingTs,
                existingHasLocation = existingHasLoc,
                candidateTimestampMs = locTimestamp,
                candidateHasLocation = hasValidCoords,
                candidateIsFresh = isFreshNow
            )

            val deviceSummaryMap = if (shouldWriteFullLatest) {
                hashMapOf<String, Any?>(
                    "deviceId" to activeDeviceId,
                    "ownerUid" to currentUid,
                    "deviceName" to deviceName,
                    "securityState" to state.name,
                    "hasLocation" to hasValidCoords,
                    "lastKnownLat" to if (hasValidCoords) location?.latitude else null,
                    "lastKnownLng" to if (hasValidCoords) location?.longitude else null,
                    "locationAccuracy" to if (hasValidCoords) location?.accuracy else null,
                    "timestamp" to locTimestamp,
                    "isFreshFix" to isFreshNow,
                    "locationSource" to if (hasValidCoords) (location?.locationSource ?: "SYSTEM") else "UNAVAILABLE",
                    "isGpsAvailable" to isGpsAvailable,
                    "batteryLevel" to safeBattery,
                    "isCharging" to (health?.isCharging ?: false),
                    "connectivityStatus" to if (network?.isConnected == true) network.networkType else "OFFLINE",
                    "isNetworkConnected" to (network?.isConnected ?: true),
                    "lastSyncedAt" to now,
                    "updatedAt" to now,
                    "serverUpdatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
            } else {
                // Keep existing newer or valid coordinates intact; merge live status metadata only
                hashMapOf<String, Any?>(
                    "deviceId" to activeDeviceId,
                    "ownerUid" to currentUid,
                    "deviceName" to deviceName,
                    "securityState" to state.name,
                    "isGpsAvailable" to isGpsAvailable,
                    "batteryLevel" to safeBattery,
                    "isCharging" to (health?.isCharging ?: false),
                    "connectivityStatus" to if (network?.isConnected == true) network.networkType else "OFFLINE",
                    "isNetworkConnected" to (network?.isConnected ?: true),
                    "lastSyncedAt" to now,
                    "updatedAt" to now,
                    "serverUpdatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
            }

            deviceRef.set(deviceSummaryMap, SetOptions.merge()).await()
            if (shouldWriteFullLatest) {
                latestRef.set(firestoreMap).await()
            } else {
                val statusPatch = hashMapOf<String, Any?>(
                    "deviceId" to activeDeviceId,
                    "ownerUid" to currentUid,
                    "deviceName" to deviceName,
                    "securityState" to state.name,
                    "isGpsAvailable" to isGpsAvailable,
                    "batteryLevel" to safeBattery,
                    "isCharging" to (health?.isCharging ?: false),
                    "connectivityStatus" to if (network?.isConnected == true) network.networkType else "OFFLINE",
                    "isNetworkConnected" to (network?.isConnected ?: true),
                    "timestamp" to (existingTs ?: locTimestamp),
                    "syncStatus" to "SYNCHRONISÉ",
                    "lastSyncedAt" to now,
                    "updatedAt" to now,
                    "serverUpdatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
                latestRef.set(statusPatch, SetOptions.merge()).await()
            }

            DeviceSettingsRepository.saveLastSuccessfulSyncTimestamp(context, now)
            _lastSuccessfulSyncAt.value = now
            _syncStatusState.value = "SYNCED"

            val synced = telemetry.copy(syncStatus = "SYNCHRONISÉ", lastSyncedAt = now)
            if (shouldWriteFullLatest) {
                _lastSyncedTelemetry.value = synced
            }
            Log.i(TAG, "Recovery telemetry synchronized for device $activeDeviceId (fullLatest=$shouldWriteFullLatest)")
            synced
        } catch (e: Exception) {
            Log.w(TAG, "Offline or remote sync delayed: ${e.message}")
            _syncStatusState.value = "OFFLINE_QUEUED"
            val buffered = telemetry.copy(syncStatus = "LOCAL_BUFFERED")
            _lastSyncedTelemetry.value = buffered
            LocationSyncWorker.enqueue(context)
            buffered
        }
    }

    /**
     * Real-time Firestore listener for all devices owned by the signed-in account.
     */
    fun observeOwnerDevices(): Flow<List<RemoteTrackedDeviceSummary>> = callbackFlow {
        val currentUid = try {
            authOrNull?.currentUser?.uid
        } catch (_: Exception) {
            null
        }
        val db = firestoreOrNull
        if (currentUid.isNullOrBlank() || db == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val registration = db.collection("users")
            .document(currentUid)
            .collection("devices")
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.w(TAG, "Error observing owner devices: ${error.message}")
                    return@addSnapshotListener
                }
                val devices = snapshots?.documents?.mapNotNull { doc ->
                    val lat = doc.getDouble("lastKnownLat")
                    val lng = doc.getDouble("lastKnownLng")
                    val validCoords = LocationBreadcrumbPolicy.isValidCoordinate(lat, lng)
                    RemoteTrackedDeviceSummary(
                        deviceId = doc.getString("deviceId") ?: doc.id,
                        deviceName = doc.getString("deviceName") ?: (doc.getString("deviceId") ?: doc.id),
                        ownerUid = doc.getString("ownerUid") ?: currentUid,
                        securityState = doc.getString("securityState") ?: "NORMAL",
                        lastKnownLat = if (validCoords) lat else null,
                        lastKnownLng = if (validCoords) lng else null,
                        locationAccuracy = doc.getDouble("locationAccuracy")?.toFloat(),
                        timestamp = doc.getLong("timestamp") ?: 0L,
                        isFreshFix = doc.getBoolean("isFreshFix") ?: false,
                        locationSource = doc.getString("locationSource") ?: "LAST_KNOWN",
                        isGpsAvailable = doc.getBoolean("isGpsAvailable") ?: true,
                        batteryLevel = doc.getLong("batteryLevel")?.toInt() ?: -1,
                        isCharging = doc.getBoolean("isCharging") ?: false,
                        connectivityStatus = doc.getString("connectivityStatus") ?: "UNKNOWN",
                        isNetworkConnected = doc.getBoolean("isNetworkConnected") ?: false,
                        updatedAt = doc.getLong("updatedAt") ?: 0L
                    )
                }?.sortedByDescending { it.updatedAt } ?: emptyList()

                trySend(devices)
            }

        awaitClose { registration.remove() }
    }

    /**
     * Real-time Firestore listener for a specific owned device's latest recovery telemetry.
     */
    fun observeAuthorizedDeviceTelemetry(targetDeviceId: String): Flow<RemoteRecoveryTelemetry?> = callbackFlow {
        val cleanId = targetDeviceId.trim()
        val currentUid = try {
            (authenticatedOwnerUidProvider?.invoke() ?: authOrNull?.currentUser?.uid)?.trim()
        } catch (_: Exception) {
            null
        }
        val db = firestoreOrNull
        if (cleanId.isBlank() || currentUid.isNullOrBlank() || db == null) {
            trySend(null)
            close()
            return@callbackFlow
        }

        val registration = db.collection("users")
            .document(currentUid)
            .collection("devices")
            .document(cleanId)
            .collection("recovery_telemetry")
            .document("latest")
            .addSnapshotListener { doc, error ->
                if (error != null) {
                    Log.w(TAG, "Error observing telemetry for $cleanId: ${error.message}")
                    return@addSnapshotListener
                }
                if (doc == null || !doc.exists()) {
                    trySend(null)
                } else if (!TelemetrySyncPolicy.isMatchingAuthorizedTelemetry(
                        expectedOwnerUid = currentUid,
                        expectedDeviceId = cleanId,
                        documentOwnerUid = doc.getString("ownerUid"),
                        documentDeviceId = doc.getString("deviceId")
                    )
                ) {
                    Log.w(TAG, "Ignored telemetry doc for $cleanId due to ownerUid/deviceId mismatch")
                    trySend(null)
                } else {
                    trySend(parseTelemetryDocument(doc, cleanId, currentUid))
                }
            }

        awaitClose { registration.remove() }
    }

    /**
     * Real-time Firestore listener for timestamped location breadcrumbs in a selected date range.
     */
    fun observeAuthorizedDeviceBreadcrumbs(
        targetDeviceId: String,
        dayStartMs: Long,
        dayEndMs: Long
    ): Flow<List<LocationBreadcrumbPoint>> = callbackFlow {
        val cleanId = targetDeviceId.trim()
        val currentUid = try {
            authOrNull?.currentUser?.uid
        } catch (_: Exception) {
            null
        }
        val db = firestoreOrNull
        if (cleanId.isBlank() || currentUid.isNullOrBlank() || db == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val registration = db.collection("users")
            .document(currentUid)
            .collection("devices")
            .document(cleanId)
            .collection("location_breadcrumbs")
            .whereGreaterThanOrEqualTo("timestamp", dayStartMs)
            .whereLessThanOrEqualTo("timestamp", dayEndMs)
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    Log.w(TAG, "Error observing breadcrumbs for $cleanId: ${error.message}")
                    return@addSnapshotListener
                }
                val points = snapshots?.documents?.mapNotNull { doc ->
                    parseBreadcrumbDoc(doc)
                } ?: emptyList()
                trySend(points)
            }

        awaitClose { registration.remove() }
    }

    /**
     * One-shot fetch for historical breadcrumbs within a date window.
     */
    suspend fun fetchAuthorizedDeviceBreadcrumbsForRange(
        targetDeviceId: String,
        dayStartMs: Long,
        dayEndMs: Long
    ): Result<List<LocationBreadcrumbPoint>> = withContext(Dispatchers.IO) {
        val cleanId = targetDeviceId.trim()
        val currentUid = try {
            authOrNull?.currentUser?.uid
        } catch (_: Exception) {
            null
        } ?: return@withContext Result.failure(IllegalStateException("Authentification requise."))
        val db = firestoreOrNull ?: return@withContext Result.failure(IllegalStateException("Firestore indisponible."))

        try {
            val snap = db.collection("users")
                .document(currentUid)
                .collection("devices")
                .document(cleanId)
                .collection("location_breadcrumbs")
                .whereGreaterThanOrEqualTo("timestamp", dayStartMs)
                .whereLessThanOrEqualTo("timestamp", dayEndMs)
                .orderBy("timestamp", Query.Direction.ASCENDING)
                .get()
                .await()
            val list = snap.documents.mapNotNull { parseBreadcrumbDoc(it) }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Retrieves the latest verified recovery telemetry for an authorized device.
     * Enforces that the request is made under the authenticated owner's UID.
     */
    suspend fun fetchAuthorizedDeviceRecoveryTelemetry(
        targetDeviceId: String
    ): Result<RemoteRecoveryTelemetry> = withContext(Dispatchers.IO) {
        val cleanId = targetDeviceId.trim()
        val currentUid = try {
            (authenticatedOwnerUidProvider?.invoke() ?: authOrNull?.currentUser?.uid)?.trim()
        } catch (_: Exception) {
            null
        } ?: return@withContext Result.failure(IllegalStateException("Authentification requise."))
        if (cleanId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Identifiant d'appareil vide."))
        }
        val db = firestoreOrNull ?: return@withContext Result.failure(IllegalStateException("Firestore indisponible."))

        try {
            val doc = db.collection("users")
                .document(currentUid)
                .collection("devices")
                .document(cleanId)
                .collection("recovery_telemetry")
                .document("latest")
                .get()
                .await()

            if (!doc.exists()) {
                return@withContext Result.failure(
                    NoSuchElementException("Aucune télémétrie vérifiée trouvée pour '$cleanId' sous votre compte.")
                )
            }

            if (!TelemetrySyncPolicy.isMatchingAuthorizedTelemetry(
                    expectedOwnerUid = currentUid,
                    expectedDeviceId = cleanId,
                    documentOwnerUid = doc.getString("ownerUid"),
                    documentDeviceId = doc.getString("deviceId")
                )
            ) {
                return@withContext Result.failure(
                    SecurityException("Autorisation refusée : incohérence ownerUid ou deviceId pour '$cleanId'.")
                )
            }

            Result.success(parseTelemetryDocument(doc, cleanId, currentUid))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseTelemetryDocument(
        doc: DocumentSnapshot,
        targetDeviceId: String,
        currentUid: String
    ): RemoteRecoveryTelemetry {
        val rawHistory = doc.get("locationHistory") as? List<*>
        val parsedHistory = rawHistory?.mapNotNull { item ->
            (item as? Map<*, *>)?.let { map ->
                val lat = (map["lat"] as? Number)?.toDouble() ?: return@let null
                val lng = (map["lng"] as? Number)?.toDouble() ?: return@let null
                if (!LocationBreadcrumbPolicy.isValidCoordinate(lat, lng)) return@let null
                val acc = (map["accuracy"] as? Number)?.toFloat() ?: 0f
                val alt = (map["altitude"] as? Number)?.toDouble() ?: 0.0
                val spd = (map["speed"] as? Number)?.toFloat() ?: 0f
                val brg = (map["bearing"] as? Number)?.toFloat() ?: 0f
                val bat = (map["batteryLevel"] as? Number)?.toInt() ?: -1
                val fresh = (map["isFreshFix"] as? Boolean) ?: false
                val prov = (map["provider"] as? String) ?: "gps"
                val tm = (map["time"] as? Number)?.toLong() ?: 0L
                val ins = (map["inside"] as? Boolean) ?: true
                LocationBreadcrumbPoint(
                    latitude = lat,
                    longitude = lng,
                    accuracy = acc,
                    timestamp = tm,
                    isInsideTrustedZone = ins,
                    altitude = alt,
                    speed = spd,
                    bearing = brg,
                    batteryLevel = bat,
                    isFreshFix = fresh,
                    provider = prov
                )
            }
        } ?: emptyList()

        val lat = doc.getDouble("lastKnownLat") ?: doc.getDouble("lat")
        val lng = doc.getDouble("lastKnownLng") ?: doc.getDouble("lng")
        val hasValidCoords = LocationBreadcrumbPolicy.isValidCoordinate(lat, lng)

        return RemoteRecoveryTelemetry(
            deviceId = doc.getString("deviceId") ?: targetDeviceId,
            ownerUid = currentUid,
            securityState = doc.getString("securityState") ?: doc.getString("status") ?: "NORMAL",
            lastKnownLat = if (hasValidCoords) lat else null,
            lastKnownLng = if (hasValidCoords) lng else null,
            locationAccuracy = if (hasValidCoords) (doc.getDouble("locationAccuracy") ?: doc.getDouble("accuracy"))?.toFloat() else null,
            timestamp = doc.getLong("timestamp") ?: doc.getLong("time") ?: 0L,
            batteryLevel = doc.getLong("batteryLevel")?.toInt()
                ?: doc.getString("battery")?.replace("%", "")?.trim()?.toIntOrNull()
                ?: 80,
            isCharging = doc.getBoolean("isCharging") ?: false,
            connectivityStatus = doc.getString("connectivityStatus") ?: "ONLINE",
            isNetworkConnected = doc.getBoolean("isNetworkConnected") ?: true,
            recentTamperEvents = (doc.get("recentTamperEvents") as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
            syncStatus = doc.getString("syncStatus") ?: "SYNCHRONISÉ",
            locationHistory = parsedHistory,
            deviceName = doc.getString("deviceName") ?: targetDeviceId,
            altitude = if (hasValidCoords) doc.getDouble("altitude") else null,
            speed = if (hasValidCoords) doc.getDouble("speed")?.toFloat() else null,
            bearing = if (hasValidCoords) doc.getDouble("bearing")?.toFloat() else null,
            isFreshFix = if (hasValidCoords) (doc.getBoolean("isFreshFix") ?: false) else false,
            locationSource = doc.getString("locationSource") ?: if (hasValidCoords) "LAST_KNOWN" else "UNAVAILABLE",
            isGpsAvailable = doc.getBoolean("isGpsAvailable") ?: true,
            lastSyncedAt = doc.getLong("lastSyncedAt") ?: doc.getLong("updatedAt")
        )
    }

    private fun parseBreadcrumbDoc(doc: DocumentSnapshot): LocationBreadcrumbPoint? {
        val lat = doc.getDouble("latitude") ?: doc.getDouble("lat") ?: return null
        val lng = doc.getDouble("longitude") ?: doc.getDouble("lng") ?: return null
        if (!LocationBreadcrumbPolicy.isValidCoordinate(lat, lng)) return null
        return LocationBreadcrumbPoint(
            latitude = lat,
            longitude = lng,
            accuracy = doc.getDouble("accuracy")?.toFloat() ?: 0f,
            timestamp = doc.getLong("timestamp") ?: 0L,
            isInsideTrustedZone = doc.getBoolean("isInsideTrustedZone") ?: true,
            altitude = doc.getDouble("altitude") ?: 0.0,
            speed = doc.getDouble("speed")?.toFloat() ?: 0f,
            bearing = doc.getDouble("bearing")?.toFloat() ?: 0f,
            batteryLevel = doc.getLong("batteryLevel")?.toInt() ?: -1,
            isFreshFix = doc.getBoolean("isFreshFix") ?: false,
            provider = doc.getString("provider") ?: "gps"
        )
    }

    /**
     * Synchronizes accumulated offline location breadcrumbs in batches to Firestore
     * under users/{ownerUid}/devices/{deviceId}/location_breadcrumbs/{docId}
     * and marks them as synced in the local Room database.
     */
    suspend fun syncOfflineBreadcrumbs(): Int = withContext(Dispatchers.IO) {
        val currentUid = try {
            authOrNull?.currentUser?.uid
        } catch (_: Exception) {
            null
        }
        val db = firestoreOrNull
        if (currentUid.isNullOrBlank() || locationDao == null || db == null) {
            if (locationDao != null) {
                LocationSyncWorker.enqueue(context)
            }
            return@withContext 0
        }

        val unsynced = try {
            locationDao.getUnsyncedLocations()
        } catch (e: Exception) {
            Log.w(TAG, "Error fetching unsynced locations: ${e.message}")
            emptyList()
        }.filter { LocationBreadcrumbPolicy.isValidCoordinate(it.latitude, it.longitude) }

        if (unsynced.isEmpty()) return@withContext 0

        val activeDeviceId = DeviceSettingsRepository.getDeviceId(context).ifBlank { deviceId }
        val breadcrumbsCol = db.collection("users")
            .document(currentUid)
            .collection("devices")
            .document(activeDeviceId)
            .collection("location_breadcrumbs")

        var totalSynced = 0
        for (chunk in unsynced.chunked(25)) {
            try {
                val batch = db.batch()
                for (loc in chunk) {
                    val docId = "bc_${loc.timestamp}_${loc.id}"
                    val payload = buildBreadcrumbFirestoreMap(docId, activeDeviceId, currentUid, loc)
                    batch.set(breadcrumbsCol.document(docId), payload, SetOptions.merge())
                }
                batch.commit().await()
                locationDao.markSynced(chunk.map { it.id })
                totalSynced += chunk.size
            } catch (e: Exception) {
                Log.w(TAG, "Could not sync location batch to Firestore: ${e.message}")
                _syncStatusState.value = "OFFLINE_QUEUED"
                LocationSyncWorker.enqueue(context)
                break
            }
        }

        if (totalSynced > 0) {
            val now = System.currentTimeMillis()
            DeviceSettingsRepository.saveLastSuccessfulSyncTimestamp(context, now)
            _lastSuccessfulSyncAt.value = now
            _syncStatusState.value = "SYNCED"
        }
        totalSynced
    }

    private fun buildBreadcrumbFirestoreMap(
        docId: String,
        activeDeviceId: String,
        ownerUid: String,
        loc: LocationHistoryEntity
    ): Map<String, Any> = mapOf(
        "id" to docId,
        "deviceId" to activeDeviceId,
        "ownerUid" to ownerUid,
        "latitude" to loc.latitude,
        "longitude" to loc.longitude,
        "accuracy" to loc.accuracy,
        "altitude" to loc.altitude,
        "speed" to loc.speed,
        "bearing" to loc.bearing,
        "batteryLevel" to loc.batteryLevel,
        "timestamp" to loc.timestamp,
        "isFreshFix" to loc.isFreshFix,
        "provider" to loc.provider,
        "isInsideTrustedZone" to loc.isInsideTrustedZone,
        "dateKey" to LocationSyncWorker.formatUtcDateKey(loc.timestamp)
    )

    /**
     * Starts listening for remote owner recovery commands on:
     * users/{ownerUid}/devices/{deviceId}/commands
     */
    fun startRemoteCommandListener() {
        val currentUid = try {
            authOrNull?.currentUser?.uid ?: return
        } catch (_: Exception) {
            return
        }
        val db = firestoreOrNull ?: return
        val activeDeviceId = DeviceSettingsRepository.getDeviceId(context).ifBlank { deviceId }
        stopRemoteCommandListener()

        try {
            commandListenerRegistration = db.collection("users")
                .document(currentUid)
                .collection("devices")
                .document(activeDeviceId)
                .collection("commands")
                .whereEqualTo("status", CommandStatus.PENDING.name)
                .addSnapshotListener { snapshots, error ->
                    if (error != null) {
                        Log.w(TAG, "Remote command listener error: ${error.message}")
                        return@addSnapshotListener
                    }

                    if (snapshots != null && !snapshots.isEmpty) {
                        for (doc in snapshots.documents) {
                            scope.launch {
                                processIncomingRemoteCommand(doc, currentUid, activeDeviceId)
                            }
                        }
                    }
                }
            Log.i(TAG, "Active remote command listener registered for device $activeDeviceId")
        } catch (e: Exception) {
            Log.w(TAG, "Could not register Firestore command listener: ${e.message}")
        }
    }

    fun stopRemoteCommandListener() {
        try {
            commandListenerRegistration?.remove()
            commandListenerRegistration = null
        } catch (_: Exception) {}
    }

    private suspend fun processIncomingRemoteCommand(
        doc: DocumentSnapshot,
        ownerUid: String,
        activeDeviceId: String
    ) {
        val envelope = RemoteCommandEnvelope(
            documentId = doc.id,
            fieldId = doc.getString("id"),
            pathOwnerUid = ownerUid,
            documentOwnerUid = doc.getString("ownerUid"),
            issuedBy = doc.getString("issuedBy"),
            targetDeviceId = doc.getString("deviceId"),
            commandTypeRaw = doc.getString("commandType"),
            statusRaw = doc.getString("status"),
            createdTimestamp = doc.getLong("timestamp"),
            expirationTimestamp = doc.getLong("expirationTimestamp"),
            payload = doc.get("payload"),
            ownerConfirmed = doc.getBoolean("ownerConfirmed")
        )
        processRemoteCommandEnvelope(envelope)
    }

    internal suspend fun processRemoteCommandEnvelope(
        envelope: RemoteCommandEnvelope
    ): SecurityCommandProcessor.CommandExecutionResult = remoteCommandMutex.withLock {
        val cleanPathOwnerUid = envelope.pathOwnerUid.trim()
        val cmdId = envelope.documentId.trim()
        val resolvedDeviceId = (activeDeviceIdProvider?.invoke()
            ?: DeviceSettingsRepository.getDeviceId(context).ifBlank { deviceId }).trim()
        val signedInUid = try {
            (authenticatedOwnerUidProvider?.invoke() ?: authOrNull?.currentUser?.uid)?.trim()
        } catch (_: Exception) {
            null
        }

        // 1. Validate Document ID & optional field ID consistency
        if (cmdId.isBlank() || cmdId.length > 128 ||
            (!envelope.fieldId.isNullOrBlank() && envelope.fieldId.trim() != cmdId)
        ) {
            if (cmdId.isNotBlank() && cleanPathOwnerUid.isNotBlank()) {
                dispatchRemoteStatusUpdate(
                    cleanPathOwnerUid,
                    resolvedDeviceId,
                    cmdId,
                    CommandStatus.REJECTED,
                    "COMMAND_ID_MISMATCH"
                )
            }
            return@withLock SecurityCommandProcessor.CommandExecutionResult.Rejected(
                "Remote command rejected: command document ID mismatch."
            )
        }

        // 2. Fail closed if unauthenticated or path owner does not match signed-in Firebase user
        if (signedInUid.isNullOrBlank() || cleanPathOwnerUid.isBlank() || signedInUid != cleanPathOwnerUid) {
            Log.w(TAG, "Rejected remote command $cmdId: authenticated owner mismatch")
            if (cleanPathOwnerUid.isNotBlank()) {
                dispatchRemoteStatusUpdate(
                    cleanPathOwnerUid,
                    resolvedDeviceId,
                    cmdId,
                    CommandStatus.REJECTED,
                    "UNAUTHENTICATED_OR_OWNER_MISMATCH"
                )
            }
            return@withLock SecurityCommandProcessor.CommandExecutionResult.Rejected(
                "Remote command rejected: authenticated owner mismatch."
            )
        }

        // 3. Only process PENDING commands (skip already-processed remote docs safely)
        val cleanStatus = envelope.statusRaw?.trim()
        if (!cleanStatus.isNullOrBlank() && cleanStatus != CommandStatus.PENDING.name) {
            val remoteStatus = try {
                CommandStatus.valueOf(cleanStatus)
            } catch (_: Exception) {
                CommandStatus.REJECTED
            }
            return@withLock SecurityCommandProcessor.CommandExecutionResult.AlreadyProcessed(
                status = remoteStatus,
                message = "Remote command '$cmdId' already has status ${remoteStatus.name}."
            )
        }

        // 4. Anti-replay & idempotency check in local Room command log before inserting/executing
        val existingLocal = try {
            commandDao.getAllCommands().firstOrNull()?.firstOrNull { it.commandId == cmdId }
        } catch (_: Exception) {
            null
        }
        if (existingLocal != null && existingLocal.status != CommandStatus.PENDING.name) {
            val existingDomain = existingLocal.toDomain()
            val targetMismatch = !envelope.targetDeviceId.isNullOrBlank() &&
                envelope.targetDeviceId.trim() != existingDomain.deviceId
            val typeMismatch = !envelope.commandTypeRaw.isNullOrBlank() &&
                envelope.commandTypeRaw.trim() != existingDomain.commandType.name
            val issuerMismatch = !envelope.issuedBy.isNullOrBlank() &&
                envelope.issuedBy.trim() != existingDomain.ownerId

            if (targetMismatch || typeMismatch || issuerMismatch) {
                return@withLock SecurityCommandProcessor.CommandExecutionResult.Rejected(
                    "Remote command rejected: replay attempt with altered parameters for '$cmdId'."
                )
            }
            dispatchRemoteStatusUpdate(
                cleanPathOwnerUid,
                resolvedDeviceId,
                cmdId,
                existingDomain.status,
                "ALREADY_PROCESSED_${existingDomain.status.name}"
            )
            return@withLock SecurityCommandProcessor.CommandExecutionResult.AlreadyProcessed(
                status = existingDomain.status,
                message = "Command '$cmdId' already processed locally with status ${existingDomain.status.name}."
            )
        }

        // 5. Strict owner & issuer authorization
        if (envelope.documentOwnerUid != null && envelope.documentOwnerUid.trim() != signedInUid) {
            dispatchRemoteStatusUpdate(
                cleanPathOwnerUid,
                resolvedDeviceId,
                cmdId,
                CommandStatus.REJECTED,
                "DOCUMENT_OWNER_MISMATCH"
            )
            return@withLock SecurityCommandProcessor.CommandExecutionResult.Rejected(
                "Remote command rejected: document ownerUid mismatch."
            )
        }
        val issuedBy = envelope.issuedBy?.trim().orEmpty()
        if (issuedBy.isBlank() || issuedBy != signedInUid) {
            Log.w(TAG, "Rejected remote command $cmdId: issuer authorization failed")
            dispatchRemoteStatusUpdate(
                cleanPathOwnerUid,
                resolvedDeviceId,
                cmdId,
                CommandStatus.REJECTED,
                "ISSUER_AUTHORIZATION_FAILED"
            )
            return@withLock SecurityCommandProcessor.CommandExecutionResult.Rejected(
                "Remote command rejected: issuer does not match authenticated owner."
            )
        }

        // 6. Validate Target Device
        val targetDeviceId = envelope.targetDeviceId?.trim().orEmpty()
        if (targetDeviceId.isBlank() || targetDeviceId != resolvedDeviceId) {
            Log.w(TAG, "Rejected remote command $cmdId: device mismatch")
            dispatchRemoteStatusUpdate(
                cleanPathOwnerUid,
                resolvedDeviceId,
                cmdId,
                CommandStatus.REJECTED,
                "TARGET_DEVICE_MISMATCH"
            )
            return@withLock SecurityCommandProcessor.CommandExecutionResult.Rejected(
                "Remote command rejected: target device mismatch."
            )
        }

        // 7. Validate Payload type & safety bounds
        val payloadStr = when (val rawPayload = envelope.payload) {
            null -> ""
            is String -> rawPayload
            else -> {
                dispatchRemoteStatusUpdate(
                    cleanPathOwnerUid,
                    resolvedDeviceId,
                    cmdId,
                    CommandStatus.REJECTED,
                    "INVALID_PAYLOAD_TYPE"
                )
                return@withLock SecurityCommandProcessor.CommandExecutionResult.Rejected(
                    "Remote command rejected: invalid payload type."
                )
            }
        }
        if (payloadStr.length > MAX_PAYLOAD_LENGTH ||
            payloadStr.any { ch -> ch.code < 32 && ch != '\n' && ch != '\r' && ch != '\t' }
        ) {
            dispatchRemoteStatusUpdate(
                cleanPathOwnerUid,
                resolvedDeviceId,
                cmdId,
                CommandStatus.REJECTED,
                "INVALID_PAYLOAD"
            )
            return@withLock SecurityCommandProcessor.CommandExecutionResult.Rejected(
                "Remote command rejected: payload failed bounds validation."
            )
        }

        // 8. Validate Timestamps & Expiration Window
        val now = clockProvider()
        val createdTs = envelope.createdTimestamp
        val expirationTs = envelope.expirationTimestamp
        if (createdTs == null || createdTs <= 0L || expirationTs == null || expirationTs <= createdTs) {
            dispatchRemoteStatusUpdate(
                cleanPathOwnerUid,
                resolvedDeviceId,
                cmdId,
                CommandStatus.REJECTED,
                "INVALID_TIMESTAMPS"
            )
            return@withLock SecurityCommandProcessor.CommandExecutionResult.Rejected(
                "Remote command rejected: invalid timestamps."
            )
        }
        if (createdTs - now > MAX_FUTURE_CLOCK_SKEW_MS) {
            dispatchRemoteStatusUpdate(
                cleanPathOwnerUid,
                resolvedDeviceId,
                cmdId,
                CommandStatus.REJECTED,
                "FUTURE_TIMESTAMP_INVALID"
            )
            return@withLock SecurityCommandProcessor.CommandExecutionResult.Rejected(
                "Remote command rejected: timestamp exceeds allowed clock skew."
            )
        }
        if (now > expirationTs ||
            (now - createdTs) > COMMAND_EXPIRATION_WINDOW_MS ||
            (expirationTs - createdTs) > COMMAND_EXPIRATION_WINDOW_MS
        ) {
            Log.w(TAG, "Rejected remote command $cmdId: command expired")
            dispatchRemoteStatusUpdate(
                cleanPathOwnerUid,
                resolvedDeviceId,
                cmdId,
                CommandStatus.EXPIRED,
                "EXPIRED_WINDOW"
            )
            return@withLock SecurityCommandProcessor.CommandExecutionResult.Rejected(
                "Remote command rejected: authorization window expired."
            )
        }

        // 9. Validate Command Type
        val commandType = try {
            CommandType.valueOf(envelope.commandTypeRaw?.trim().orEmpty())
        } catch (_: Exception) {
            Log.w(TAG, "Unknown command type: ${envelope.commandTypeRaw}")
            dispatchRemoteStatusUpdate(
                cleanPathOwnerUid,
                resolvedDeviceId,
                cmdId,
                CommandStatus.UNSUPPORTED_ON_DEVICE,
                "UNKNOWN_TYPE"
            )
            return@withLock SecurityCommandProcessor.CommandExecutionResult.Unsupported(
                "Unknown command type: ${envelope.commandTypeRaw}"
            )
        }

        // 10. Validate explicit owner confirmation when flagged on sensitive actions
        if ((commandType == CommandType.ACTIVATE_STOLEN_MODE || commandType == CommandType.MARK_RECOVERED) &&
            envelope.ownerConfirmed == false
        ) {
            dispatchRemoteStatusUpdate(
                cleanPathOwnerUid,
                resolvedDeviceId,
                cmdId,
                CommandStatus.REJECTED,
                "OWNER_CONFIRMATION_MISSING"
            )
            return@withLock SecurityCommandProcessor.CommandExecutionResult.Rejected(
                "Remote command rejected: owner confirmation flag is false."
            )
        }

        val domainCommand = SecurityCommand(
            commandId = cmdId,
            deviceId = targetDeviceId,
            ownerId = issuedBy,
            commandType = commandType,
            createdTimestamp = createdTs,
            expirationTimestamp = expirationTs,
            status = CommandStatus.PENDING,
            authorizationResult = "REMOTE_AUTHORIZED_BY_$issuedBy",
            payload = payloadStr
        )

        val executionResult = commandProcessor.executeCommand(domainCommand, authorizedBy = issuedBy)

        val finalStatus = when (executionResult) {
            is SecurityCommandProcessor.CommandExecutionResult.Success -> CommandStatus.EXECUTED
            is SecurityCommandProcessor.CommandExecutionResult.PendingServiceActivation -> CommandStatus.PENDING_SERVICE_ACTIVATION
            is SecurityCommandProcessor.CommandExecutionResult.Failed -> CommandStatus.FAILED
            is SecurityCommandProcessor.CommandExecutionResult.Rejected -> CommandStatus.REJECTED
            is SecurityCommandProcessor.CommandExecutionResult.Unsupported -> CommandStatus.UNSUPPORTED_ON_DEVICE
            is SecurityCommandProcessor.CommandExecutionResult.AlreadyProcessed -> executionResult.status
        }

        val resultNote = when (executionResult) {
            is SecurityCommandProcessor.CommandExecutionResult.Success -> "EXECUTED: ${executionResult.message}"
            is SecurityCommandProcessor.CommandExecutionResult.PendingServiceActivation -> "PENDING_SERVICE_ACTIVATION: ${executionResult.message}"
            is SecurityCommandProcessor.CommandExecutionResult.Failed -> "FAILED: ${executionResult.reason}"
            is SecurityCommandProcessor.CommandExecutionResult.Rejected -> "REJECTED: ${executionResult.reason}"
            is SecurityCommandProcessor.CommandExecutionResult.Unsupported -> "UNSUPPORTED: ${executionResult.reason}"
            is SecurityCommandProcessor.CommandExecutionResult.AlreadyProcessed -> "ALREADY_PROCESSED(${executionResult.status.name}): ${executionResult.message}"
        }

        dispatchRemoteStatusUpdate(cleanPathOwnerUid, resolvedDeviceId, cmdId, finalStatus, resultNote)
        executionResult
    }

    private suspend fun dispatchRemoteStatusUpdate(
        ownerUid: String,
        activeDeviceId: String,
        commandId: String,
        status: CommandStatus,
        resultNote: String
    ) {
        if (remoteStatusUpdater != null) {
            try {
                remoteStatusUpdater.invoke(ownerUid, activeDeviceId, commandId, status, resultNote)
            } catch (e: Exception) {
                Log.w(TAG, "Custom remote status updater failed: ${e.message}")
            }
            return
        }
        updateRemoteCommandStatus(ownerUid, activeDeviceId, commandId, status, resultNote)
    }

    private suspend fun updateRemoteCommandStatus(
        ownerUid: String,
        activeDeviceId: String,
        commandId: String,
        status: CommandStatus,
        resultNote: String
    ) {
        val db = firestoreOrNull ?: return
        try {
            db.collection("users")
                .document(ownerUid)
                .collection("devices")
                .document(activeDeviceId)
                .collection("commands")
                .document(commandId)
                .update(
                    mapOf(
                        "status" to status.name,
                        "executionResult" to resultNote,
                        "executedAt" to clockProvider()
                    )
                )
                .await()
        } catch (e: Exception) {
            Log.w(TAG, "Could not update remote command status in Firestore: ${e.message}")
        }
    }

    suspend fun dispatchRemoteCommandToDevice(
        targetDeviceId: String,
        commandType: CommandType,
        payload: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val currentUid = try {
            authOrNull?.currentUser?.uid
        } catch (_: Exception) {
            null
        } ?: return@withContext Result.failure(IllegalStateException("Authentification requise pour commander l'appareil à distance."))
        val db = firestoreOrNull ?: return@withContext Result.failure(IllegalStateException("Firestore indisponible."))

        val commandId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val expiration = now + COMMAND_EXPIRATION_WINDOW_MS

        val commandDoc = mapOf(
            "id" to commandId,
            "commandType" to commandType.name,
            "deviceId" to targetDeviceId,
            "issuedBy" to currentUid,
            "timestamp" to now,
            "expirationTimestamp" to expiration,
            "status" to CommandStatus.PENDING.name,
            "payload" to (payload ?: "")
        )

        try {
            db.collection("users")
                .document(currentUid)
                .collection("devices")
                .document(targetDeviceId)
                .collection("commands")
                .document(commandId)
                .set(commandDoc)
                .await()

            Result.success("Commande distante '${commandType.name}' envoyée avec succès (Validité 10 min).")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
