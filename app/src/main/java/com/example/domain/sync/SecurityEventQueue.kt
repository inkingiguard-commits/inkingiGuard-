package com.example.domain.sync

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.core.model.QueueItem
import com.example.core.model.QueuePriority
import com.example.core.model.SyncStatus
import com.example.data.local.dao.QueueItemDao
import com.example.data.local.entity.QueueItemEntity
import com.example.data.repository.DeviceSettingsRepository
import com.example.domain.security.OfflineSecurityVault
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Explicit, non-deceptive backend transmission outcomes for queued security events.
 */
sealed class BackendTransmissionOutcome {
    data class SentToBackend(val backendReference: String? = null) : BackendTransmissionOutcome()
    data class DispatchAccepted(val dispatchReference: String? = null) : BackendTransmissionOutcome()
    data class DeliveryConfirmed(val confirmationReference: String? = null) : BackendTransmissionOutcome()
    data class AlreadyProcessed(val existingStatus: SyncStatus) : BackendTransmissionOutcome()
    data class NotConfigured(val reason: String) : BackendTransmissionOutcome()
    data class AuthRequired(val reason: String) : BackendTransmissionOutcome()
    data class TransientFailure(val reason: String) : BackendTransmissionOutcome()
    data class PermanentFailure(val reason: String) : BackendTransmissionOutcome()
}

data class QueueStatusSummary(
    val totalCount: Int = 0,
    val localQueuedCount: Int = 0,
    val pendingCount: Int = 0,
    val syncingCount: Int = 0,
    val sentToBackendCount: Int = 0,
    val dispatchAcceptedCount: Int = 0,
    val deliveryConfirmedCount: Int = 0,
    val failedCount: Int = 0,
    val notConfiguredCount: Int = 0,
    val lastErrorMessage: String? = null,
    val lastSyncAttemptAt: Long? = null
) {
    val actionablePendingCount: Int
        get() = localQueuedCount + pendingCount + syncingCount + failedCount + notConfiguredCount
}

data class EnqueueResult(
    val eventId: String,
    val isDuplicateSuppressed: Boolean,
    val status: SyncStatus
)

/**
 * Gateway responsible for transmitting general security events to an authenticated backend.
 */
interface SecurityEventBackendGateway {
    fun isBackendConfigured(): Boolean
    suspend fun transmitEvent(
        item: QueueItemEntity,
        decryptedPayload: String
    ): BackendTransmissionOutcome
}

/**
 * Optional external dispatch relay for trusted-contact alerts (Cloud Function / FCM / SMS webhook).
 * In the current baseline project, no such external relay exists, so dispatch returns NotConfigured.
 */
interface TrustedContactDispatchBackend {
    fun isConfigured(): Boolean
    suspend fun dispatchTrustedContactAlert(
        ownerUid: String,
        deviceId: String,
        eventId: String,
        payloadJson: String
    ): BackendTransmissionOutcome
}

/**
 * Scrubs secrets, PINs, passwords, tokens, emails, and phone numbers from all logs and surfaced errors.
 */
object SecurityLogRedactor {
    private val SENSITIVE_JSON_FIELD_REGEX = Regex(
        """("(?:pin|masterPin|password|token|secret|authorization|authToken|fcmToken|phone|contactPhone|phoneNumber|ownerEmail|email)"\s*:\s*")([^"]*)(")""",
        RegexOption.IGNORE_CASE
    )
    private val BEARER_REGEX = Regex("""Bearer\s+[A-Za-z0-9\-._~+/]+=*""", RegexOption.IGNORE_CASE)
    private val EMAIL_REGEX = Regex("""[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}""")
    private val PHONE_REGEX = Regex("""(?<!\w)\+?\d[\d\s\-().]{5,}\d(?!\w)""")
    private val PIN_ASSIGN_REGEX = Regex("""\b(?:pin|passcode|password|token|secret)\s*[:=]\s*[^\s,;]+""", RegexOption.IGNORE_CASE)

