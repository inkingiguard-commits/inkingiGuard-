package com.example

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import com.example.core.model.QueuePriority
import com.example.core.model.SyncStatus
import com.example.core.model.TrustedPerson
import com.example.data.local.InkingiDatabase
import com.example.data.local.entity.QueueItemEntity
import com.example.data.repository.DeviceSettingsRepository
import com.example.domain.alerts.AlertDispatchStage
import com.example.domain.alerts.AlertNotificationManager
import com.example.domain.security.OfflineSecurityVault
import com.example.domain.sync.BackendTransmissionOutcome
import com.example.domain.sync.FirestoreSecurityEventGateway
import com.example.domain.sync.QueueRetryMetadataStore
import com.example.domain.sync.SecurityEventBackendGateway
import com.example.domain.sync.SecurityEventQueue
import com.example.domain.sync.SecurityLogRedactor
import com.example.domain.sync.TrustedContactDispatchBackend
import com.example.presentation.InkingiUiState
import com.example.presentation.settings.SettingsScreen
import com.example.presentation.theft.TheftRecoveryScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SecurityEventQueueAndAlertsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Application
    private lateinit var db: InkingiDatabase
    private lateinit var vault: OfflineSecurityVault

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        db = Room.inMemoryDatabaseBuilder(context, InkingiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        vault = OfflineSecurityVault(context)
        context.getSharedPreferences("inkingi_alert_dedup_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `1 successful backend transmission sets SENT_TO_BACKEND without immediate deletion`() = runBlocking {
        val backend = object : SecurityEventBackendGateway {
            var calls = 0
            override fun isBackendConfigured(): Boolean = true
            override suspend fun transmitEvent(item: QueueItemEntity, decryptedPayload: String): BackendTransmissionOutcome {
                calls++
                return BackendTransmissionOutcome.SentToBackend("fs-${item.id}")
            }
        }
        val queue = SecurityEventQueue(db.queueItemDao(), vault, backend)
        val id = queue.enqueue("INCIDENT_UPDATE", "{\"incidentId\":\"INC-1\",\"score\":80}", QueuePriority.HIGH)

        assertEquals(SyncStatus.LOCAL_QUEUED.name, db.queueItemDao().getItemById(id)?.syncStatus)

        val transmitted = queue.processPendingQueue(isNetworkAvailable = true)
        assertEquals(1, transmitted)
        assertEquals(1, backend.calls)

        val persisted = db.queueItemDao().getItemById(id)
        assertNotNull("Confirmed item must remain stored within retention window", persisted)
        assertEquals(SyncStatus.SENT_TO_BACKEND.name, persisted?.syncStatus)
        assertEquals(0, queue.pendingCount.first())
        assertEquals(1, queue.queueSummary.value.sentToBackendCount)
    }

    @Test
    fun `2 offline queue retains events safely in PENDING without burning retries or calling backend`() = runBlocking {
        val backend = object : SecurityEventBackendGateway {
            var calls = 0
            override fun isBackendConfigured(): Boolean = true
            override suspend fun transmitEvent(item: QueueItemEntity, decryptedPayload: String): BackendTransmissionOutcome {
                calls++
                return BackendTransmissionOutcome.SentToBackend("ref")
            }
        }
        val queue = SecurityEventQueue(db.queueItemDao(), vault, backend)
        val id = queue.enqueue("THEFT_REPORT_COMPILED", "{\"reportId\":\"R-OFFLINE\"}", QueuePriority.CRITICAL)

        val transmitted = queue.processPendingQueue(isNetworkAvailable = false)
        assertEquals(0, transmitted)
        assertEquals(0, backend.calls)

        val stored = db.queueItemDao().getItemById(id)
        assertNotNull(stored)
        assertEquals(SyncStatus.PENDING.name, stored?.syncStatus)
        assertEquals(0, stored?.retryCount)
        assertEquals(1, queue.queueSummary.value.pendingCount)
    }

    @Test
    fun `3 backend failure increments retry applies exponential backoff and stops at maxRetries`() = runBlocking {
        val clock = AtomicLong(1_000_000L)
        val shouldFail = AtomicBoolean(true)
        val attempts = AtomicInteger(0)

        val backend = object : SecurityEventBackendGateway {
            override fun isBackendConfigured(): Boolean = true
            override suspend fun transmitEvent(item: QueueItemEntity, decryptedPayload: String): BackendTransmissionOutcome {
                attempts.incrementAndGet()
                return if (shouldFail.get()) {
                    BackendTransmissionOutcome.TransientFailure("503 Service Unavailable")
                } else {
                    BackendTransmissionOutcome.SentToBackend("ok")
                }
            }
        }

        val queue = SecurityEventQueue(
            queueItemDao = db.queueItemDao(),
            vault = vault,
            backendGateway = backend,
            retryMetadataStore = QueueRetryMetadataStore(),
            nowProvider = { clock.get() },
            maxRetries = 3,
            baseBackoffMs = 10_000L,
            maxBackoffMs = 40_000L
        )
        val id = queue.enqueue("INCIDENT_UPDATE", "{\"incidentId\":\"INC-RETRY\"}", QueuePriority.HIGH)

        // Attempt 1 fails -> retryCount=1, nextRetryAt = +10_000ms
        assertEquals(0, queue.processPendingQueue(isNetworkAvailable = true))
        assertEquals(1, attempts.get())
        assertEquals(SyncStatus.FAILED.name, db.queueItemDao().getItemById(id)?.syncStatus)
        assertEquals(1, db.queueItemDao().getItemById(id)?.retryCount)

        // Before backoff elapses -> skipped (no tight retry spin)
        clock.addAndGet(5_000L)
        assertEquals(0, queue.processPendingQueue(isNetworkAvailable = true))
        assertEquals(1, attempts.get())

        // After 10_000ms -> Attempt 2 fails -> retryCount=2, nextRetryAt = +20_000ms
        clock.addAndGet(6_000L)
        assertEquals(0, queue.processPendingQueue(isNetworkAvailable = true))
        assertEquals(2, attempts.get())
        assertEquals(2, db.queueItemDao().getItemById(id)?.retryCount)

        // Recovery succeeds after 20s backoff
        shouldFail.set(false)
        clock.addAndGet(21_000L)
        assertEquals(1, queue.processPendingQueue(isNetworkAvailable = true))
        assertEquals(3, attempts.get())
        assertEquals(SyncStatus.SENT_TO_BACKEND.name, db.queueItemDao().getItemById(id)?.syncStatus)

        // Second item hits maxRetries = 3 and terminates cleanly without infinite loop
        shouldFail.set(true)
        val idExhausted = queue.enqueue("INCIDENT_UPDATE", "{\"incidentId\":\"INC-EXHAUST\"}", QueuePriority.HIGH)
        queue.processPendingQueue(isNetworkAvailable = true) // retry 1
        clock.addAndGet(15_000L)
        queue.processPendingQueue(isNetworkAvailable = true) // retry 2
        clock.addAndGet(25_000L)
        queue.processPendingQueue(isNetworkAvailable = true) // retry 3 (maxRetries reached)
        val callsAtMax = attempts.get()
        clock.addAndGet(100_000L)
        queue.processPendingQueue(isNetworkAvailable = true) // must NOT retry beyond maxRetries
        assertEquals(callsAtMax, attempts.get())
        assertEquals(SyncStatus.FAILED.name, db.queueItemDao().getItemById(idExhausted)?.syncStatus)
        assertEquals(3, db.queueItemDao().getItemById(idExhausted)?.retryCount)
    }

    @Test
    fun `4 authentication failure retains queued event and fails closed without sending`() = runBlocking {
        var firebaseWrites = 0
        val gateway = FirestoreSecurityEventGateway(
            context = context,
            trustedContactDispatchBackend = null,
            firebaseInitializedProvider = { true },
            ownerUidProvider = { null }, // Signed-out!
            deviceIdProvider = { "DEV-AUTH-TEST" },
            firestoreProvider = {
                firebaseWrites++
                null
            }
        )
        val queue = SecurityEventQueue(db.queueItemDao(), vault, gateway)
        val id = queue.enqueue("INCIDENT_UPDATE", "{\"incidentId\":\"INC-AUTH\"}", QueuePriority.HIGH)

        val count = queue.processPendingQueue(isNetworkAvailable = true)
        assertEquals(0, count)
        assertEquals(0, firebaseWrites)

        val item = db.queueItemDao().getItemById(id)
        assertNotNull(item)
        assertEquals(SyncStatus.FAILED.name, item?.syncStatus)
        assertTrue(queue.queueSummary.value.lastErrorMessage?.contains("authentifiée", ignoreCase = true) == true)
    }

    @Test
    fun `5 duplicate event prevention suppresses identical idempotency keys and repeated payloads`() = runBlocking {
        val clock = AtomicLong(5_000_000L)
        val queue = SecurityEventQueue(
            queueItemDao = db.queueItemDao(),
            vault = vault,
            backendGateway = null,
            nowProvider = { clock.get() }
        )

        val r1 = queue.enqueueWithResult("INCIDENT_UPDATE", "{\"incident\":\"DUP-1\"}", QueuePriority.HIGH, idempotencyKey = "idem-123")
        val r2 = queue.enqueueWithResult("INCIDENT_UPDATE", "{\"incident\":\"DUP-1\"}", QueuePriority.HIGH, idempotencyKey = "idem-123")

        assertFalse(r1.isDuplicateSuppressed)
        assertTrue(r2.isDuplicateSuppressed)
        assertEquals(r1.eventId, r2.eventId)

        // Also test unkeyed rapid duplicate suppression within window
        val u1 = queue.enqueueWithResult("SIM_ALERT", "{\"carrier\":\"Lumitel\"}", QueuePriority.CRITICAL)
        val u2 = queue.enqueueWithResult("SIM_ALERT", "{\"carrier\":\"Lumitel\"}", QueuePriority.CRITICAL)
        assertFalse(u1.isDuplicateSuppressed)
        assertTrue(u2.isDuplicateSuppressed)
        assertEquals(u1.eventId, u2.eventId)
        assertEquals(2, db.queueItemDao().getAllQueueItems().size)
    }

    @Test
    fun `6 idempotent event processing sends once even across repeated sync cycles`() = runBlocking {
        val dispatchedKeys = mutableListOf<String>()
        val backend = object : SecurityEventBackendGateway {
            override fun isBackendConfigured(): Boolean = true
            override suspend fun transmitEvent(item: QueueItemEntity, decryptedPayload: String): BackendTransmissionOutcome {
                dispatchedKeys.add(item.id)
                return BackendTransmissionOutcome.DispatchAccepted("disp-${item.id}")
            }
        }
        val queue = SecurityEventQueue(db.queueItemDao(), vault, backend)
        val id = queue.enqueue("CLOUD_ALERT_DISPATCH", "{\"alert\":\"Perimeter\"}", QueuePriority.CRITICAL, idempotencyKey = "alert-key-1")

        assertEquals(1, queue.processPendingQueue(isNetworkAvailable = true))
        // Repeated enqueue with same key must NOT reset confirmed status or resend
        val dup = queue.enqueueWithResult("CLOUD_ALERT_DISPATCH", "{\"alert\":\"Perimeter\"}", QueuePriority.CRITICAL, idempotencyKey = "alert-key-1")
        assertTrue(dup.isDuplicateSuppressed)
        assertEquals(0, queue.processPendingQueue(isNetworkAvailable = true))
        assertEquals(listOf(id), dispatchedKeys)
        assertEquals(SyncStatus.DISPATCH_ACCEPTED.name, db.queueItemDao().getItemById(id)?.syncStatus)
    }

    @Test
    fun `7 entries are never deleted before confirmed success and retention pruning keeps non-confirmed entries`() = runBlocking {
        val clock = AtomicLong(100_000_000L)
        val backend = object : SecurityEventBackendGateway {
            override fun isBackendConfigured(): Boolean = true
            override suspend fun transmitEvent(item: QueueItemEntity, decryptedPayload: String): BackendTransmissionOutcome {
                return if (decryptedPayload.contains("KEEP_FAILED")) {
                    BackendTransmissionOutcome.PermanentFailure("Rejected")
                } else {
                    BackendTransmissionOutcome.SentToBackend("ok")
                }
            }
        }
        val queue = SecurityEventQueue(
            queueItemDao = db.queueItemDao(),
            vault = vault,
            backendGateway = backend,
            nowProvider = { clock.get() }
        )

        val sentId = queue.enqueue("INCIDENT_UPDATE", "{\"type\":\"OK_EVENT\"}", idempotencyKey = "k-ok")
        val failedId = queue.enqueue("INCIDENT_UPDATE", "{\"type\":\"KEEP_FAILED\"}", idempotencyKey = "k-fail")
        queue.processPendingQueue(isNetworkAvailable = true)

        // Immediately after sync: BOTH remain stored safely
        assertNotNull(db.queueItemDao().getItemById(sentId))
        assertNotNull(db.queueItemDao().getItemById(failedId))

        // Within retention window (1 day): nothing pruned
        clock.addAndGet(24L * 60L * 60L * 1000L)
        assertEquals(0, queue.pruneExpiredConfirmedItems(SecurityEventQueue.DEFAULT_RETENTION_WINDOW_MS))

        // Past retention window (8 days): confirmed sentId is pruned, failedId stays safe!
        clock.addAndGet(7L * 24L * 60L * 60L * 1000L)
        val pruned = queue.pruneExpiredConfirmedItems(SecurityEventQueue.DEFAULT_RETENTION_WINDOW_MS)
        assertEquals(1, pruned)
        assertEquals(null, db.queueItemDao().getItemById(sentId))
        assertNotNull("Failed entry must never be deleted by retention pruning", db.queueItemDao().getItemById(failedId))
    }

    @Test
    fun `8 concurrent worker protection serializes processing so each event transmits once`() = runBlocking {
        val backendCalls = AtomicInteger(0)
        val backend = object : SecurityEventBackendGateway {
            override fun isBackendConfigured(): Boolean = true
            override suspend fun transmitEvent(item: QueueItemEntity, decryptedPayload: String): BackendTransmissionOutcome {
                delay(40)
                backendCalls.incrementAndGet()
                return BackendTransmissionOutcome.DeliveryConfirmed("del-${item.id}")
            }
        }
        val queue = SecurityEventQueue(db.queueItemDao(), vault, backend)
        queue.enqueue("THEFT_REPORT_COMPILED", "{\"reportId\":\"CONCURRENT-1\"}", QueuePriority.CRITICAL, idempotencyKey = "conc-1")
        queue.enqueue("THEFT_REPORT_COMPILED", "{\"reportId\":\"CONCURRENT-2\"}", QueuePriority.CRITICAL, idempotencyKey = "conc-2")

        val results = (1..4).map {
            async(Dispatchers.Default) {
                queue.processPendingQueue(isNetworkAvailable = true)
            }
        }.awaitAll()

        assertEquals(2, backendCalls.get())
        assertEquals(2, results.sum())
    }

    @Test
    fun `9 status transitions follow truthful lifecycle states`() = runBlocking {
        val outcomeQueue = ArrayDeque<BackendTransmissionOutcome>(
            listOf(
                BackendTransmissionOutcome.TransientFailure("Temporary timeout"),
                BackendTransmissionOutcome.SentToBackend("fs-1"),
                BackendTransmissionOutcome.DispatchAccepted("disp-2"),
                BackendTransmissionOutcome.DeliveryConfirmed("conf-3")
            )
        )
        val clock = AtomicLong(1_000_000L)
        val backend = object : SecurityEventBackendGateway {
            override fun isBackendConfigured(): Boolean = true
            override suspend fun transmitEvent(item: QueueItemEntity, decryptedPayload: String): BackendTransmissionOutcome {
                return outcomeQueue.removeFirst()
            }
        }
        val queue = SecurityEventQueue(
            queueItemDao = db.queueItemDao(),
            vault = vault,
            backendGateway = backend,
            nowProvider = { clock.get() },
            baseBackoffMs = 5_000L
        )

        val id1 = queue.enqueue("INCIDENT_UPDATE", "{\"n\":1}", idempotencyKey = "st-1")
        assertEquals(SyncStatus.LOCAL_QUEUED.name, db.queueItemDao().getItemById(id1)?.syncStatus)

        queue.processPendingQueue(isNetworkAvailable = false)
        assertEquals(SyncStatus.PENDING.name, db.queueItemDao().getItemById(id1)?.syncStatus)

        queue.processPendingQueue(isNetworkAvailable = true)
        assertEquals(SyncStatus.FAILED.name, db.queueItemDao().getItemById(id1)?.syncStatus)

        clock.addAndGet(10_000L)
        queue.processPendingQueue(isNetworkAvailable = true)
        assertEquals(SyncStatus.SENT_TO_BACKEND.name, db.queueItemDao().getItemById(id1)?.syncStatus)

        val id2 = queue.enqueue("CLOUD_ALERT_DISPATCH", "{\"n\":2}", idempotencyKey = "st-2")
        val id3 = queue.enqueue("CLOUD_ALERT_DISPATCH", "{\"n\":3}", idempotencyKey = "st-3")
        queue.processPendingQueue(isNetworkAvailable = true)
        assertEquals(SyncStatus.DISPATCH_ACCEPTED.name, db.queueItemDao().getItemById(id2)?.syncStatus)
        assertEquals(SyncStatus.DELIVERY_CONFIRMED.name, db.queueItemDao().getItemById(id3)?.syncStatus)
    }

    @Test
    fun `10 trusted contact authorization rejects disabled mismatched or unauthenticated access`() = runBlocking {
        val authorizedContact = TrustedPerson(
            id = "tp-valid",
            name = "Contact Autorisé",
            phoneNumber = "+25779111222",
            canReceiveTheftAlerts = true
        )
        val disabledContact = TrustedPerson(
            id = "tp-disabled",
            name = "Contact Désactivé",
            phoneNumber = "+25779333444",
            canReceiveTheftAlerts = false
        )
        val rogueContact = TrustedPerson(
            id = "tp-rogue",
            name = "Non Enregistré",
            phoneNumber = "+25779555666",
            canReceiveTheftAlerts = true
        )

        var dispatchInvocations = 0
        val trustedBackend = object : TrustedContactDispatchBackend {
            override fun isConfigured(): Boolean = true
            override suspend fun dispatchTrustedContactAlert(
                ownerUid: String,
                deviceId: String,
                eventId: String,
                payloadJson: String
            ): BackendTransmissionOutcome {
                dispatchInvocations++
                return BackendTransmissionOutcome.DispatchAccepted("ext-$eventId")
            }
        }

        // Case A: Missing owner authentication -> rejected cleanly, 0 dispatches
        var currentOwner: String? = null
        val queueUnauth = SecurityEventQueue(
            queueItemDao = db.queueItemDao(),
            vault = vault,
            backendGateway = FirestoreSecurityEventGateway(
                context = context,
                trustedContactDispatchBackend = trustedBackend,
                firebaseInitializedProvider = { true },
                ownerUidProvider = { currentOwner },
                deviceIdProvider = { "DEV-AUTHZ" }
            )
        )
        val unauthManager = AlertNotificationManager(
            context = context,
            eventQueue = queueUnauth,
            queueItemDao = db.queueItemDao(),
            ownerUidProvider = { currentOwner },
            deviceIdProvider = { "DEV-AUTHZ" },
            authorizedContactsProvider = { listOf(authorizedContact) },
            isExternalContactBackendConfiguredProvider = { true }
        )
        val unauthStatus = unauthManager.dispatchSimChangeAlert(
            carrier = "Lumitel",
            trustedContacts = listOf(authorizedContact),
            isNetworkAvailable = true,
            processImmediately = true
        )
        assertEquals(AlertDispatchStage.FAILED, unauthStatus.trustedContactStage)
        assertEquals(0, unauthStatus.authorizedRecipientsCount)
        assertEquals(0, dispatchInvocations)

        // Case B: Authenticated owner -> disabled + rogue contacts rejected, ONLY valid authorized contact dispatched
        currentOwner = "owner-uid-999"
        val authStatus = unauthManager.dispatchSimChangeAlert(
            carrier = "Econet",
            trustedContacts = listOf(disabledContact, rogueContact, authorizedContact),
            isNetworkAvailable = true,
            processImmediately = true
        )
        assertEquals(1, authStatus.authorizedRecipientsCount)
        assertEquals(2, authStatus.rejectedRecipientsCount)
        assertEquals(1, dispatchInvocations)
        assertEquals(AlertDispatchStage.DISPATCH_ACCEPTED, authStatus.trustedContactStage)
    }

    @Test
    fun test11_noFalseAlertSentClaimAndHonestFrenchLabels(): Unit = runBlocking {
        val authorizedContact = TrustedPerson(
            id = "tp-1",
            name = "Jean",
            phoneNumber = "+25779000111",
            canReceiveTheftAlerts = true
        )
        val gateway = FirestoreSecurityEventGateway(
            context = context,
            trustedContactDispatchBackend = null,
            firebaseInitializedProvider = { true },
            ownerUidProvider = { "owner-uid-1" },
            deviceIdProvider = { "DEV-HONEST" }
        )
        val queue = SecurityEventQueue(db.queueItemDao(), vault, gateway)
        val alertManager = AlertNotificationManager(
            context = context,
            eventQueue = queue,
            queueItemDao = db.queueItemDao(),
            ownerUidProvider = { "owner-uid-1" },
            deviceIdProvider = { "DEV-HONEST" },
            authorizedContactsProvider = { listOf(authorizedContact) },
            isExternalContactBackendConfiguredProvider = { false }
        )

        // Offline SIM change must NEVER claim alert was sent
        val offlineStatus = alertManager.dispatchSimChangeAlert(
            carrier = "Lumitel-Offline",
            trustedContacts = listOf(authorizedContact),
            isNetworkAvailable = false,
            processImmediately = true
        )
        assertEquals(AlertDispatchStage.PENDING, offlineStatus.trustedContactStage)
        assertFalse(offlineStatus.lastNotificationBody.contains("Alerte envoyée aux contacts", ignoreCase = true))
        assertTrue(offlineStatus.lastNotificationBody.contains("attente", ignoreCase = true))

        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val posted = Shadows.shadowOf(notificationManager).getNotification(AlertNotificationManager.ALERT_NOTIFICATION_ID)
        val postedText = posted?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: posted?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertFalse("Notification must NEVER falsely claim alert sent", postedText.contains("Alerte envoyée aux contacts", ignoreCase = true))

        // Online with no external relay configured -> NOT_CONFIGURED and honest French UI rendering
        val unconfiguredStatus = alertManager.dispatchSimChangeAlert(
            carrier = "Lumitel-Online",
            trustedContacts = listOf(authorizedContact),
            isNetworkAvailable = true,
            processImmediately = true
        )
        assertEquals(AlertDispatchStage.NOT_CONFIGURED, unconfiguredStatus.trustedContactStage)
        assertFalse(unconfiguredStatus.lastNotificationBody.contains("Alerte envoyée aux contacts", ignoreCase = true))

        val uiState = InkingiUiState(
            currentLanguage = "fr",
            trustedPersons = listOf(authorizedContact),
            queueStatusSummary = queue.queueSummary.value,
            alertDispatchStatus = unconfiguredStatus,
            pendingQueueCount = queue.queueSummary.value.actionablePendingCount
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    uiState = uiState,
                    onSetLanguage = {},
                    onSwitchUserRole = { _, _ -> true },
                    onAddTrustedPerson = { _, _, _, _ -> true },
                    onRemoveTrustedPerson = { _, _ -> true },
                    onSimulateSimChange = {},
                    onSimulateDisplacement = {},
                    onSimulateKineticImpact = {},
                    onSimulateReboot = {},
                    onSimulateNetworkDrop = {},
                    onResetSimulations = {}
                )
            }
        }
        composeTestRule.onNode(hasScrollAction())
            .performScrollToNode(hasTestTag("settings_trusted_dispatch_readiness"))
        composeTestRule.onNodeWithTag("settings_trusted_dispatch_readiness").assertIsDisplayed()
        composeTestRule.onNodeWithText("Non configuré", substring = true).assertIsDisplayed()
    }

    @Test
    fun test12_notConfiguredStatusWhenNoDispatchBackendExists(): Unit = runBlocking {
        val gateway = FirestoreSecurityEventGateway(
            context = context,
            trustedContactDispatchBackend = null,
            firebaseInitializedProvider = { true },
            ownerUidProvider = { "owner-uid-2" },
            deviceIdProvider = { "DEV-NOCFG" }
        )
        val queue = SecurityEventQueue(db.queueItemDao(), vault, gateway)
        val eventId = queue.enqueue(
            eventType = FirestoreSecurityEventGateway.EVENT_TYPE_TRUSTED_CONTACT_SIM_ALERT,
            payload = "{\"ownerUid\":\"owner-uid-2\",\"deviceId\":\"DEV-NOCFG\",\"contactId\":\"tp-1\",\"canReceiveTheftAlerts\":true}",
            priority = QueuePriority.CRITICAL,
            idempotencyKey = "trusted-sim-nocfg"
        )

        val processedCount = queue.processPendingQueue(isNetworkAvailable = true)
        assertEquals(0, processedCount)

        val item = db.queueItemDao().getItemById(eventId)
        assertNotNull(item)
        assertEquals(SyncStatus.NOT_CONFIGURED.name, item?.syncStatus)
        assertEquals(0, item?.retryCount)
        assertEquals(1, queue.queueSummary.value.notConfiguredCount)

        // Verify TheftRecoveryScreen displays distinct queue & non-configured alert banner honestly
        composeTestRule.setContent {
            MyApplicationTheme {
                TheftRecoveryScreen(
                    uiState = InkingiUiState(
                        currentLanguage = "fr",
                        queueStatusSummary = queue.queueSummary.value
                    ),
                    onConfirmStolen = { true },
                    onActivateRecoveryMode = { true },
                    onMarkRecovered = { true },
                    onDisarm = { true },
                    onAttemptDeviceLock = { com.example.domain.management.DeviceLockManager.LockExecutionResult.Unavailable("none", enrollmentRequired = true) },
                    onTriggerSync = {}
                )
            }
        }
        composeTestRule.onNodeWithTag("theft_recovery_list")
            .performScrollToNode(hasTestTag("alert_dispatch_status_banner"))
        composeTestRule.onNodeWithTag("queue_status_summary_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("alert_dispatch_status_banner").assertIsDisplayed()
    }

    @Test
    fun `13 sensitive data PINs tokens passwords phones and emails are scrubbed from logs and errors`() = runBlocking {
        ShadowLog.clear()
        val capturedLogs = mutableListOf<String>()

        val leakyBackend = object : SecurityEventBackendGateway {
            override fun isBackendConfigured(): Boolean = true
            override suspend fun transmitEvent(item: QueueItemEntity, decryptedPayload: String): BackendTransmissionOutcome {
                return BackendTransmissionOutcome.TransientFailure(
                    "HTTP 401 token=SECRET_TOKEN_XYZ pin=4829 password=SuperPass123! contact=+25779999888 email=secret.user@example.com Bearer abc.def.ghi"
                )
            }
        }

        val queue = SecurityEventQueue(
            queueItemDao = db.queueItemDao(),
            vault = vault,
            backendGateway = leakyBackend,
            logger = { msg ->
                val safe = SecurityLogRedactor.redact(msg)
                capturedLogs.add(safe)
                android.util.Log.i("TestQueueRedaction", safe)
            }
        )

        val secretPayload = "{\"pin\":\"4829\",\"token\":\"SECRET_TOKEN_XYZ\",\"password\":\"SuperPass123!\",\"contactPhone\":\"+25779999888\",\"ownerEmail\":\"secret.user@example.com\"}"
        queue.enqueue("INCIDENT_UPDATE", secretPayload, QueuePriority.HIGH)
        queue.processPendingQueue(isNetworkAvailable = true)

        val allLoggedText = (capturedLogs + ShadowLog.getLogs().mapNotNull { it.msg } + listOfNotNull(queue.queueSummary.value.lastErrorMessage))
            .joinToString("\n")

        assertFalse("PIN must NEVER appear in logs/errors", allLoggedText.contains("4829"))
        assertFalse("Token must NEVER appear in logs/errors", allLoggedText.contains("SECRET_TOKEN_XYZ"))
        assertFalse("Password must NEVER appear in logs/errors", allLoggedText.contains("SuperPass123!"))
        assertFalse("Phone number must NEVER appear in logs/errors", allLoggedText.contains("79999888"))
        assertFalse("Email must NEVER appear in logs/errors", allLoggedText.contains("secret.user@example.com"))
        assertTrue("Redaction marker should appear cleanly", allLoggedText.contains("REDACTED"))
    }
}
