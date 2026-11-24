package com.example.domain.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.core.model.QueuePriority
import com.example.core.model.SyncStatus
import com.example.core.model.TrustedPerson
import com.example.data.local.dao.QueueItemDao
import com.example.data.repository.DeviceSettingsRepository
import com.example.domain.sync.SecurityEventQueue
import com.example.domain.sync.SecurityLogRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Truthful delivery lifecycle stage for local alerts and external trusted-contact dispatch.
 */
enum class AlertDispatchStage {
    IDLE,
    LOCAL_QUEUED,
    PENDING,
    SENT_TO_BACKEND,
    DISPATCH_ACCEPTED,
    DELIVERY_CONFIRMED,
    FAILED,
    NOT_CONFIGURED
}

data class AlertDispatchStatus(
    val stage: AlertDispatchStage = AlertDispatchStage.IDLE,
    val trustedContactStage: AlertDispatchStage = AlertDispatchStage.IDLE,
    val authorizedRecipientsCount: Int = 0,
    val rejectedRecipientsCount: Int = 0,
    val queuedEventIds: List<String> = emptyList(),
    val lastNotificationBody: String = "",
    val lastStatusMessageFr: String = "",
    val lastStatusMessageEn: String = "",
    val isExternalContactDispatchConfigured: Boolean = false,
    val updatedAtMillis: Long? = null
)

/**
 * Dispatches truthful local system notifications and queues security/alert events
 * into the encrypted offline-first [SecurityEventQueue].
 *
 * Strict Truthfulness & Authorization Boundary:
 * - NEVER claims an alert was sent externally when only queued locally or unconfigured.
 * - Validates owner UID, device ID, and recipient TrustedPerson authorization before queuing.
 * - Never embeds credentials or logs sensitive PII (phone numbers, emails, PINs, tokens).
 */
