package com.example

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.example.core.brain.orchestration.SecurityBrain
import com.example.core.brain.model.OntologyDevice
import com.example.core.brain.model.OntologyOwner
import com.example.core.model.CommandStatus
import com.example.core.model.CommandType
import com.example.core.model.SecurityCommand
import com.example.core.model.SecurityState
import com.example.core.security.SecurityStateManager
import com.example.data.local.InkingiDatabase
import com.example.data.local.entity.LocationHistoryEntity
import com.example.data.local.entity.SecurityCommandEntity
import com.example.data.repository.DeviceSettingsRepository
import com.example.di.InkingiContainer
import com.example.domain.command.SecurityCommandProcessor
import com.example.domain.management.DeviceLockManager
import com.example.domain.receiver.BootCompletedReceiver
import com.example.domain.recovery.SecureRecoveryBridge
import com.example.domain.service.RecoveryMonitoringService
import com.example.domain.sync.LocationSyncWorker
import com.example.presentation.InkingiViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class RemoteCommandRecoveryBridgeTest {

    private lateinit var app: Application
    private lateinit var db: InkingiDatabase
    private lateinit var container: InkingiContainer
    private lateinit var deviceId: String
    private val ownerUid = "owner-uid-001"

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
        deviceId = DeviceSettingsRepository.getDeviceId(app)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Shadows.shadowOf(app).clearStartedServices()
    }

    @After
    fun tearDown() {
        container.secureRecoveryBridge.stopRemoteCommandListener()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        db.close()
    }

    private fun buildBridge(
        processor: SecurityCommandProcessor = container.commandProcessor,
        authUidProvider: () -> String? = { ownerUid },
        clockProvider: () -> Long = { System.currentTimeMillis() },
        statusRecorder: MutableList<Pair<CommandStatus, String>> = mutableListOf()
    ): SecureRecoveryBridge {
        return SecureRecoveryBridge(
            context = app,
            deviceId = deviceId,
            commandProcessor = processor,
            commandDao = db.securityCommandDao(),
            eventQueue = container.securityEventQueue,
            locationDao = db.locationDao(),
            authenticatedOwnerUidProvider = authUidProvider,
            activeDeviceIdProvider = { deviceId },
            clockProvider = clockProvider,
            remoteStatusUpdater = { _, _, _, status, note ->
                statusRecorder.add(status to note)
            }
        )
    }

    private fun buildValidEnvelope(
        commandId: String = "cmd-remote-1",
        commandTypeRaw: String = CommandType.ACTIVATE_STOLEN_MODE.name,
        targetDeviceId: String = deviceId,
        pathOwnerUid: String = ownerUid,
        issuedBy: String? = ownerUid,
        statusRaw: String? = CommandStatus.PENDING.name,
        now: Long = System.currentTimeMillis(),
        createdTimestamp: Long? = now - 5_000L,
        expirationTimestamp: Long? = now + 300_000L,
        payload: Any? = "",
        fieldId: String? = commandId,
        documentOwnerUid: String? = pathOwnerUid,
        ownerConfirmed: Boolean? = null
    ) = SecureRecoveryBridge.RemoteCommandEnvelope(
        documentId = commandId,
        fieldId = fieldId,
        pathOwnerUid = pathOwnerUid,
        documentOwnerUid = documentOwnerUid,
        issuedBy = issuedBy,
        targetDeviceId = targetDeviceId,
        commandTypeRaw = commandTypeRaw,
        statusRaw = statusRaw,
        createdTimestamp = createdTimestamp,
        expirationTimestamp = expirationTimestamp,
        payload = payload,
        ownerConfirmed = ownerConfirmed
    )

    @Test
    fun `remote ACTIVATE_STOLEN_MODE updates SecurityBrain StateManager ViewModel prefs DB and starts RecoveryMonitoringService`() = runBlocking {
        val viewModel = InkingiViewModel(container)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Shadows.shadowOf(app).clearStartedServices()

        val statusUpdates = mutableListOf<Pair<CommandStatus, String>>()
        val bridge = buildBridge(statusRecorder = statusUpdates)
        val envelope = buildValidEnvelope(
            commandId = "cmd-stolen-01",
            commandTypeRaw = CommandType.ACTIVATE_STOLEN_MODE.name
        )

        val result = bridge.processRemoteCommandEnvelope(envelope)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(result is SecurityCommandProcessor.CommandExecutionResult.Success)
        assertEquals(SecurityState.STOLEN_MODE, container.securityBrain.currentState.value)
        assertEquals(SecurityState.STOLEN_MODE, container.securityStateManager.currentState.value)
        assertEquals(SecurityState.STOLEN_MODE, viewModel.uiState.value.securityState)
        assertTrue(DeviceSettingsRepository.isStolenTrackingActive(app))
        assertEquals(SecurityState.STOLEN_MODE.name, DeviceSettingsRepository.getPersistedSecurityStateName(app))
        assertEquals(RecoveryMonitoringService.DEFAULT_THEFT_INTERVAL_MILLIS, container.locationEngine.activeIntervalMillis)
        assertTrue(viewModel.uiState.value.isForegroundServiceActive)

        val startedIntent = Shadows.shadowOf(app).nextStartedService
        assertNotNull("Expected RecoveryMonitoringService to be started", startedIntent)
        assertEquals(RecoveryMonitoringService::class.java.name, startedIntent?.component?.className)

        val savedCmd = db.securityCommandDao().getAllCommands().first().firstOrNull { it.commandId == "cmd-stolen-01" }
        assertNotNull(savedCmd)
        assertEquals(CommandStatus.EXECUTED.name, savedCmd?.status)
        assertEquals(CommandStatus.EXECUTED, statusUpdates.last().first)

        // Reboot simulation restarts foreground service & offline sync
        Shadows.shadowOf(app).clearStartedServices()
        BootCompletedReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        val rebootServiceIntent = Shadows.shadowOf(app).nextStartedService
        assertNotNull("BootCompletedReceiver must restart RecoveryMonitoringService when STOLEN_MODE is active", rebootServiceIntent)
        assertEquals(RecoveryMonitoringService::class.java.name, rebootServiceIntent?.component?.className)
    }

    @Test
    fun `remote ACTIVATE_RECOVERY_MODE transitions state starts service and survives restart as RECOVERY_MODE`() = runBlocking {
        val statusUpdates = mutableListOf<Pair<CommandStatus, String>>()
        val bridge = buildBridge(statusRecorder = statusUpdates)
        val envelope = buildValidEnvelope(
            commandId = "cmd-rec-01",
            commandTypeRaw = CommandType.ACTIVATE_RECOVERY_MODE.name
        )

        val result = bridge.processRemoteCommandEnvelope(envelope)
        assertTrue(result is SecurityCommandProcessor.CommandExecutionResult.Success)
        assertEquals(SecurityState.RECOVERY_MODE, container.securityBrain.currentState.value)
        assertEquals(SecurityState.RECOVERY_MODE, container.securityStateManager.currentState.value)
        assertTrue(DeviceSettingsRepository.isStolenTrackingActive(app))
        assertEquals(SecurityState.RECOVERY_MODE.name, DeviceSettingsRepository.getPersistedSecurityStateName(app))
        assertEquals(CommandStatus.EXECUTED, statusUpdates.last().first)

        val startedIntent = Shadows.shadowOf(app).nextStartedService
        assertNotNull(startedIntent)
        assertEquals(RecoveryMonitoringService::class.java.name, startedIntent?.component?.className)

        // Reboot simulation
        Shadows.shadowOf(app).clearStartedServices()
        BootCompletedReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(
            RecoveryMonitoringService::class.java.name,
            Shadows.shadowOf(app).nextStartedService?.component?.className
        )

        // Simulated process restart with fresh container + ViewModel restores RECOVERY_MODE (not STOLEN_MODE)
        val restartedContainer = InkingiContainer(app, db)
        val restartedViewModel = InkingiViewModel(restartedContainer)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(SecurityState.RECOVERY_MODE, restartedContainer.securityBrain.currentState.value)
        assertEquals(SecurityState.RECOVERY_MODE, restartedViewModel.uiState.value.securityState)
    }

    @Test
    fun `remote MARK_RECOVERED stops tracking preserves history and does not auto-restart tracking on reboot`() = runBlocking {
        // Seed a location breadcrumb to verify historical trail is preserved
        db.locationDao().insertLocation(
            LocationHistoryEntity(
                latitude = -1.9441,
                longitude = 30.0619,
                accuracy = 5f,
                altitude = 1500.0,
                speed = 0f,
                bearing = 0f,
                timestamp = System.currentTimeMillis() - 60_000L,
                isInsideTrustedZone = false,
                syncedWithBackend = false,
                provider = "gps",
                isFreshFix = true,
                batteryLevel = 80,
                deviceId = deviceId
            )
        )

        val viewModel = InkingiViewModel(container)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val statusUpdates = mutableListOf<Pair<CommandStatus, String>>()
        val bridge = buildBridge(statusRecorder = statusUpdates)

        val stolenRes = bridge.processRemoteCommandEnvelope(
            buildValidEnvelope(
                commandId = "cmd-stolen-before-recover",
                commandTypeRaw = CommandType.ACTIVATE_STOLEN_MODE.name
            )
        )
        assertTrue(stolenRes is SecurityCommandProcessor.CommandExecutionResult.Success)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Shadows.shadowOf(app).clearStartedServices()

        val recoverRes = bridge.processRemoteCommandEnvelope(
            buildValidEnvelope(
                commandId = "cmd-mark-recovered-01",
                commandTypeRaw = CommandType.MARK_RECOVERED.name
            )
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(recoverRes is SecurityCommandProcessor.CommandExecutionResult.Success)
        assertEquals(SecurityState.RECOVERED, container.securityBrain.currentState.value)
        assertEquals(SecurityState.RECOVERED, container.securityStateManager.currentState.value)
        assertEquals(SecurityState.RECOVERED, viewModel.uiState.value.securityState)
        assertFalse(DeviceSettingsRepository.isStolenTrackingActive(app))
        assertEquals(SecurityState.RECOVERED.name, DeviceSettingsRepository.getPersistedSecurityStateName(app))
        assertFalse(viewModel.uiState.value.isForegroundServiceActive)
        assertEquals(CommandStatus.EXECUTED, statusUpdates.last().first)

        // Verify breadcrumb history is strictly preserved
        val history = db.locationDao().getLocationHistory(10).first()
        assertEquals(1, history.size)
        assertEquals(-1.9441, history[0].latitude, 0.0001)

        // Verify BootCompletedReceiver does NOT restart tracking after recovery
        Shadows.shadowOf(app).clearStartedServices()
        BootCompletedReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(Shadows.shadowOf(app).nextStartedService)

        // Verify fresh ViewModel startup restores RECOVERED cleanly without activating tracking service
        val restartedContainer = InkingiContainer(app, db)
        val restartedVm = InkingiViewModel(restartedContainer)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(SecurityState.RECOVERED, restartedVm.uiState.value.securityState)
        assertFalse(restartedVm.uiState.value.isForegroundServiceActive)
        assertNull(Shadows.shadowOf(app).nextStartedService)
    }

    @Test
    fun `remote command from unauthorized or different owner UID fails closed`() = runBlocking {
        val statusUpdates = mutableListOf<Pair<CommandStatus, String>>()
        val bridge = buildBridge(
            authUidProvider = { ownerUid },
            statusRecorder = statusUpdates
        )

        val wrongOwnerEnvelope = buildValidEnvelope(
            commandId = "cmd-wrong-owner",
            pathOwnerUid = "attacker-uid-999",
            issuedBy = "attacker-uid-999"
        )
        val res1 = bridge.processRemoteCommandEnvelope(wrongOwnerEnvelope)
        assertTrue(res1 is SecurityCommandProcessor.CommandExecutionResult.Rejected)
        assertEquals(CommandStatus.REJECTED, statusUpdates.last().first)
        assertEquals(SecurityState.NORMAL, container.securityBrain.currentState.value)
        assertFalse(DeviceSettingsRepository.isStolenTrackingActive(app))
        assertNull(Shadows.shadowOf(app).nextStartedService)

        val spoofedIssuerEnvelope = buildValidEnvelope(
            commandId = "cmd-spoofed-issuer",
            pathOwnerUid = ownerUid,
            issuedBy = "attacker-uid-999"
        )
        val res2 = bridge.processRemoteCommandEnvelope(spoofedIssuerEnvelope)
        assertTrue(res2 is SecurityCommandProcessor.CommandExecutionResult.Rejected)
        assertEquals(CommandStatus.REJECTED, statusUpdates.last().first)
        assertEquals(SecurityState.NORMAL, container.securityBrain.currentState.value)
    }

    @Test
    fun `remote command with mismatched target deviceId fails closed`() = runBlocking {
        val statusUpdates = mutableListOf<Pair<CommandStatus, String>>()
        val bridge = buildBridge(statusRecorder = statusUpdates)

        val mismatchEnvelope = buildValidEnvelope(
            commandId = "cmd-wrong-device",
            targetDeviceId = "IG-OTHER-DEVICE-999"
        )
        val result = bridge.processRemoteCommandEnvelope(mismatchEnvelope)

        assertTrue(result is SecurityCommandProcessor.CommandExecutionResult.Rejected)
        assertEquals(CommandStatus.REJECTED, statusUpdates.last().first)
        assertEquals(SecurityState.NORMAL, container.securityBrain.currentState.value)
        assertEquals(SecurityState.NORMAL, container.securityStateManager.currentState.value)
        assertFalse(DeviceSettingsRepository.isStolenTrackingActive(app))
        assertNull(Shadows.shadowOf(app).nextStartedService)
    }

    @Test
    fun `remote command when signed out or missing issuedBy fails closed`() = runBlocking {
        val statusUpdates = mutableListOf<Pair<CommandStatus, String>>()
        val signedOutBridge = buildBridge(
            authUidProvider = { null },
            statusRecorder = statusUpdates
        )
        val signedOutResult = signedOutBridge.processRemoteCommandEnvelope(
            buildValidEnvelope(commandId = "cmd-signed-out")
        )
        assertTrue(signedOutResult is SecurityCommandProcessor.CommandExecutionResult.Rejected)
        assertEquals(CommandStatus.REJECTED, statusUpdates.last().first)
        assertEquals(SecurityState.NORMAL, container.securityBrain.currentState.value)

        val missingIssuerBridge = buildBridge(
            authUidProvider = { ownerUid },
            statusRecorder = statusUpdates
        )
        val missingIssuerResult = missingIssuerBridge.processRemoteCommandEnvelope(
            buildValidEnvelope(commandId = "cmd-missing-issuer", issuedBy = null)
        )
        assertTrue(missingIssuerResult is SecurityCommandProcessor.CommandExecutionResult.Rejected)
        assertEquals(CommandStatus.REJECTED, statusUpdates.last().first)
    }

    @Test
    fun `expired remote command returns EXPIRED and performs no state change`() = runBlocking {
        val fixedNow = 1_700_000_500_000L
        val statusUpdates = mutableListOf<Pair<CommandStatus, String>>()
        val bridge = buildBridge(
            clockProvider = { fixedNow },
            statusRecorder = statusUpdates
        )

        val expiredEnvelope = buildValidEnvelope(
            commandId = "cmd-expired-01",
            createdTimestamp = fixedNow - 700_000L,
            expirationTimestamp = fixedNow - 10_000L
        )
        val result = bridge.processRemoteCommandEnvelope(expiredEnvelope)

        assertTrue(result is SecurityCommandProcessor.CommandExecutionResult.Rejected)
        assertEquals(CommandStatus.EXPIRED, statusUpdates.last().first)
        assertEquals(SecurityState.NORMAL, container.securityBrain.currentState.value)
        assertFalse(DeviceSettingsRepository.isStolenTrackingActive(app))
        assertNull(Shadows.shadowOf(app).nextStartedService)
    }

    @Test
    fun `duplicate delivery of same commandId is idempotent and does not re-execute`() = runBlocking {
        var serviceStartCalls = 0
        val brain = createIsolatedBrain()
        val stateManager = SecurityStateManager(db.securityEventDao(), db.auditEventDao()) { deviceId }
        val processor = SecurityCommandProcessor(
            expectedDeviceId = deviceId,
            lockManager = DeviceLockManager(app),
            stateManager = stateManager,
            commandDao = db.securityCommandDao(),
            securityBrain = brain,
            expectedOwnerProvider = { ownerUid },
            persistTrackingState = { active, state ->
                DeviceSettingsRepository.setStolenTrackingActiveCommitted(app, active, state)
            },
            startRecoveryService = {
                serviceStartCalls++
                true
            }
        )
        val statusUpdates = mutableListOf<Pair<CommandStatus, String>>()
        val bridge = buildBridge(processor = processor, statusRecorder = statusUpdates)
        val envelope = buildValidEnvelope(commandId = "cmd-dup-01")

        val firstRes = bridge.processRemoteCommandEnvelope(envelope)
        val secondRes = bridge.processRemoteCommandEnvelope(envelope)

        assertTrue(firstRes is SecurityCommandProcessor.CommandExecutionResult.Success)
        assertTrue(secondRes is SecurityCommandProcessor.CommandExecutionResult.AlreadyProcessed)
        assertEquals(1, serviceStartCalls)
        assertEquals(
            CommandStatus.EXECUTED,
            db.securityCommandDao().getAllCommands().first().first { it.commandId == "cmd-dup-01" }.status.let(CommandStatus::valueOf)
        )
        assertEquals(CommandStatus.EXECUTED, statusUpdates.last().first)
    }

    @Test
    fun `replay reusing commandId with altered payload or commandType is rejected`() = runBlocking {
        val statusUpdates = mutableListOf<Pair<CommandStatus, String>>()
        val bridge = buildBridge(statusRecorder = statusUpdates)

        val original = buildValidEnvelope(
            commandId = "cmd-replay-01",
            commandTypeRaw = CommandType.ACTIVATE_STOLEN_MODE.name,
            payload = "reason=lost"
        )
        val firstRes = bridge.processRemoteCommandEnvelope(original)
        assertTrue(firstRes is SecurityCommandProcessor.CommandExecutionResult.Success)

        val alteredTypeReplay = buildValidEnvelope(
            commandId = "cmd-replay-01",
            commandTypeRaw = CommandType.MARK_RECOVERED.name,
            payload = "reason=lost"
        )
        val replayRes = bridge.processRemoteCommandEnvelope(alteredTypeReplay)

        assertTrue(replayRes is SecurityCommandProcessor.CommandExecutionResult.Rejected)
        assertEquals(SecurityState.STOLEN_MODE, container.securityBrain.currentState.value)
        assertTrue(DeviceSettingsRepository.isStolenTrackingActive(app))
        assertEquals(
            CommandStatus.EXECUTED.name,
            db.securityCommandDao().getAllCommands().first().first { it.commandId == "cmd-replay-01" }.status
        )
    }

    @Test
    fun `persistence failure aborts ACTIVATE_STOLEN_MODE cleanly and marks command FAILED`() = runBlocking {
        var serviceStartCalled = false
        val brain = createIsolatedBrain()
        val stateManager = SecurityStateManager(db.securityEventDao(), db.auditEventDao()) { deviceId }
        val processor = SecurityCommandProcessor(
            expectedDeviceId = deviceId,
            lockManager = DeviceLockManager(app),
            stateManager = stateManager,
            commandDao = db.securityCommandDao(),
            securityBrain = brain,
            expectedOwnerProvider = { ownerUid },
            persistTrackingState = { _, _ -> false }, // Simulate persistence failure
            startRecoveryService = {
                serviceStartCalled = true
                true
            }
        )
        val statusUpdates = mutableListOf<Pair<CommandStatus, String>>()
        val bridge = buildBridge(processor = processor, statusRecorder = statusUpdates)

        val res = bridge.processRemoteCommandEnvelope(
            buildValidEnvelope(commandId = "cmd-persist-fail")
        )

        assertTrue(res is SecurityCommandProcessor.CommandExecutionResult.Failed)
        assertEquals(CommandStatus.FAILED, statusUpdates.last().first)
        assertEquals(SecurityState.NORMAL, brain.currentState.value)
        assertEquals(SecurityState.NORMAL, stateManager.currentState.value)
        assertFalse(serviceStartCalled)
        assertFalse(DeviceSettingsRepository.isStolenTrackingActive(app))
        assertEquals(
            CommandStatus.FAILED.name,
            db.securityCommandDao().getAllCommands().first().first { it.commandId == "cmd-persist-fail" }.status
        )
    }

    @Test
    fun `persistence failure on MARK_RECOVERED keeps tracking active and marks FAILED`() = runBlocking {
        val brain = createIsolatedBrain()
        val stateManager = SecurityStateManager(db.securityEventDao(), db.auditEventDao()) { deviceId }
        var shouldFailRecoverPersist = false
        var serviceStopped = false
        val processor = SecurityCommandProcessor(
            expectedDeviceId = deviceId,
            lockManager = DeviceLockManager(app),
            stateManager = stateManager,
            commandDao = db.securityCommandDao(),
            securityBrain = brain,
            expectedOwnerProvider = { ownerUid },
            persistTrackingState = { active, state ->
                if (!active && shouldFailRecoverPersist) false
                else DeviceSettingsRepository.setStolenTrackingActiveCommitted(app, active, state)
            },
            startRecoveryService = { true },
            stopRecoveryService = {
                serviceStopped = true
                true
            }
        )
        val statusUpdates = mutableListOf<Pair<CommandStatus, String>>()
        val bridge = buildBridge(processor = processor, statusRecorder = statusUpdates)

        assertTrue(
            bridge.processRemoteCommandEnvelope(buildValidEnvelope(commandId = "cmd-setup-stolen"))
                is SecurityCommandProcessor.CommandExecutionResult.Success
        )

        shouldFailRecoverPersist = true
        val recoverFail = bridge.processRemoteCommandEnvelope(
            buildValidEnvelope(
                commandId = "cmd-recover-persist-fail",
                commandTypeRaw = CommandType.MARK_RECOVERED.name
            )
        )

        assertTrue(recoverFail is SecurityCommandProcessor.CommandExecutionResult.Failed)
        assertEquals(CommandStatus.FAILED, statusUpdates.last().first)
        assertEquals(SecurityState.STOLEN_MODE, brain.currentState.value)
        assertEquals(SecurityState.STOLEN_MODE, stateManager.currentState.value)
        assertTrue(DeviceSettingsRepository.isStolenTrackingActive(app))
        assertFalse("Tracking service must NOT be stopped when recovery persistence fails", serviceStopped)
    }

    @Test
    fun `service start blocked due to missing permission or OS restriction marks PENDING_SERVICE_ACTIVATION`() = runBlocking {
        // Revoke location permission so real RecoveryMonitoringService.startService returns false
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)

        val viewModel = InkingiViewModel(container)
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        val statusUpdates = mutableListOf<Pair<CommandStatus, String>>()
        val bridge = buildBridge(statusRecorder = statusUpdates)

        val res = bridge.processRemoteCommandEnvelope(
            buildValidEnvelope(commandId = "cmd-perm-denied")
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(res is SecurityCommandProcessor.CommandExecutionResult.PendingServiceActivation)
        assertEquals(CommandStatus.PENDING_SERVICE_ACTIVATION, statusUpdates.last().first)
        assertEquals(SecurityState.STOLEN_MODE, container.securityBrain.currentState.value)
        assertTrue(DeviceSettingsRepository.isStolenTrackingActive(app))
        assertFalse("UI state must truthfully reflect service not running", viewModel.uiState.value.isForegroundServiceActive)
        assertNull(Shadows.shadowOf(app).nextStartedService)

        // WorkManager LocationSyncWorker fallback should be enqueued
        val workInfos = WorkManager.getInstance(app)
            .getWorkInfosForUniqueWork(LocationSyncWorker.UNIQUE_WORK_NAME)
            .get()
        assertTrue(workInfos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.BLOCKED })
    }

    @Test
    fun `malformed tampered or non-PENDING remote command envelopes fail closed`() = runBlocking {
        val statusUpdates = mutableListOf<Pair<CommandStatus, String>>()
        val bridge = buildBridge(statusRecorder = statusUpdates)
        val now = System.currentTimeMillis()

        // 1. Already EXECUTED doc should not re-run
        val nonPending = bridge.processRemoteCommandEnvelope(
            buildValidEnvelope(commandId = "cmd-nonpending", statusRaw = CommandStatus.EXECUTED.name)
        )
        assertTrue(nonPending is SecurityCommandProcessor.CommandExecutionResult.AlreadyProcessed)

        // 2. Mismatched field id vs documentId
        val idMismatch = bridge.processRemoteCommandEnvelope(
            buildValidEnvelope(commandId = "doc-id-1", fieldId = "different-id-2")
        )
        assertTrue(idMismatch is SecurityCommandProcessor.CommandExecutionResult.Rejected)

        // 3. Oversized payload
        val oversized = bridge.processRemoteCommandEnvelope(
            buildValidEnvelope(commandId = "cmd-oversized", payload = "x".repeat(600))
        )
        assertTrue(oversized is SecurityCommandProcessor.CommandExecutionResult.Rejected)

        // 4. Future timestamp beyond allowed skew
        val futureSkew = bridge.processRemoteCommandEnvelope(
            buildValidEnvelope(
                commandId = "cmd-future",
                now = now,
                createdTimestamp = now + 900_000L,
                expirationTimestamp = now + 1_200_000L
            )
        )
        assertTrue(futureSkew is SecurityCommandProcessor.CommandExecutionResult.Rejected)

        // 5. ownerConfirmed = false for MARK_RECOVERED
        val unconfirmed = bridge.processRemoteCommandEnvelope(
            buildValidEnvelope(
                commandId = "cmd-unconfirmed-recover",
                commandTypeRaw = CommandType.MARK_RECOVERED.name,
                ownerConfirmed = false
            )
        )
        assertTrue(unconfirmed is SecurityCommandProcessor.CommandExecutionResult.Rejected)

        // 6. Unknown command type
        val unknownType = bridge.processRemoteCommandEnvelope(
            buildValidEnvelope(
                commandId = "cmd-unknown-type",
                commandTypeRaw = "WIPE_ALL_USER_DATA"
            )
        )
        assertTrue(unknownType is SecurityCommandProcessor.CommandExecutionResult.Unsupported)
        assertEquals(CommandStatus.UNSUPPORTED_ON_DEVICE, statusUpdates.last().first)

        assertEquals(SecurityState.NORMAL, container.securityBrain.currentState.value)
        assertFalse(DeviceSettingsRepository.isStolenTrackingActive(app))
    }

    @Test
    fun `concurrent STOLEN_MODE and MARK_RECOVERED commands serialize cleanly without state corruption`() = runBlocking {
        val statusUpdates = mutableListOf<Pair<CommandStatus, String>>()
        val bridge = buildBridge(statusRecorder = statusUpdates)

        val job1 = async {
            bridge.processRemoteCommandEnvelope(
                buildValidEnvelope(
                    commandId = "cmd-concurrent-1",
                    commandTypeRaw = CommandType.ACTIVATE_STOLEN_MODE.name
                )
            )
        }
        val job2 = async {
            bridge.processRemoteCommandEnvelope(
                buildValidEnvelope(
                    commandId = "cmd-concurrent-2",
                    commandTypeRaw = CommandType.MARK_RECOVERED.name
                )
            )
        }

        val results = awaitAll(job1, job2)
        assertTrue(results.all { it is SecurityCommandProcessor.CommandExecutionResult.Success })

        val brainState = container.securityBrain.currentState.value
        val managerState = container.securityStateManager.currentState.value
        val persistedState = DeviceSettingsRepository.getPersistedSecurityStateName(app)
        val persistedTracking = DeviceSettingsRepository.isStolenTrackingActive(app)

        // Brain, StateManager, and persisted preferences must strictly agree (never torn/split)
        assertEquals(brainState, managerState)
        assertEquals(brainState.name, persistedState)
        assertEquals(brainState == SecurityState.STOLEN_MODE, persistedTracking)
    }

    private fun createIsolatedBrain(): SecurityBrain = SecurityBrain(
        device = OntologyDevice(
            deviceId = deviceId,
            ownerId = ownerUid,
            name = "TestDevice",
            model = "Pixel 8 Pro",
            osVersion = "Android 14"
        ),
        owner = OntologyOwner(ownerId = ownerUid, email = "owner@test.com", displayName = "Owner")
    )
}