    fun redact(input: String?): String {
        if (input.isNullOrBlank()) return ""
        var out = input
        out = SENSITIVE_JSON_FIELD_REGEX.replace(out) { m -> "${m.groupValues[1]}[REDACTED]${m.groupValues[3]}" }
        out = BEARER_REGEX.replace(out, "Bearer [REDACTED]")
        out = EMAIL_REGEX.replace(out, "[REDACTED_EMAIL]")
        out = PHONE_REGEX.replace(out, "[REDACTED_PHONE]")
        out = PIN_ASSIGN_REGEX.replace(out, "[REDACTED_CREDENTIAL]")
        return out.take(240)
    }

    fun sanitizeJsonMapForBackend(rawPayload: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        try {
            val json = JSONObject(rawPayload)
            val keys = json.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val lower = key.lowercase()
                val isSensitive = lower.contains("pin") ||
                    lower.contains("password") ||
                    lower.contains("token") ||
                    lower.contains("secret") ||
                    lower.contains("authorization") ||
                    lower.contains("phone") ||
                    lower.contains("email")
                if (isSensitive) {
                    result[key] = "[REDACTED]"
                } else {
                    val value = json.opt(key)
                    if (value != null && value != JSONObject.NULL) {
                        result[key] = when (value) {
                            is Number, is Boolean, is String -> value
                            else -> redact(value.toString())
                        }
                    }
                }
            }
        } catch (_: Exception) {
            result["summary"] = redact(rawPayload)
        }
        return result
    }
}

/**
 * Stores bounded retry timestamps and redacted failure metadata without altering Room table schema.
 */
class QueueRetryMetadataStore(
    private val prefs: SharedPreferences? = null
) {
    data class RetryMetadata(
        val nextRetryAt: Long = 0L,
        val permanentFailure: Boolean = false,
        val lastError: String? = null
    )

    private val memoryMap = mutableMapOf<String, RetryMetadata>()

    @Synchronized
    fun get(id: String): RetryMetadata {
        memoryMap[id]?.let { return it }
        if (prefs != null) {
            val raw = prefs.getString("retry_meta_$id", null)
            if (raw != null) {
                try {
                    val obj = JSONObject(raw)
                    val loaded = RetryMetadata(
                        nextRetryAt = obj.optLong("nextRetryAt", 0L),
                        permanentFailure = obj.optBoolean("permanentFailure", false),
                        lastError = obj.optString("lastError", "").ifBlank { null }
                    )
                    memoryMap[id] = loaded
                    return loaded
                } catch (_: Exception) {}
            }
        }
        return RetryMetadata()
    }

    @Synchronized
    fun put(id: String, metadata: RetryMetadata) {
        val redacted = metadata.copy(lastError = metadata.lastError?.let { SecurityLogRedactor.redact(it) })
        memoryMap[id] = redacted
        prefs?.edit()?.putString(
            "retry_meta_$id",
            JSONObject().apply {
                put("nextRetryAt", redacted.nextRetryAt)
                put("permanentFailure", redacted.permanentFailure)
                put("lastError", redacted.lastError ?: "")
            }.toString()
        )?.apply()
    }

    @Synchronized
    fun remove(id: String) {
        memoryMap.remove(id)
        prefs?.edit()?.remove("retry_meta_$id")?.apply()
    }
}

/**
 * Production Firebase Firestore gateway persisting owner-scoped security event records under:
 * users/{ownerUid}/devices/{deviceId}/recovery_history/evt_{eventId}
 * Matches existing firestore.rules without modifying security rules.
 * Never fabricates external push/SMS delivery for trusted contacts when no relay backend is configured.
 */
