package com.example

import android.Manifest
import android.app.Application
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.brain.model.EventCategory
import com.example.core.brain.model.NormalizedSecurityEvent
import com.example.core.brain.risk.ConfigurableRiskScoringEngine
import com.example.core.model.EventSeverity
import com.example.core.model.SecurityState
import com.example.data.local.InkingiDatabase
import com.example.data.repository.DeviceSettingsRepository
import com.example.di.InkingiContainer
import com.example.domain.management.DeviceLockManager
import com.example.domain.receiver.BootCompletedReceiver
import com.example.domain.service.RecoveryMonitoringService
import com.example.presentation.InkingiUiState
import com.example.presentation.InkingiViewModel
import com.example.presentation.theft.TheftRecoveryScreen
import com.example.ui.theme.MyApplicationTheme
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class TaskARecoveryFixTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: InkingiDatabase
    private val viewModelStores = mutableListOf<androidx.lifecycle.ViewModelStore>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(DeviceSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("inkingi_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(com.example.domain.security.OfflineSecurityVault.VAULT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, InkingiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        viewModelStores.forEach { it.clear() }
        viewModelStores.clear()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
        db.close()
    }

    private fun createManagedViewModel(container: InkingiContainer): InkingiViewModel {
        val store = androidx.lifecycle.ViewModelStore()
        viewModelStores.add(store)
        val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
                return InkingiViewModel(container) as T
            }
        }
        return androidx.lifecycle.ViewModelProvider(store, factory)[InkingiViewModel::class.java]
    }

    @Test
    fun `test lock confirmation uses read-only verifyMasterPin and never calls onDisarm`() {
        var disarmCalled = false
        var verifyCalled = false
        var attemptLockCalled = false

        composeTestRule.setContent {
            MyApplicationTheme(language = "en") {
                TheftRecoveryScreen(
                    uiState = InkingiUiState(
                        securityState = SecurityState.SUSPECTED_LOST,
                        currentLanguage = "en"
                    ),
                    onConfirmStolen = { false },
                    onActivateRecoveryMode = { false },
                    onMarkRecovered = { false },
                    onDisarm = {
                        disarmCalled = true
                        true
                    },
                    onVerifyMasterPin = { pin ->
                        verifyCalled = true
                        pin == "8492"
                    },
                    onAttemptDeviceLock = {
                        attemptLockCalled = true
                        DeviceLockManager.LockExecutionResult.Unavailable("Admin inactive", true)
                    },
                    onTriggerSync = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("theft_recovery_list")
            .performScrollToNode(hasTestTag("trigger_hardware_lock_button"))
        composeTestRule.onNodeWithTag("trigger_hardware_lock_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("pin_auth_input").performTextInput("8492")
        composeTestRule.onNodeWithTag("confirm_pin_execute_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue("Expected read-only PIN verification to be invoked", verifyCalled)
        assertTrue("Expected hardware lock test to execute after valid PIN", attemptLockCalled)
        assertFalse("Hardware lock test must NEVER invoke onDisarm", disarmCalled)
    }

    @Test
    fun `camera opt-in toggle and hardcoded fallback number are absent from TheftRecoveryScreen`() {
        composeTestRule.setContent {
            MyApplicationTheme(language = "en") {
                TheftRecoveryScreen(
                    uiState = InkingiUiState(currentLanguage = "en"),
                    onConfirmStolen = { false },
                    onActivateRecoveryMode = { false },
                    onMarkRecovered = { false },
                    onDisarm = { false },
                    onVerifyMasterPin = { false },
                    onAttemptDeviceLock = {
                        DeviceLockManager.LockExecutionResult.Unavailable("Admin inactive", true)
                    },
                    onTriggerSync = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("theft_recovery_list")
            .performScrollToNode(hasTestTag("lock_screen_phone_input"))
        composeTestRule.waitForIdle()

        assertTrue(
            "Camera evidence toggle must not exist in TheftRecoveryScreen",
            composeTestRule.onAllNodesWithTag("camera_evidence_consent_switch").fetchSemanticsNodes().isEmpty()
        )
        val node = composeTestRule.onNodeWithTag("lock_screen_phone_input").fetchSemanticsNode()
        val text = node.config.getOrElse(androidx.compose.ui.semantics.SemanticsProperties.EditableText) {
            androidx.compose.ui.text.AnnotatedString("")
        }.text
        assertFalse("Hardcoded +250788000000 must not appear", text.contains("250788000000"))
    }

    @Test
    fun `verifyMasterPin preserves active WARNING DANGER and SUSPECTED_LOST states`() = runBlocking {
        val container = InkingiContainer(context, db)
        val viewModel = createManagedViewModel(container)
        assertTrue(viewModel.enrollMasterPin("8492"))
        // Let initial Room seeding, MainLooper collectors, and ViewModel telemetry debounce complete before setting explicit states
        org.robolectric.shadows.ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        kotlinx.coroutines.delay(500)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)
        kotlinx.coroutines.delay(500)
        org.robolectric.shadows.ShadowLooper.idleMainLooper(500, java.util.concurrent.TimeUnit.MILLISECONDS)

        val statesAndScores = listOf(
            SecurityState.WARNING to ConfigurableRiskScoringEngine.RawSignals(
                isSimChanged = true // 30 -> WARNING
            ),
            SecurityState.DANGER to ConfigurableRiskScoringEngine.RawSignals(
                isSimChanged = true,
                isOutsideTrustedZone = true // 50 -> DANGER
            ),
            SecurityState.SUSPECTED_LOST to ConfigurableRiskScoringEngine.RawSignals(
                isSimChanged = true,
                isOutsideTrustedZone = true,
                failedAuthAttempts = 3 // 90 -> SUSPECTED_LOST
            )
        )

        for ((expectedState, signals) in statesAndScores) {
            container.securityBrain.ingestSecurityEvent(
                event = NormalizedSecurityEvent(
                    eventId = "evt-${expectedState.name}",
                    deviceId = container.deviceSettingsRepository.deviceProfile.value.deviceId,
                    ownerId = "owner@test.com",
                    category = EventCategory.SIM_TELEPHONY,
                    source = "Test",
                    severity = EventSeverity.CRITICAL,
                    baseRiskImpact = 40,
                    title = "Signal ${expectedState.name}",
                    details = "Details"
                ),
                signals = signals
            )

            assertEquals(expectedState, container.securityBrain.currentState.value)
            val activeIncidentBefore = container.securityBrain.activeIncident.value
            assertNotNull("Active incident must exist in $expectedState", activeIncidentBefore)

            // Valid PIN check (as triggered by TEST_LOCK_CONFIRM)
            val verified = viewModel.verifyMasterPin("8492")
            assertTrue(verified)

            assertEquals(
                "verifyMasterPin must preserve $expectedState",
                expectedState,
                container.securityBrain.currentState.value
            )
            assertEquals(
                "verifyMasterPin must preserve active incident in $expectedState",
                activeIncidentBefore?.incidentId,
                container.securityBrain.activeIncident.value?.incidentId
            )
        }
    }

    @Test
    fun `recovery contact and lock screen message persist across restarts and handle invalid input safely`() {
        val container1 = InkingiContainer(context, db)
        val viewModel1 = createManagedViewModel(container1)

        // Blank message rejected safely
        val emptyMsgError = viewModel1.configureLockScreenMessage("   ", "+250791234567")
        assertTrue(emptyMsgError.startsWith("Erreur") || emptyMsgError.startsWith("Error"))
        assertEquals("", DeviceSettingsRepository.getSavedLockScreenMessage(context))

        // Invalid phone rejected safely
        val invalidPhoneError = viewModel1.configureLockScreenMessage("Please return device", "not-a-phone!")
        assertTrue(invalidPhoneError.startsWith("Erreur") || invalidPhoneError.startsWith("Error"))
        assertEquals("", DeviceSettingsRepository.getSavedRecoveryContact(context))

        // Valid config persists
        val okMsg = viewModel1.configureLockScreenMessage("Please return to reception", "+250791234567")
        assertTrue(okMsg.contains("Message"))

        // Re-create repository and ViewModel to simulate restart
        val container2 = InkingiContainer(context, db)
        val viewModel2 = createManagedViewModel(container2)
        assertEquals("+250791234567", viewModel2.uiState.value.recoveryContactPhone)
        assertEquals("Please return to reception", viewModel2.uiState.value.lockScreenRecoveryMessage)
    }

    @Test
    fun `RecoveryMonitoringService and BootCompletedReceiver use saved config and never invent fallback number`() {
        val shadowApp = Shadows.shadowOf(context.applicationContext as Application)
        shadowApp.grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)

        // 1. Missing configuration: service starts cleanly with no invented phone number
        val intentNoConfig = Intent(context, RecoveryMonitoringService::class.java)
        val controllerNoConfig = Robolectric.buildService(RecoveryMonitoringService::class.java, intentNoConfig).create()
        controllerNoConfig.startCommand(0, 1)

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val shadowNm = Shadows.shadowOf(notificationManager)
        val notifNoConfig = shadowNm.getNotification(RecoveryMonitoringService.NOTIFICATION_ID)
        assertNotNull(notifNoConfig)
        val textNoConfig = notifNoConfig.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertFalse("Service notification must not contain +250788000000", textNoConfig.contains("250788000000"))

        controllerNoConfig.destroy()

        // 2. Saved configuration: service & BootCompletedReceiver read owner's saved contact and message
        val repo = DeviceSettingsRepository(context)
        assertTrue(repo.saveLockScreenRecoveryConfig("Call owner immediately", "+250795551234"))
        context.getSharedPreferences("inkingi_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("is_stolen_active", true).commit()

        val receiver = BootCompletedReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        val startedServiceIntent = shadowApp.nextStartedService
        assertNotNull("BootCompletedReceiver should start RecoveryMonitoringService when stolen mode is active", startedServiceIntent)
        assertEquals("+250795551234", startedServiceIntent.getStringExtra(RecoveryMonitoringService.EXTRA_CONTACT_NUMBER))

        val controllerWithConfig = Robolectric.buildService(
            RecoveryMonitoringService::class.java,
            Intent(context, RecoveryMonitoringService::class.java)
        ).create()
        controllerWithConfig.startCommand(0, 2)
        val notifWithConfig = shadowNm.getNotification(RecoveryMonitoringService.NOTIFICATION_ID)
        val textWithConfig = notifWithConfig.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        assertTrue("Service notification must include saved owner contact", textWithConfig.contains("+250795551234"))

        controllerWithConfig.destroy()
    }
}
