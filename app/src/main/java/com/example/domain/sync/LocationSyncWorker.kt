package com.example.domain.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.local.InkingiDatabase
import com.example.data.local.entity.LocationHistoryEntity
import com.example.data.repository.DeviceSettingsRepository
import com.example.domain.location.LocationBreadcrumbPolicy
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Reliable background WorkManager worker for deferred offline GPS breadcrumb synchronization.
 * Automatically executes when network connectivity returns (NetworkType.CONNECTED).
 */
class LocationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "LocationSyncWorker"
        const val UNIQUE_WORK_NAME = "inkingi_location_breadcrumb_sync"

        fun enqueue(context: Context): Boolean {
            return try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = OneTimeWorkRequestBuilder<LocationSyncWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15L, TimeUnit.SECONDS)
                    .build()

                WorkManager.getInstance(context.applicationContext)
                    .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Unable to enqueue LocationSyncWorker: ${e.message}")
                false
            }
        }

        fun formatUtcDateKey(timestampMs: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            return sdf.format(Date(timestampMs))
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val isFirebaseReady = try {
            FirebaseApp.getApps(ctx).isNotEmpty()
        } catch (_: Exception) {
            false
        }
        if (!isFirebaseReady) {
            Log.w(TAG, "Firebase not initialized; skipping sync.")
            return Result.success()
        }

        val ownerUid = try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Exception) {
            null
        }
        if (ownerUid.isNullOrBlank()) {
            Log.d(TAG, "No authenticated owner session; deferring sync.")
            return Result.success()
        }

        val db = InkingiDatabase.getInstance(ctx)
        val locationDao = db.locationDao()
        val unsynced = try {
            locationDao.getUnsyncedLocations()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query unsynced locations: ${e.message}")
            return if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        val validPoints = unsynced.filter {
            LocationBreadcrumbPolicy.isValidCoordinate(it.latitude, it.longitude)
        }

        val firestore = try {
            FirebaseFirestore.getInstance()
        } catch (e: Exception) {
            Log.w(TAG, "Firestore unavailable: ${e.message}")
            return if (runAttemptCount < 5) Result.retry() else Result.failure()
        }

        val deviceId = DeviceSettingsRepository.getDeviceId(ctx)
        val deviceName = DeviceSettingsRepository.getDeviceName(ctx)
        if (deviceId.isBlank() || ownerUid.isBlank()) {
            return Result.success()
        }
        val deviceDocRef = firestore.collection("users")
            .document(ownerUid)
            .collection("devices")
            .document(deviceId)
        val breadcrumbsCol = deviceDocRef.collection("location_breadcrumbs")

        var syncedCount = 0
        for (chunk in validPoints.chunked(25)) {
            try {
                val batch = firestore.batch()
                for (loc in chunk) {
                    val docId = breadcrumbDocId(loc)
                    val payload = mapOf(
                        "id" to docId,
                        "deviceId" to deviceId,
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
                        "dateKey" to formatUtcDateKey(loc.timestamp)
                    )
                    batch.set(breadcrumbsCol.document(docId), payload, SetOptions.merge())
                }
                batch.commit().await()
                locationDao.markSynced(chunk.map { it.id })
                syncedCount += chunk.size
            } catch (e: Exception) {
                Log.w(TAG, "Deferred breadcrumb batch sync failed: ${e.message}")
                return if (runAttemptCount < 5) Result.retry() else Result.failure()
            }
        }

        val newestCandidate = validPoints.maxByOrNull { it.timestamp }
            ?: try {
                locationDao.getLatestBreadcrumb()?.takeIf {
                    LocationBreadcrumbPolicy.isValidCoordinate(it.latitude, it.longitude)
                }
            } catch (_: Exception) {
                null
            }

        val now = System.currentTimeMillis()
        val latestRef = deviceDocRef.collection("recovery_telemetry").document("latest")
        val existingLatest = try {
            latestRef.get().await()
        } catch (_: Exception) {
            null
        }
        val existingTs = existingLatest?.getLong("timestamp")
        val existingHasLoc = existingLatest?.let {
            LocationBreadcrumbPolicy.isValidCoordinate(
                it.getDouble("lastKnownLat") ?: it.getDouble("lat"),
                it.getDouble("lastKnownLng") ?: it.getDouble("lng")
            )
        } ?: false

        val candidateHasLoc = newestCandidate != null &&
            LocationBreadcrumbPolicy.isValidCoordinate(newestCandidate.latitude, newestCandidate.longitude)
        val candidateTs = newestCandidate?.timestamp ?: now
        val isFreshNow = newestCandidate != null &&
            !LocationBreadcrumbPolicy.isLocationStale(newestCandidate.timestamp, newestCandidate.isFreshFix, now)

        val shouldPromoteLatest = com.example.domain.recovery.TelemetrySyncPolicy.shouldOverwriteLatestTelemetry(
            existingTimestampMs = existingTs,
            existingHasLocation = existingHasLoc,
            candidateTimestampMs = candidateTs,
            candidateHasLocation = candidateHasLoc,
            candidateIsFresh = isFreshNow
        )

        if (shouldPromoteLatest || existingLatest == null || !existingLatest.exists()) {
            try {
                val safeBattery = readSafeBatteryLevel(ctx, newestCandidate?.batteryLevel ?: -1)
                val isCharging = readIsCharging(ctx)
                val securityState = DeviceSettingsRepository.getPersistedSecurityStateName(ctx) ?: "NORMAL"
                val locSource = when {
                    !candidateHasLoc -> "UNAVAILABLE"
                    isFreshNow -> when {
                        newestCandidate?.provider?.contains("fused", ignoreCase = true) == true -> "FUSED_LIVE"
                        newestCandidate?.provider?.contains("network", ignoreCase = true) == true -> "NETWORK_LIVE"
                        else -> "GPS_LIVE"
                    }
                    else -> "OFFLINE_BUFFERED"
                }

                val summaryPayload = hashMapOf<String, Any?>(
                    "deviceId" to deviceId,
                    "ownerUid" to ownerUid,
                    "deviceName" to deviceName,
                    "securityState" to securityState,
                    "hasLocation" to candidateHasLoc,
                    "lastKnownLat" to newestCandidate?.latitude,
                    "lastKnownLng" to newestCandidate?.longitude,
                    "locationAccuracy" to newestCandidate?.accuracy,
                    "timestamp" to candidateTs,
                    "isFreshFix" to isFreshNow,
                    "locationSource" to locSource,
                    "isGpsAvailable" to isLocationProviderAvailable(ctx),
                    "batteryLevel" to safeBattery,
                    "isCharging" to isCharging,
                    "connectivityStatus" to "ONLINE",
                    "isNetworkConnected" to true,
                    "lastSyncedAt" to now,
                    "updatedAt" to now,
                    "syncStatus" to "SYNCHRONISÉ",
                    "serverUpdatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
                deviceDocRef.set(summaryPayload, SetOptions.merge()).await()

                val telemetryPayload = hashMapOf<String, Any?>(
                    "deviceId" to deviceId,
                    "ownerUid" to ownerUid,
                    "deviceName" to deviceName,
                    "securityState" to securityState,
                    "status" to securityState,
                    "hasLocation" to candidateHasLoc,
                    "lastKnownLat" to newestCandidate?.latitude,
                    "lastKnownLng" to newestCandidate?.longitude,
                    "locationAccuracy" to newestCandidate?.accuracy,
                    "altitude" to newestCandidate?.altitude,
                    "speed" to newestCandidate?.speed,
                    "bearing" to newestCandidate?.bearing,
                    "isFreshFix" to isFreshNow,
                    "locationSource" to locSource,
                    "isGpsAvailable" to isLocationProviderAvailable(ctx),
                    "timestamp" to candidateTs,
                    "batteryLevel" to safeBattery,
                    "battery" to "$safeBattery%",
                    "isCharging" to isCharging,
                    "connectivityStatus" to "ONLINE",
                    "isNetworkConnected" to true,
                    "syncStatus" to "SYNCHRONISÉ",
                    "lastSyncedAt" to now,
                    "updatedAt" to now,
                    "serverUpdatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                )
                latestRef.set(telemetryPayload).await()
                DeviceSettingsRepository.saveLastSuccessfulSyncTimestamp(ctx, now)
            } catch (e: Exception) {
                Log.w(TAG, "Latest recovery_telemetry sync failed: ${e.message}")
                return if (runAttemptCount < 5) Result.retry() else Result.failure()
            }
        } else if (syncedCount > 0) {
            DeviceSettingsRepository.saveLastSuccessfulSyncTimestamp(ctx, now)
            try {
                deviceDocRef.set(
                    mapOf(
                        "deviceId" to deviceId,
                        "ownerUid" to ownerUid,
                        "lastSyncedAt" to now,
                        "updatedAt" to now,
                        "syncStatus" to "SYNCHRONISÉ"
                    ),
                    SetOptions.merge()
                ).await()
            } catch (_: Exception) {}
        }

        if (syncedCount > 0) {
            Log.i(TAG, "Synchronized $syncedCount buffered location breadcrumbs for device $deviceId.")
        }

        return Result.success()
    }

    private fun readSafeBatteryLevel(ctx: Context, recordedBattery: Int): Int {
        if (recordedBattery in 0..100) return recordedBattery
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager
            bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 } ?: 80
        } catch (_: Exception) {
            80
        }
    }

    private fun readIsCharging(ctx: Context): Boolean {
        return try {
            val intent = ctx.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val status = intent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                status == android.os.BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Exception) {
            false
        }
    }

    private fun isLocationProviderAvailable(ctx: Context): Boolean {
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? android.location.LocationManager
            (lm?.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) == true) ||
                (lm?.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER) == true)
        } catch (_: Exception) {
            false
        }
    }

    private fun breadcrumbDocId(loc: LocationHistoryEntity): String {
        return "bc_${loc.timestamp}_${loc.id}"
    }
}