class FirestoreSecurityEventGateway(
    private val context: Context,
    private val trustedContactDispatchBackend: TrustedContactDispatchBackend? = null,
    private val firebaseInitializedProvider: () -> Boolean = {
        try {
            FirebaseApp.getApps(context).isNotEmpty()
        } catch (_: Exception) {
            false
        }
    },
    private val ownerUidProvider: () -> String? = {
        try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Exception) {
            null
        }
    },
    private val deviceIdProvider: () -> String = {
        DeviceSettingsRepository.getDeviceId(context)
    },
    private val firestoreProvider: () -> FirebaseFirestore? = {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) FirebaseFirestore.getInstance() else null
        } catch (_: Exception) {
            null
        }
    }
) : SecurityEventBackendGateway {

    override fun isBackendConfigured(): Boolean = firebaseInitializedProvider()

    override suspend fun transmitEvent(
        item: QueueItemEntity,
        decryptedPayload: String
    ): BackendTransmissionOutcome {
        if (!firebaseInitializedProvider()) {
            return BackendTransmissionOutcome.NotConfigured(
                "Backend cloud Firebase non initialisé sur cet appareil."
            )
        }

        val ownerUid = ownerUidProvider()?.trim().orEmpty()
        if (ownerUid.isBlank()) {
            return BackendTransmissionOutcome.AuthRequired(
                "Session propriétaire authentifiée requise pour la synchronisation cloud."
            )
        }

        val activeDeviceId = deviceIdProvider().trim()
        if (activeDeviceId.isBlank()) {
            return BackendTransmissionOutcome.PermanentFailure(
                "Identifiant d'appareil invalide pour l'envoi sécurisé."
            )
        }

        // Validate owner/device scope if declared inside payload
        val payloadJson = try {
            JSONObject(decryptedPayload)
        } catch (_: Exception) {
            null
        }
        val payloadOwner = payloadJson?.optString("ownerUid", "")?.trim().orEmpty()
        if (payloadOwner.isNotBlank() && payloadOwner != ownerUid) {
            return BackendTransmissionOutcome.PermanentFailure(
                "Conflit d'identité propriétaire rejeté par sécurité."
            )
        }
        val payloadDevice = payloadJson?.optString("deviceId", "")?.trim().orEmpty()
        if (payloadDevice.isNotBlank() && payloadDevice != activeDeviceId) {
            return BackendTransmissionOutcome.PermanentFailure(
                "Conflit d'identifiant d'appareil rejeté par sécurité."
            )
        }

        if (item.eventType == EVENT_TYPE_TRUSTED_CONTACT_SIM_ALERT) {
            val authorizedContactId = payloadJson?.optString("contactId", "")?.trim().orEmpty()
            val canReceiveAlerts = payloadJson?.optBoolean("canReceiveTheftAlerts", false) ?: false
            if (authorizedContactId.isBlank() || !canReceiveAlerts) {
                return BackendTransmissionOutcome.PermanentFailure(
                    "Destinataire non autorisé pour l'alerte contact de confiance."
                )
            }
            val dispatchBackend = trustedContactDispatchBackend
            if (dispatchBackend == null || !dispatchBackend.isConfigured()) {
                return BackendTransmissionOutcome.NotConfigured(
                    "Aucun service d'envoi externe (Push FCM, SMS ou Cloud Function) n'est configuré pour notifier les contacts de confiance."
                )
            }
            return dispatchBackend.dispatchTrustedContactAlert(
                ownerUid = ownerUid,
                deviceId = activeDeviceId,
                eventId = item.id,
                payloadJson = decryptedPayload
            )
        }

        val firestore = firestoreProvider()
            ?: return BackendTransmissionOutcome.NotConfigured("Instance Firestore indisponible.")

        return try {
            val docId = "evt_${item.id}"
            val sanitizedFields = SecurityLogRedactor.sanitizeJsonMapForBackend(decryptedPayload)
            val record = hashMapOf<String, Any>(
                "eventId" to item.id,
                "ownerUid" to ownerUid,
                "deviceId" to activeDeviceId,
                "eventType" to item.eventType,
                "priority" to item.priority,
                "createdAt" to item.timestamp,
                "syncedAt" to System.currentTimeMillis(),
                "payloadFields" to sanitizedFields
            )
            firestore.collection("users")
                .document(ownerUid)
                .collection("devices")
                .document(activeDeviceId)
                .collection("recovery_history")
                .document(docId)
                .set(record, SetOptions.merge())
                .await()
            BackendTransmissionOutcome.SentToBackend(backendReference = docId)
        } catch (e: FirebaseFirestoreException) {
            when (e.code) {
                FirebaseFirestoreException.Code.UNAUTHENTICATED,
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    BackendTransmissionOutcome.AuthRequired("Autorisation cloud refusée (${e.code.name}).")
                FirebaseFirestoreException.Code.INVALID_ARGUMENT ->
                    BackendTransmissionOutcome.PermanentFailure("Charge utile rejetée (${e.code.name}).")
                else ->
                    BackendTransmissionOutcome.TransientFailure("Erreur Firestore (${e.code.name}).")
            }
        } catch (e: Exception) {
            BackendTransmissionOutcome.TransientFailure(
                SecurityLogRedactor.redact(e.message ?: "Erreur réseau lors de la transmission.")
            )
        }
    }

    companion object {
        const val EVENT_TYPE_TRUSTED_CONTACT_SIM_ALERT = "TRUSTED_CONTACT_SIM_ALERT"
    }
}