class AlertNotificationManager(
    private val context: Context,
    private val eventQueue: SecurityEventQueue? = null,
    private val queueItemDao: QueueItemDao? = null,
    private val ownerUidProvider: () -> String? = { null },
    private val deviceIdProvider: () -> String = { DeviceSettingsRepository.getDeviceId(context) },
    private val authorizedContactsProvider: (() -> List<TrustedPerson>)? = null,
    private val isNetworkAvailableProvider: () -> Boolean = { true },
    private val isExternalContactBackendConfiguredProvider: () -> Boolean = { false },
    private val nowProvider: () -> Long = { System.currentTimeMillis() }
) {

    companion object {
        private const val TAG = "AlertNotificationMgr"
        const val ALERT_CHANNEL_ID = "inkingi_alerts_channel"
        const val ALERT_NOTIFICATION_ID = 3001
        private const val PREFS_NAME = "inkingi_alert_dedup_prefs"
        private const val KEY_LAST_SIM_ALERT_FP = "last_sim_alert_fingerprint"
    }

    private val _dispatchStatus = MutableStateFlow(
        AlertDispatchStatus(
            isExternalContactDispatchConfigured = isExternalContactBackendConfiguredProvider()
        )
    )
    val dispatchStatus: StateFlow<AlertDispatchStatus> = _dispatchStatus.asStateFlow()

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                ALERT_CHANNEL_ID,
                "InkingiGuard Security Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Critical alerts for SIM changes, theft mode, and perimeter violations."
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    suspend fun dispatchAlert(
        title: String,
        body: String,
        isCritical: Boolean = false,
        idempotencyKey: String? = null,
        isNetworkAvailable: Boolean = isNetworkAvailableProvider(),
        processImmediately: Boolean = false
    ): AlertDispatchStatus {
        val now = nowProvider()
        val ownerUid = ownerUidProvider()?.trim().orEmpty()
        val deviceId = deviceIdProvider().trim()

        showLocalNotification(title = title, body = body, isCritical = isCritical)

        val payloadJson = JSONObject().apply {
            if (ownerUid.isNotBlank()) put("ownerUid", ownerUid)
            if (deviceId.isNotBlank()) put("deviceId", deviceId)
            put("title", title)
            put("body", body)
            put("critical", isCritical)
            put("timestamp", now)
        }.toString()

        var eventId: String? = null
        var stage = if (eventQueue != null) AlertDispatchStage.LOCAL_QUEUED else AlertDispatchStage.NOT_CONFIGURED

        if (eventQueue != null) {
            eventId = eventQueue.enqueue(
                eventType = "CLOUD_ALERT_DISPATCH",
                payload = payloadJson,
                priority = if (isCritical) QueuePriority.CRITICAL else QueuePriority.HIGH,
                idempotencyKey = idempotencyKey
            )
            if (processImmediately) {
                eventQueue.processPendingQueue(isNetworkAvailable)
                stage = resolveStageForEventIds(listOf(eventId), isNetworkAvailable)
            } else if (!isNetworkAvailable) {
                stage = AlertDispatchStage.PENDING
            }
        }

        val updated = _dispatchStatus.value.copy(
            stage = stage,
            queuedEventIds = listOfNotNull(eventId),
            lastNotificationBody = body,
            lastStatusMessageFr = describeStageFr(stage, isTrustedContactAlert = false),
            lastStatusMessageEn = describeStageEn(stage, isTrustedContactAlert = false),
            isExternalContactDispatchConfigured = isExternalContactBackendConfiguredProvider(),
            updatedAtMillis = now
        )
        _dispatchStatus.value = updated
        return updated
    }

    /**
     * Supported fallback for lock-screen message on standard Android devices.
     * Shows a persistent high-priority notification with PUBLIC visibility so it appears on the lock screen.
     */
    fun showLockScreenRecoveryNotification(recoveryMessage: String, emergencyContact: String) {
        val sanitizedMessage = recoveryMessage.trim()
        if (sanitizedMessage.isBlank()) return
        val sanitizedContact = emergencyContact.trim()
        val bigText = if (sanitizedContact.isNotBlank()) {
            "$sanitizedMessage\nContact: $sanitizedContact"
        } else {
            sanitizedMessage
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle("InkingiGuard — Message de Récupération")
            .setContentText(sanitizedMessage)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setSmallIcon(R.drawable.ic_stat_security)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
        try {
            manager?.notify(4001, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied on Android 13+; fail safely without crashing
        }
    }

    /**
     * Truthful SIM-change alert handler:
     * 1. Deduplicates across restarts within 1-minute buckets.
     * 2. Validates owner UID, device ID, and recipient TrustedPerson authorization.
     * 3. Queues encrypted payloads safely and attempts processing when online.
     * 4. Surfaces honest notification & status copy: NEVER claims "Alerte envoyée aux contacts"
     *    unless external dispatch actually returned DISPATCH_ACCEPTED or DELIVERY_CONFIRMED.
     */
    suspend fun dispatchSimChangeAlert(
        carrier: String,
        trustedContacts: List<TrustedPerson>,
        isNetworkAvailable: Boolean = isNetworkAvailableProvider(),
        processImmediately: Boolean = true
    ): AlertDispatchStatus {
        val now = nowProvider()
        val minuteBucket = now / 60_000L
        val ownerUid = ownerUidProvider()?.trim().orEmpty()
        val deviceId = deviceIdProvider().trim()
        val fingerprint = "${ownerUid}_${deviceId}_${carrier.trim()}_$minuteBucket"

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastFp = prefs.getString(KEY_LAST_SIM_ALERT_FP, null)
        if (ownerUid.isNotBlank() && lastFp == fingerprint && _dispatchStatus.value.updatedAtMillis != null) {
            return _dispatchStatus.value
        }

        val authorizedPool = authorizedContactsProvider?.invoke()
        val authorizedById = authorizedPool?.associateBy { it.id }

        val validRecipients = mutableListOf<TrustedPerson>()
        var rejectedCount = 0

        if (ownerUid.isBlank() || deviceId.isBlank()) {
            rejectedCount = trustedContacts.size
        } else {
            for (candidate in trustedContacts) {
                val cleanPhone = candidate.phoneNumber.trim()
                val validPhone = DeviceSettingsRepository.isValidRecoveryPhone(cleanPhone)
                val inOwnerRegistry = if (authorizedById != null) {
                    val registered = authorizedById[candidate.id]
                    registered != null &&
                        registered.canReceiveTheftAlerts &&
                        registered.phoneNumber.trim() == cleanPhone
                } else {
                    candidate.id.isNotBlank() && candidate.canReceiveTheftAlerts
                }
                if (candidate.canReceiveTheftAlerts && validPhone && inOwnerRegistry) {
                    validRecipients.add(candidate)
                } else {
                    rejectedCount++
                }
            }
        }

        val queuedContactEventIds = mutableListOf<String>()
        if (eventQueue != null && ownerUid.isNotBlank() && deviceId.isNotBlank()) {
            for (contact in validRecipients) {
                val contactPayload = JSONObject().apply {
                    put("ownerUid", ownerUid)
                    put("deviceId", deviceId)
                    put("contactId", contact.id)
                    put("contactName", contact.name)
                    put("contactPhone", contact.phoneNumber)
                    put("canReceiveTheftAlerts", contact.canReceiveTheftAlerts)
                    put("carrier", carrier)
                    put("timestamp", now)
                }.toString()

                val contactKey = "sim_contact_${ownerUid}_${deviceId}_${contact.id}_${carrier.trim()}_$minuteBucket"
                val id = eventQueue.enqueue(
                    eventType = "TRUSTED_CONTACT_SIM_ALERT",
                    payload = contactPayload,
                    priority = QueuePriority.CRITICAL,
                    idempotencyKey = contactKey
                )
                queuedContactEventIds.add(id)
            }
        }

        // Also record the owner device security alert event cleanly
        val alertPayload = JSONObject().apply {
            if (ownerUid.isNotBlank()) put("ownerUid", ownerUid)
            if (deviceId.isNotBlank()) put("deviceId", deviceId)
            put("title", "Changement de carte SIM détecté")
            put("carrier", carrier)
            put("authorizedRecipients", validRecipients.size)
            put("rejectedRecipients", rejectedCount)
            put("timestamp", now)
        }.toString()

        var alertEventId: String? = null
        if (eventQueue != null) {
            alertEventId = eventQueue.enqueue(
                eventType = "CLOUD_ALERT_DISPATCH",
                payload = alertPayload,
                priority = QueuePriority.CRITICAL,
                idempotencyKey = "sim_owner_${deviceId}_${carrier.trim()}_$minuteBucket"
            )
        }

        if (processImmediately && eventQueue != null) {
            eventQueue.processPendingQueue(isNetworkAvailable)
        }

        val contactStage = when {
            ownerUid.isBlank() || deviceId.isBlank() -> AlertDispatchStage.FAILED
            trustedContacts.isEmpty() -> AlertDispatchStage.NOT_CONFIGURED
            validRecipients.isEmpty() -> AlertDispatchStage.FAILED
            eventQueue == null -> AlertDispatchStage.NOT_CONFIGURED
            !isNetworkAvailable -> AlertDispatchStage.PENDING
            processImmediately -> resolveStageForEventIds(queuedContactEventIds, isNetworkAvailable)
            else -> AlertDispatchStage.LOCAL_QUEUED
        }

        val overallStage = when {
            alertEventId != null && processImmediately -> resolveStageForEventIds(listOf(alertEventId), isNetworkAvailable)
            alertEventId != null && !isNetworkAvailable -> AlertDispatchStage.PENDING
            alertEventId != null -> AlertDispatchStage.LOCAL_QUEUED
            else -> contactStage
        }

        val honestBodyFr = buildTruthfulSimNotificationBody(
            carrier = carrier,
            contactStage = contactStage,
            ownerAuthenticated = ownerUid.isNotBlank() && deviceId.isNotBlank(),
            validRecipientsCount = validRecipients.size,
            rejectedCount = rejectedCount
        )

        showLocalNotification(
            title = "ALERTE SÉCURITÉ : Changement de carte SIM détecté",
            body = honestBodyFr,
            isCritical = true
        )
        if (ownerUid.isNotBlank() && validRecipients.isNotEmpty()) {
            prefs.edit().putString(KEY_LAST_SIM_ALERT_FP, fingerprint).apply()
        }

        Log.i(
            TAG,
            SecurityLogRedactor.redact(
                "SIM alert processed carrier=$carrier stage=$overallStage contactStage=$contactStage authorized=${validRecipients.size} rejected=$rejectedCount"
            )
        )

        val status = AlertDispatchStatus(
            stage = overallStage,
            trustedContactStage = contactStage,
            authorizedRecipientsCount = validRecipients.size,
            rejectedRecipientsCount = rejectedCount,
            queuedEventIds = (queuedContactEventIds + listOfNotNull(alertEventId)).distinct(),
            lastNotificationBody = honestBodyFr,
            lastStatusMessageFr = describeSimStageFr(
                contactStage = contactStage,
                ownerAuthenticated = ownerUid.isNotBlank() && deviceId.isNotBlank(),
                validRecipientsCount = validRecipients.size,
                rejectedCount = rejectedCount
            ),
            lastStatusMessageEn = describeSimStageEn(
                contactStage = contactStage,
                ownerAuthenticated = ownerUid.isNotBlank() && deviceId.isNotBlank(),
                validRecipientsCount = validRecipients.size,
                rejectedCount = rejectedCount
            ),
            isExternalContactDispatchConfigured = isExternalContactBackendConfiguredProvider(),
            updatedAtMillis = now
        )
        _dispatchStatus.value = status
        return status
    }

    /**
     * Backward-compatible overload for callers holding (name, phone) pairs.
     * Validates each pair strictly against the authorized owner contact registry when provided;
     * rejects unverified entries when no authorized contact matches.
     */
    suspend fun dispatchSimChangeAlert(
        carrier: String,
        trustedContacts: List<Pair<String, String>>
    ): AlertDispatchStatus {
        val authorizedByPhone = authorizedContactsProvider?.invoke()
            ?.filter { it.canReceiveTheftAlerts }
            ?.associateBy { it.phoneNumber.trim() }
            .orEmpty()

        val mapped = trustedContacts.mapIndexed { idx, (name, phone) ->
            val match = authorizedByPhone[phone.trim()]
            match ?: TrustedPerson(
                id = if (authorizedContactsProvider == null) "pair_$idx" else "",
                name = name,
                phoneNumber = phone,
                canReceiveTheftAlerts = authorizedContactsProvider == null
            )
        }
        return dispatchSimChangeAlert(
            carrier = carrier,
            trustedContacts = mapped,
            isNetworkAvailable = isNetworkAvailableProvider(),
            processImmediately = true
        )
    }

    private suspend fun resolveStageForEventIds(
        ids: List<String>,
        isNetworkAvailable: Boolean
    ): AlertDispatchStage {
        if (ids.isEmpty()) return AlertDispatchStage.LOCAL_QUEUED
        val dao = queueItemDao
        if (dao == null) {
            return if (!isNetworkAvailable) AlertDispatchStage.PENDING else AlertDispatchStage.LOCAL_QUEUED
        }
        val statuses = ids.mapNotNull { dao.getItemById(it)?.toDomain()?.syncStatus }
        if (statuses.isEmpty()) {
            return if (!isNetworkAvailable) AlertDispatchStage.PENDING else AlertDispatchStage.LOCAL_QUEUED
        }
        return when {
            statuses.all { it == SyncStatus.DELIVERY_CONFIRMED } -> AlertDispatchStage.DELIVERY_CONFIRMED
            statuses.all { it == SyncStatus.DISPATCH_ACCEPTED } -> AlertDispatchStage.DISPATCH_ACCEPTED
            statuses.all { it == SyncStatus.SENT_TO_BACKEND || it == SyncStatus.SYNCED } -> AlertDispatchStage.SENT_TO_BACKEND
            statuses.any { it == SyncStatus.NOT_CONFIGURED } -> AlertDispatchStage.NOT_CONFIGURED
            statuses.any { it == SyncStatus.FAILED } -> AlertDispatchStage.FAILED
            statuses.any { it == SyncStatus.PENDING } -> AlertDispatchStage.PENDING
            else -> AlertDispatchStage.LOCAL_QUEUED
        }
    }

    private fun buildTruthfulSimNotificationBody(
        carrier: String,
        contactStage: AlertDispatchStage,
        ownerAuthenticated: Boolean,
        validRecipientsCount: Int,
        rejectedCount: Int
    ): String {
        return when {
            !ownerAuthenticated ->
                "Changement de carte SIM observé ($carrier). Enregistré localement uniquement : connexion propriétaire requise avant tout envoi."
            validRecipientsCount == 0 && rejectedCount > 0 ->
                "Changement de carte SIM observé ($carrier). Aucun contact de confiance autorisé n'est actif pour cet envoi."
            contactStage == AlertDispatchStage.DELIVERY_CONFIRMED ->
                "Changement de carte SIM observé ($carrier). Livraison confirmée pour $validRecipientsCount contact(s) de confiance autorisé(s)."
            contactStage == AlertDispatchStage.DISPATCH_ACCEPTED ->
                "Changement de carte SIM observé ($carrier). Envoi accepté par le service relais pour $validRecipientsCount contact(s) autorisé(s)."
            contactStage == AlertDispatchStage.SENT_TO_BACKEND ->
                "Changement de carte SIM observé ($carrier). Événement enregistré sur le serveur cloud propriétaire."
            contactStage == AlertDispatchStage.NOT_CONFIGURED ->
                "Changement de carte SIM observé ($carrier). Envoi externe aux contacts de confiance non configuré (aucun relais Push/SMS actif)."
            contactStage == AlertDispatchStage.PENDING ->
                "Changement de carte SIM observé ($carrier). Alerte en attente de réseau/backend (non encore envoyée)."
            contactStage == AlertDispatchStage.FAILED ->
                "Changement de carte SIM observé ($carrier). Échec d'envoi de l'alerte — conservée en file locale pour nouvelle tentative."
            else ->
                "Changement de carte SIM observé ($carrier). Alerte enregistrée localement (non encore envoyée)."
        }
    }

    private fun describeSimStageFr(
        contactStage: AlertDispatchStage,
        ownerAuthenticated: Boolean,
        validRecipientsCount: Int,
        rejectedCount: Int
    ): String = when {
        !ownerAuthenticated -> "Enregistrée localement — authentification propriétaire requise."
        validRecipientsCount == 0 && rejectedCount > 0 -> "Envoi refusé : contact(s) non autorisé(s) ou désactivé(s)."
        contactStage == AlertDispatchStage.DELIVERY_CONFIRMED -> "Livraison confirmée aux contacts autorisés ($validRecipientsCount)."
        contactStage == AlertDispatchStage.DISPATCH_ACCEPTED -> "Acceptée par le service d'envoi pour $validRecipientsCount contact(s)."
        contactStage == AlertDispatchStage.SENT_TO_BACKEND -> "Enregistrée sur le serveur cloud propriétaire."
        contactStage == AlertDispatchStage.NOT_CONFIGURED -> "Non configuré : aucun service Push/SMS/Cloud Function configuré pour contacter vos proches."
        contactStage == AlertDispatchStage.PENDING -> "En attente de réseau/backend (conservée hors ligne)."
        contactStage == AlertDispatchStage.FAILED -> "Échec d'envoi — événement conservé pour nouvelle tentative."
        else -> "Alerte enregistrée localement."
    }

    private fun describeSimStageEn(
        contactStage: AlertDispatchStage,
        ownerAuthenticated: Boolean,
        validRecipientsCount: Int,
        rejectedCount: Int
    ): String = when {
        !ownerAuthenticated -> "Saved locally — signed-in owner verification required."
        validRecipientsCount == 0 && rejectedCount > 0 -> "Dispatch rejected: unauthorized or disabled trusted contact(s)."
        contactStage == AlertDispatchStage.DELIVERY_CONFIRMED -> "Delivery confirmed to authorized contact(s) ($validRecipientsCount)."
        contactStage == AlertDispatchStage.DISPATCH_ACCEPTED -> "Accepted by external dispatch service for $validRecipientsCount authorized contact(s)."
        contactStage == AlertDispatchStage.SENT_TO_BACKEND -> "Stored in owner cloud backend."
        contactStage == AlertDispatchStage.NOT_CONFIGURED -> "Not configured: no external Push/SMS/Cloud Function relay exists to notify trusted contacts."
        contactStage == AlertDispatchStage.PENDING -> "Waiting for internet/backend (preserved offline)."
        contactStage == AlertDispatchStage.FAILED -> "Dispatch failed — event preserved in queue for retry."
        else -> "Alert saved locally."
    }

    private fun describeStageFr(stage: AlertDispatchStage, isTrustedContactAlert: Boolean): String = when (stage) {
        AlertDispatchStage.IDLE -> "Aucune alerte récente"
        AlertDispatchStage.LOCAL_QUEUED -> "Alerte enregistrée localement"
        AlertDispatchStage.PENDING -> "En attente de connexion / serveur"
        AlertDispatchStage.SENT_TO_BACKEND -> "Envoyée au serveur cloud"
        AlertDispatchStage.DISPATCH_ACCEPTED -> "Acceptée par le service d'envoi"
        AlertDispatchStage.DELIVERY_CONFIRMED -> "Livraison confirmée"
        AlertDispatchStage.FAILED -> "Échec d'envoi — en file de reprise"
        AlertDispatchStage.NOT_CONFIGURED -> if (isTrustedContactAlert) {
            "Envoi aux contacts de confiance non configuré"
        } else {
            "Service d'envoi externe non configuré"
        }
    }

    private fun describeStageEn(stage: AlertDispatchStage, isTrustedContactAlert: Boolean): String = when (stage) {
        AlertDispatchStage.IDLE -> "No recent alerts"
        AlertDispatchStage.LOCAL_QUEUED -> "Alert saved locally"
        AlertDispatchStage.PENDING -> "Waiting for internet / backend"
        AlertDispatchStage.SENT_TO_BACKEND -> "Sent to backend"
        AlertDispatchStage.DISPATCH_ACCEPTED -> "Accepted by dispatch service"
        AlertDispatchStage.DELIVERY_CONFIRMED -> "Delivery confirmed"
        AlertDispatchStage.FAILED -> "Send failed — queued for retry"
        AlertDispatchStage.NOT_CONFIGURED -> if (isTrustedContactAlert) {
            "Trusted contact dispatch not configured"
        } else {
            "External dispatch service not configured"
        }
    }

    private fun showLocalNotification(title: String, body: String, isCritical: Boolean) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setContentTitle("InkingiGuard : $title")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat_security)
            .setPriority(if (isCritical) NotificationCompat.PRIORITY_MAX else NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        try {
            manager?.notify(ALERT_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS denied on Android 13+; queue dispatch continues safely
        }
    }
}