/**
 * Offline-first reliable security event synchronization queue.
 * - Encrypts payloads locally via Hardware KeyStore vault.
 * - Uses Mutex + atomic SQL claims so concurrent workers cannot double-process an item.
 * - Preserves failed/offline/unconfigured events safely; NEVER deletes before confirmed success,
 *   and retains confirmed items within a bounded retention window for idempotency.
 */
class SecurityEventQueue(
    private val queueItemDao: QueueItemDao,
    private val vault: OfflineSecurityVault,
    private val backendGateway: SecurityEventBackendGateway? = null,
    private val retryMetadataStore: QueueRetryMetadataStore = QueueRetryMetadataStore(),
    private val nowProvider: () -> Long = { System.currentTimeMillis() },
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val baseBackoffMs: Long = DEFAULT_BASE_BACKOFF_MS,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
    private val logger: (String) -> Unit = { msg -> Log.i(TAG, SecurityLogRedactor.redact(msg)) }
) {

    val pendingCount: Flow<Int> = queueItemDao.getPendingCount()

    private val _queueSummary = MutableStateFlow(QueueStatusSummary())
    val queueSummary: StateFlow<QueueStatusSummary> = _queueSummary.asStateFlow()

    private val enqueueMutex = Mutex()
    private val processMutex = Mutex()
    private val recentContentFingerprints = mutableMapOf<String, Pair<String, Long>>()

    companion object {
        private const val TAG = "SecurityEventQueue"
        const val PAYLOAD_PREFIX_V1_ENC = "ENC:V1:"
        const val DEFAULT_MAX_RETRIES = 5
        const val DEFAULT_BASE_BACKOFF_MS = 10_000L
        const val DEFAULT_MAX_BACKOFF_MS = 300_000L
        const val DEFAULT_RETENTION_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
        private const val CONTENT_DEDUP_WINDOW_MS = 120_000L

        fun deterministicIdForKey(eventType: String, idempotencyKey: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$eventType::$idempotencyKey".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(28)
            return "evt_$digest"
        }

        private fun contentHash(eventType: String, payload: String): String {
            return MessageDigest.getInstance("SHA-256")
                .digest("$eventType::$payload".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }

    suspend fun enqueue(
        eventType: String,
        payload: String,
        priority: QueuePriority = QueuePriority.NORMAL,
        idempotencyKey: String? = null
    ): String {
        return enqueueWithResult(
            eventType = eventType,
            payload = payload,
            priority = priority,
            idempotencyKey = idempotencyKey
        ).eventId
    }

    suspend fun enqueueWithResult(
        eventType: String,
        payload: String,
        priority: QueuePriority = QueuePriority.NORMAL,
        idempotencyKey: String? = null
    ): EnqueueResult = enqueueMutex.withLock {
        val now = nowProvider()

        if (!idempotencyKey.isNullOrBlank()) {
            val stableId = deterministicIdForKey(eventType, idempotencyKey.trim())
            val existing = queueItemDao.getItemById(stableId)
            if (existing != null) {
                refreshSummaryLocked(lastAttemptAt = _queueSummary.value.lastSyncAttemptAt)
                logger("Duplicate event suppressed via idempotency key for eventType=$eventType id=$stableId")
                return@withLock EnqueueResult(
                    eventId = existing.id,
                    isDuplicateSuppressed = true,
                    status = existing.toDomain().syncStatus
                )
            }
            val encryptedPayload = vault.encryptValue(payload)?.let { "$PAYLOAD_PREFIX_V1_ENC$it" } ?: payload
            val item = QueueItem(
                id = stableId,
                eventType = eventType,
                timestamp = now,
                priority = priority,
                payloadJson = encryptedPayload,
                retryCount = 0,
                syncStatus = SyncStatus.LOCAL_QUEUED
            )
            val insertedRowId = queueItemDao.insertIfAbsent(QueueItemEntity.fromDomain(item))
            val contentFp = contentHash(eventType, payload)
            recentContentFingerprints[contentFp] = stableId to now
            refreshSummaryLocked(lastAttemptAt = _queueSummary.value.lastSyncAttemptAt)
            return@withLock EnqueueResult(
                eventId = stableId,
                isDuplicateSuppressed = insertedRowId == -1L,
                status = SyncStatus.LOCAL_QUEUED
            )
        }

        // Fallback duplicate suppression when no explicit idempotency key is provided
        val fp = contentHash(eventType, payload)
        val recentMatch = recentContentFingerprints[fp]
        if (recentMatch != null && (now - recentMatch.second) <= CONTENT_DEDUP_WINDOW_MS) {
            val existing = queueItemDao.getItemById(recentMatch.first)
            if (existing != null) {
                refreshSummaryLocked(lastAttemptAt = _queueSummary.value.lastSyncAttemptAt)
                logger("Duplicate unkeyed event suppressed within window for eventType=$eventType id=${existing.id}")
                return@withLock EnqueueResult(
                    eventId = existing.id,
                    isDuplicateSuppressed = true,
                    status = existing.toDomain().syncStatus
                )
            }
        }

        // Also scan active/recent queued records of same eventType to prevent duplicates across restarts
        val existingMatchingActive = queueItemDao.getAllQueueItems()
            .firstOrNull { candidate ->
                candidate.eventType == eventType &&
                    (now - candidate.timestamp) <= CONTENT_DEDUP_WINDOW_MS &&
                    resolvePayload(candidate.payloadJson) == payload
            }
        if (existingMatchingActive != null) {
            recentContentFingerprints[fp] = existingMatchingActive.id to existingMatchingActive.timestamp
            refreshSummaryLocked(lastAttemptAt = _queueSummary.value.lastSyncAttemptAt)
            logger("Duplicate event suppressed via durable scan for eventType=$eventType id=${existingMatchingActive.id}")
            return@withLock EnqueueResult(
                eventId = existingMatchingActive.id,
                isDuplicateSuppressed = true,
                status = existingMatchingActive.toDomain().syncStatus
            )
        }

        val eventId = "evt_${UUID.randomUUID()}"
        val encryptedPayload = vault.encryptValue(payload)?.let { "$PAYLOAD_PREFIX_V1_ENC$it" } ?: payload
        val item = QueueItem(
            id = eventId,
            eventType = eventType,
            timestamp = now,
            priority = priority,
            payloadJson = encryptedPayload,
            retryCount = 0,
            syncStatus = SyncStatus.LOCAL_QUEUED
        )
        queueItemDao.insertIfAbsent(QueueItemEntity.fromDomain(item))
        recentContentFingerprints[fp] = eventId to now
        refreshSummaryLocked(lastAttemptAt = _queueSummary.value.lastSyncAttemptAt)
        logger("Queued security event locally eventType=$eventType id=$eventId status=${SyncStatus.LOCAL_QUEUED.name}")
        return@withLock EnqueueResult(
            eventId = eventId,
            isDuplicateSuppressed = false,
            status = SyncStatus.LOCAL_QUEUED
        )
    }

    /**
     * Executes serialized, idempotent synchronization of queued security events.
     * - When offline: transitions LOCAL_QUEUED -> PENDING, retains everything, burns 0 retries.
     * - When online: atomically claims items, honors retry caps & exponential backoff,
     *   records honest backend outcomes, and never deletes failed or freshly sent items.
     */
    suspend fun processPendingQueue(isNetworkAvailable: Boolean): Int = processMutex.withLock {
        val now = nowProvider()

        if (!isNetworkAvailable) {
            queueItemDao.markLocalQueuedAsPending()
            refreshSummaryLocked(lastAttemptAt = _queueSummary.value.lastSyncAttemptAt)
            logger("Network unavailable: retaining queued security events in PENDING state.")
            return@withLock 0
        }

        // Recover any items left in SYNCING due to prior crash before processing
        queueItemDao.recoverStaleSyncingItems()

        val pending = queueItemDao.getPendingQueueItems()
        if (pending.isEmpty()) {
            refreshSummaryLocked(lastAttemptAt = now)
            return@withLock 0
        }

        var transmittedCount = 0
        var latestError: String? = null

        for (item in pending) {
            val meta = retryMetadataStore.get(item.id)
            if (item.syncStatus == SyncStatus.FAILED.name) {
                if (meta.permanentFailure || item.retryCount >= maxRetries) {
                    logger("Skipping terminal FAILED item id=${item.id} retryCount=${item.retryCount}")
                    continue
                }
                if (now < meta.nextRetryAt) {
                    logger("Deferring retry backoff for id=${item.id}")
                    continue
                }
            }

            // Atomic SQL claim prevents duplicate dispatch across concurrent callers
            val claimed = queueItemDao.claimForSync(item.id) == 1
            if (!claimed) {
                continue
            }

            val plaintextPayload = resolvePayload(item.payloadJson)
            if (plaintextPayload == null) {
                val quarantineError = "Charge utile chiffrée corrompue mise en quarantaine."
                queueItemDao.setStatusWithoutRetryIncrement(item.id, SyncStatus.FAILED.name)
                retryMetadataStore.put(
                    item.id,
                    QueueRetryMetadataStore.RetryMetadata(
                        nextRetryAt = Long.MAX_VALUE,
                        permanentFailure = true,
                        lastError = quarantineError
                    )
                )
                latestError = quarantineError
                logger("Quarantined unreadable encrypted queue item id=${item.id}")
                continue
            }

            if (plaintextPayload.isBlank()) {
                val invalidError = "Charge utile vide rejetée."
                queueItemDao.setStatusWithoutRetryIncrement(item.id, SyncStatus.FAILED.name)
                retryMetadataStore.put(
                    item.id,
                    QueueRetryMetadataStore.RetryMetadata(
                        nextRetryAt = Long.MAX_VALUE,
                        permanentFailure = true,
                        lastError = invalidError
                    )
                )
                latestError = invalidError
                continue
            }

            val gateway = backendGateway
            if (gateway == null || !gateway.isBackendConfigured()) {
                val reason = "Aucun service backend configuré pour ${item.eventType}."
                queueItemDao.setStatusWithoutRetryIncrement(item.id, SyncStatus.NOT_CONFIGURED.name)
                retryMetadataStore.put(
                    item.id,
                    QueueRetryMetadataStore.RetryMetadata(
                        nextRetryAt = 0L,
                        permanentFailure = false,
                        lastError = reason
                    )
                )
                latestError = reason
                logger("Queue item marked NOT_CONFIGURED id=${item.id} eventType=${item.eventType}")
                continue
            }

            val outcome = try {
                gateway.transmitEvent(item, plaintextPayload)
            } catch (e: Exception) {
                BackendTransmissionOutcome.TransientFailure(
                    SecurityLogRedactor.redact(e.message ?: "Erreur inattendue de transmission.")
                )
            }

            when (outcome) {
                is BackendTransmissionOutcome.SentToBackend -> {
                    queueItemDao.setStatusWithoutRetryIncrement(item.id, SyncStatus.SENT_TO_BACKEND.name)
                    retryMetadataStore.remove(item.id)
                    transmittedCount++
                    logger("Event transmitted to backend id=${item.id} status=SENT_TO_BACKEND")
                }

                is BackendTransmissionOutcome.DispatchAccepted -> {
                    queueItemDao.setStatusWithoutRetryIncrement(item.id, SyncStatus.DISPATCH_ACCEPTED.name)
                    retryMetadataStore.remove(item.id)
                    transmittedCount++
                    logger("Event dispatch accepted id=${item.id} status=DISPATCH_ACCEPTED")
                }

                is BackendTransmissionOutcome.DeliveryConfirmed -> {
                    queueItemDao.setStatusWithoutRetryIncrement(item.id, SyncStatus.DELIVERY_CONFIRMED.name)
                    retryMetadataStore.remove(item.id)
                    transmittedCount++
                    logger("Event delivery confirmed id=${item.id} status=DELIVERY_CONFIRMED")
                }

                is BackendTransmissionOutcome.AlreadyProcessed -> {
                    val resolved = when (outcome.existingStatus) {
                        SyncStatus.SENT_TO_BACKEND,
                        SyncStatus.DISPATCH_ACCEPTED,
                        SyncStatus.DELIVERY_CONFIRMED,
                        SyncStatus.SYNCED -> outcome.existingStatus
                        else -> SyncStatus.SENT_TO_BACKEND
                    }
                    queueItemDao.setStatusWithoutRetryIncrement(item.id, resolved.name)
                    retryMetadataStore.remove(item.id)
                    logger("Event already processed id=${item.id} status=${resolved.name}")
                }

                is BackendTransmissionOutcome.NotConfigured -> {
                    val safeReason = SecurityLogRedactor.redact(outcome.reason)
                    queueItemDao.setStatusWithoutRetryIncrement(item.id, SyncStatus.NOT_CONFIGURED.name)
                    retryMetadataStore.put(
                        item.id,
                        QueueRetryMetadataStore.RetryMetadata(
                            nextRetryAt = 0L,
                            permanentFailure = false,
                            lastError = safeReason
                        )
                    )
                    latestError = safeReason
                    logger("Dispatch not configured for id=${item.id}: $safeReason")
                }

                is BackendTransmissionOutcome.AuthRequired -> {
                    val safeReason = SecurityLogRedactor.redact(outcome.reason)
                    queueItemDao.updateStatus(item.id, SyncStatus.FAILED.name)
                    val newRetries = item.retryCount + 1
                    val backoffMs = computeBackoffMs(newRetries)
                    retryMetadataStore.put(
                        item.id,
                        QueueRetryMetadataStore.RetryMetadata(
                            nextRetryAt = now + backoffMs,
                            permanentFailure = newRetries >= maxRetries,
                            lastError = safeReason
                        )
                    )
                    latestError = safeReason
                    logger("Auth required for id=${item.id} retries=$newRetries/$maxRetries: $safeReason")
                }

                is BackendTransmissionOutcome.TransientFailure -> {
                    val safeReason = SecurityLogRedactor.redact(outcome.reason)
                    queueItemDao.updateStatus(item.id, SyncStatus.FAILED.name)
                    val newRetries = item.retryCount + 1
                    val backoffMs = computeBackoffMs(newRetries)
                    retryMetadataStore.put(
                        item.id,
                        QueueRetryMetadataStore.RetryMetadata(
                            nextRetryAt = now + backoffMs,
                            permanentFailure = newRetries >= maxRetries,
                            lastError = safeReason
                        )
                    )
                    latestError = safeReason
                    logger("Transient failure for id=${item.id} retries=$newRetries/$maxRetries nextBackoffMs=$backoffMs: $safeReason")
                }

                is BackendTransmissionOutcome.PermanentFailure -> {
                    val safeReason = SecurityLogRedactor.redact(outcome.reason)
                    queueItemDao.updateStatus(item.id, SyncStatus.FAILED.name)
                    retryMetadataStore.put(
                        item.id,
                        QueueRetryMetadataStore.RetryMetadata(
                            nextRetryAt = Long.MAX_VALUE,
                            permanentFailure = true,
                            lastError = safeReason
                        )
                    )
                    latestError = safeReason
                    logger("Permanent failure for id=${item.id}: $safeReason")
                }
            }
        }

        refreshSummaryLocked(lastAttemptAt = now, overrideError = latestError)
        return@withLock transmittedCount
    }

    /**
     * Retains confirmed items for a bounded window to support idempotency and auditing,
     * pruning only expired confirmed records while NEVER deleting pending/failed/unconfigured entries.
     */
    suspend fun pruneExpiredConfirmedItems(
        retentionWindowMillis: Long = DEFAULT_RETENTION_WINDOW_MS
    ): Int = processMutex.withLock {
        val cutoff = nowProvider() - retentionWindowMillis
        val deleted = queueItemDao.pruneConfirmedOlderThan(cutoff)
        refreshSummaryLocked(lastAttemptAt = _queueSummary.value.lastSyncAttemptAt)
        logger("Pruned $deleted confirmed queue items older than retention cutoff.")
        deleted
    }

    suspend fun refreshSummary(): QueueStatusSummary = processMutex.withLock {
        refreshSummaryLocked(lastAttemptAt = _queueSummary.value.lastSyncAttemptAt)
    }

    private suspend fun refreshSummaryLocked(
        lastAttemptAt: Long?,
        overrideError: String? = _queueSummary.value.lastErrorMessage
    ): QueueStatusSummary {
        val all = queueItemDao.getAllQueueItems()
        var localQueued = 0
        var pending = 0
        var syncing = 0
        var sentToBackend = 0
        var dispatchAccepted = 0
        var deliveryConfirmed = 0
        var failed = 0
        var notConfigured = 0

        for (entry in all) {
            when (entry.syncStatus) {
                SyncStatus.LOCAL_QUEUED.name -> localQueued++
                SyncStatus.PENDING.name -> pending++
                SyncStatus.SYNCING.name -> syncing++
                SyncStatus.SENT_TO_BACKEND.name,
                SyncStatus.SYNCED.name -> sentToBackend++
                SyncStatus.DISPATCH_ACCEPTED.name -> dispatchAccepted++
                SyncStatus.DELIVERY_CONFIRMED.name -> deliveryConfirmed++
                SyncStatus.FAILED.name -> failed++
                SyncStatus.NOT_CONFIGURED.name -> notConfigured++
            }
        }

        val resolvedError = overrideError ?: all
            .lastOrNull { it.syncStatus == SyncStatus.FAILED.name || it.syncStatus == SyncStatus.NOT_CONFIGURED.name }
            ?.let { retryMetadataStore.get(it.id).lastError }

        val summary = QueueStatusSummary(
            totalCount = all.size,
            localQueuedCount = localQueued,
            pendingCount = pending,
            syncingCount = syncing,
            sentToBackendCount = sentToBackend,
            dispatchAcceptedCount = dispatchAccepted,
            deliveryConfirmedCount = deliveryConfirmed,
            failedCount = failed,
            notConfiguredCount = notConfigured,
            lastErrorMessage = resolvedError?.let { SecurityLogRedactor.redact(it) },
            lastSyncAttemptAt = lastAttemptAt
        )
        _queueSummary.value = summary
        return summary
    }

    private fun computeBackoffMs(retryCount: Int): Long {
        if (retryCount <= 0) return baseBackoffMs
        val shift = (retryCount - 1).coerceAtMost(10)
        val factor = 1L shl shift
        val raw = if (baseBackoffMs > Long.MAX_VALUE / factor) Long.MAX_VALUE else baseBackoffMs * factor
        return raw.coerceAtMost(maxBackoffMs)
    }

    private fun resolvePayload(rawPayload: String): String? {
        return if (rawPayload.startsWith(PAYLOAD_PREFIX_V1_ENC)) {
            val ciphertext = rawPayload.removePrefix(PAYLOAD_PREFIX_V1_ENC)
            vault.decryptValue(ciphertext)
        } else {
            rawPayload
        }
    }
}
