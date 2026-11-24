package com.example

import android.content.Context
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.DeviceProfile
import com.example.core.model.SecurityEvent
import com.example.data.local.InkingiDatabase
import com.example.data.repository.DeviceSettingsRepository
import com.example.di.InkingiContainer
import com.example.domain.security.AndroidKeyStoreKeyProvider
import com.example.domain.security.OfflineSecurityVault
import com.example.domain.security.VaultKeyProvider
import com.example.presentation.InkingiUiState
import com.example.presentation.InkingiViewModel
import com.example.presentation.auth.OnboardingAuthDialog
import com.example.presentation.settings.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.security.MessageDigest
import java.security.Security
import java.util.concurrent.TimeUnit
import javax.crypto.SecretKey

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineVaultPinHardeningTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: InkingiDatabase
    private val viewModelStores = mutableListOf<androidx.lifecycle.ViewModelStore>()

    private class FailingKeyProvider(
        private val throwOnGet: Boolean = false,
        override var lastError: String? = "Simulated Keystore outage"
    ) : VaultKeyProvider {
        override fun getOrCreateSecretKey(alias: String): SecretKey? {
            if (throwOnGet) throw IllegalStateException("Keystore hardware locked")
            return null
        }
    }

    private class ToggleableKeyProvider(
        private val delegate: VaultKeyProvider = AndroidKeyStoreKeyProvider()
    ) : VaultKeyProvider {
        var shouldFail: Boolean = false
        override val lastError: String?
            get() = if (shouldFail) "Simulated Keystore failure" else delegate.lastError

        override fun getOrCreateSecretKey(alias: String): SecretKey? {
            return if (shouldFail) null else delegate.getOrCreateSecretKey(alias)
        }
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(DeviceSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(DeviceSettingsRepository.LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(OfflineSecurityVault.VAULT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        db = Room.inMemoryDatabaseBuilder(context, InkingiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        viewModelStores.forEach { it.clear() }
        viewModelStores.clear()
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
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
    fun freshInstallHasNoDefaultPinOrHardcodedOwnerEmailAndRejects1234() {
        val repo = DeviceSettingsRepository(context)
        val profile = repo.deviceProfile.value

        assertFalse("Fresh install must not have PIN configured", repo.isPinConfigured.value)
        assertFalse("Fresh install must not be registered", profile.isRegistered)
        assertEquals("Owner email must default to empty string", "", profile.ownerEmail)
        assertEquals("Profile PIN marker must be blank before enrollment", "", profile.masterPinHash)

        assertFalse("Default PIN 1234 must never verify on fresh install", repo.verifyPin("1234"))
        assertTrue(
            "Detailed verify must return NotConfigured",
            repo.verifyPinDetailed("1234") is OfflineSecurityVault.PinVerificationResult.NotConfigured
        )
    }

    @Test
    fun legacyStaticSaltedSha256PinHashIsPurgedAndNeverAccepted() {
        // Seed old vulnerable hash: SHA-256("inkingi_salt_1234") in pin_hash
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("inkingi_salt_1234".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        val legacyPrefs = context.getSharedPreferences(DeviceSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        legacyPrefs.edit()
            .putString("pin_hash", digest)
            .putBoolean("is_registered", true)
            .commit()
        assertTrue(legacyPrefs.contains("pin_hash"))

        val repo = DeviceSettingsRepository(context)
        assertFalse("Legacy static-salted SHA-256 pin_hash must be purged on init", legacyPrefs.contains("pin_hash"))
        assertFalse("Purged legacy hash must not configure vault PIN", repo.isPinConfigured.value)
        assertFalse("Default 1234 must never be accepted via legacy hash", repo.verifyPin("1234"))
    }

    @Test
    fun weakSequentialRepeatedAndMalformedPinsAreRejectedOnEnrollmentAndRegistration() {
        val vault = OfflineSecurityVault(context)
        val repo = DeviceSettingsRepository(context, vault)

        val weakOrInvalidPins = listOf("1234", "0000", "1111", "4321", "123456", "654321", "123", "12ab", "", "99999")
        for (badPin in weakOrInvalidPins) {
            val enrollRes = vault.enrollMasterPin(badPin)
            assertTrue(
                "PIN '$badPin' must be rejected at enrollment",
                enrollRes is OfflineSecurityVault.PinEnrollmentResult.Rejected
            )
            assertFalse(
                "registerDevice must fail closed for weak/invalid PIN '$badPin'",
                repo.registerDevice("TestNode", "owner@example.com", badPin, biometric = true)
            )
            assertFalse(repo.isPinConfigured.value)
            assertFalse(repo.deviceProfile.value.isRegistered)
        }
    }

    @Test
    fun pbkdf2UsesRandomPerEnrollmentSaltHighIterationCountAndAesGcmVaultStorage() {
        val vault = OfflineSecurityVault(context)
        val vaultPrefs = context.getSharedPreferences(OfflineSecurityVault.VAULT_PREFS_NAME, Context.MODE_PRIVATE)

        val enroll1 = vault.enrollMasterPin("8492")
        assertTrue(enroll1 is OfflineSecurityVault.PinEnrollmentResult.Success)

        val storedCipher1 = vaultPrefs.getString(OfflineSecurityVault.KEY_MASTER_PIN_RECORD, null)
        assertNotNull(storedCipher1)
        assertFalse("Stored vault record must not contain raw PIN", storedCipher1!!.contains("8492"))
        assertFalse(
            "Stored vault record must not be plaintext Base64 JSON",
            String(Base64.decode(storedCipher1, Base64.NO_WRAP), Charsets.UTF_8).contains("hashBase64")
        )

        val decrypted1 = vault.retrieveAndDecrypt(OfflineSecurityVault.KEY_MASTER_PIN_RECORD)!!
        val json1 = JSONObject(decrypted1)
        assertEquals(OfflineSecurityVault.PBKDF2_ALGORITHM, json1.getString("algorithm"))
        assertTrue(json1.getInt("iterations") >= OfflineSecurityVault.MIN_PBKDF2_ITERATIONS)
        val salt1 = json1.getString("saltBase64")
        val hash1 = json1.getString("hashBase64")

        // Re-enroll the same PIN -> random salt & distinct AES-GCM IV produce different salt/hash/ciphertext
        val enroll2 = vault.enrollMasterPin("8492")
        assertTrue(enroll2 is OfflineSecurityVault.PinEnrollmentResult.Success)

        val storedCipher2 = vaultPrefs.getString(OfflineSecurityVault.KEY_MASTER_PIN_RECORD, null)!!
        val decrypted2 = vault.retrieveAndDecrypt(OfflineSecurityVault.KEY_MASTER_PIN_RECORD)!!
        val json2 = JSONObject(decrypted2)

        assertNotEquals("Per-enrollment random salt must differ", salt1, json2.getString("saltBase64"))
        assertNotEquals("Salted PBKDF2 derived hash must differ", hash1, json2.getString("hashBase64"))
        assertNotEquals("AES-GCM IV/ciphertext must differ", storedCipher1, storedCipher2)

        assertTrue(vault.verifyMasterPin("8492"))
        assertFalse(vault.verifyMasterPin("1234"))
        assertFalse(vault.verifyMasterPin("8493"))
    }

    @Test
    fun keystoreFailureNeverFallsBackToPlaintextOrBase64AndFailsClosed() {
        val failingNullVault = OfflineSecurityVault(context, FailingKeyProvider(throwOnGet = false))
        assertFalse(failingNullVault.encryptAndStore("secret_key", "top_secret_value"))
        assertNull(failingNullVault.encryptValue("top_secret_value"))
        assertFalse(
            "Vault SharedPreferences must stay empty on Keystore failure (no Base64 fallback)",
            context.getSharedPreferences(OfflineSecurityVault.VAULT_PREFS_NAME, Context.MODE_PRIVATE)
                .contains("secret_key")
        )
        assertTrue(
            failingNullVault.enrollMasterPin("8492") is OfflineSecurityVault.PinEnrollmentResult.VaultUnavailable
        )

        val repoWithFailingVault = DeviceSettingsRepository(context, failingNullVault)
        assertFalse(repoWithFailingVault.registerDevice("Pixel", "user@example.com", "8492", biometric = false))
        assertFalse(repoWithFailingVault.deviceProfile.value.isRegistered)

        val throwingVault = OfflineSecurityVault(context, FailingKeyProvider(throwOnGet = true))
        assertFalse(throwingVault.encryptAndStore("secret_key_2", "sensitive"))
        assertNull(throwingVault.encryptValue("sensitive"))
        assertTrue(
            throwingVault.enrollMasterPin("7391") is OfflineSecurityVault.PinEnrollmentResult.VaultUnavailable
        )
    }

    @Test
    fun tamperedOrPlaintextBase64PayloadFailsDecryptionClosed() {
        val vault = OfflineSecurityVault(context)
        assertTrue(vault.enrollMasterPin("8492") is OfflineSecurityVault.PinEnrollmentResult.Success)

        val vaultPrefs = context.getSharedPreferences(OfflineSecurityVault.VAULT_PREFS_NAME, Context.MODE_PRIVATE)
        val originalPayload = vaultPrefs.getString(OfflineSecurityVault.KEY_MASTER_PIN_RECORD, null)!!

        // Flip last byte of AES-GCM tag -> AEADBadTagException -> fail closed
        val raw = Base64.decode(originalPayload, Base64.NO_WRAP)
        raw[raw.lastIndex] = (raw[raw.lastIndex].toInt() xor 0xFF).toByte()
        vaultPrefs.edit().putString(
            OfflineSecurityVault.KEY_MASTER_PIN_RECORD,
            Base64.encodeToString(raw, Base64.NO_WRAP)
        ).commit()

        assertNull("Tampered GCM ciphertext must fail decryption", vault.retrieveAndDecrypt(OfflineSecurityVault.KEY_MASTER_PIN_RECORD))
        assertTrue(
            "Verification on tampered ciphertext must fail closed as VaultUnavailable",
            vault.verifyMasterPinDetailed("8492") is OfflineSecurityVault.PinVerificationResult.VaultUnavailable
        )

        // Inject unencrypted Base64 JSON -> must never be accepted by decryptValue
        val legacyBase64Plaintext = Base64.encodeToString("{\"pin\":\"1234\"}".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        assertNull("Plaintext Base64 must fail closed", vault.decryptValue(legacyBase64Plaintext))
    }

    @Test
    fun viewModelFailsClosedAndRecordsCriticalEventWhenKeystoreFailsAfterEnrollment() = runBlocking {
        val toggleProvider = ToggleableKeyProvider()
        val vault = OfflineSecurityVault(context, toggleProvider)
        assertTrue(vault.enrollMasterPin("8492") is OfflineSecurityVault.PinEnrollmentResult.Success)

        // Simulate Keystore lock/invalidation after enrollment
        toggleProvider.shouldFail = true

        val repo = DeviceSettingsRepository(context, vault)
        val container = InkingiContainer(context, db)
        // Verify direct verification fails closed and reports VaultUnavailable
        val verifyRes = repo.verifyPinDetailed("8492")
        assertTrue(verifyRes is OfflineSecurityVault.PinVerificationResult.VaultUnavailable)

        // Also test ViewModel health & critical event recording using failing provider via repo reflection-free path:
        // Enroll via container's vault, corrupt stored ciphertext to trigger VaultUnavailable through ViewModel
        val vm = createManagedViewModel(container)
        assertTrue(vm.enrollMasterPin("7391"))
        val vaultPrefs = context.getSharedPreferences(OfflineSecurityVault.VAULT_PREFS_NAME, Context.MODE_PRIVATE)
        val corrupted = "AAAA"
        vaultPrefs.edit().putString(OfflineSecurityVault.KEY_MASTER_PIN_RECORD, corrupted).commit()

        assertFalse("Corrupted Keystore payload must fail closed", vm.verifyMasterPin("7391"))
        repeat(10) {
            delay(30)
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)
        }
        assertFalse(vm.uiState.value.isVaultKeystoreHealthy)
        assertNotNull(vm.uiState.value.pinActionError)
        val events = container.securityRepository.allSecurityEvents.first()
        assertTrue(
            "Expected critical Vault Keystore Verification Failure event",
            events.any { it.title == "Vault Keystore Verification Failure" }
        )
    }

    @Test
    fun fiveFailedAttemptsTriggerLockoutAndPreventImmediateCorrectPinUntilExpiry() {
        val vault = OfflineSecurityVault(context)
        assertTrue(vault.enrollMasterPin("8492") is OfflineSecurityVault.PinEnrollmentResult.Success)

        val t0 = 1_700_000_000_000L
        for (attempt in 1..4) {
            val res = vault.verifyMasterPinDetailed("7770", nowMillis = t0 + attempt * 100L)
            assertTrue(res is OfflineSecurityVault.PinVerificationResult.InvalidPin)
            assertEquals(OfflineSecurityVault.MAX_FAILED_ATTEMPTS - attempt, (res as OfflineSecurityVault.PinVerificationResult.InvalidPin).remainingAttempts)
        }

        val fifthRes = vault.verifyMasterPinDetailed("7770", nowMillis = t0 + 500L)
        assertTrue("5th failed attempt must lock verification", fifthRes is OfflineSecurityVault.PinVerificationResult.LockedOut)

        // Correct PIN during lockout window must remain blocked
        val duringLockout = vault.verifyMasterPinDetailed("8492", nowMillis = t0 + 5_000L)
        assertTrue("Correct PIN must be blocked during lockout", duringLockout is OfflineSecurityVault.PinVerificationResult.LockedOut)

        // After lockout window expires, correct PIN succeeds and resets failure counter
        val afterLockout = vault.verifyMasterPinDetailed(
            "8492",
            nowMillis = t0 + 500L + OfflineSecurityVault.LOCKOUT_DURATION_MS + 1L
        )
        assertTrue("Valid PIN must succeed after lockout expires", afterLockout is OfflineSecurityVault.PinVerificationResult.Verified)
        assertEquals(0, vault.currentHealthSnapshot(t0 + 60_000L).failedAttempts)
    }

    @Test
    fun changeMasterPinRequiresValidCurrentPinAndRejectsWeakReplacement() {
        val repo = DeviceSettingsRepository(context)
        assertTrue(repo.enrollMasterPin("8492") is OfflineSecurityVault.PinEnrollmentResult.Success)

        val wrongCurrent = repo.changeMasterPin("0000", "7391")
        assertTrue(wrongCurrent is OfflineSecurityVault.PinEnrollmentResult.Rejected)
        assertTrue("Old PIN must remain active after failed change", repo.verifyPin("8492"))

        val weakNew = repo.changeMasterPin("8492", "1234")
        assertTrue(weakNew is OfflineSecurityVault.PinEnrollmentResult.Rejected)
        assertTrue(repo.verifyPin("8492"))

        val okChange = repo.changeMasterPin("8492", "7391")
        assertTrue(okChange is OfflineSecurityVault.PinEnrollmentResult.Success)
        assertFalse(repo.verifyPin("8492"))
        assertTrue(repo.verifyPin("7391"))
    }

    @Test
    fun onboardingAuthDialogHasNoHardcodedEmailAndRejectsWeakPin1234() {
        var registerCalled = false
        composeTestRule.setContent {
            MyApplicationTheme(language = "en") {
                OnboardingAuthDialog(
                    uiState = InkingiUiState(currentLanguage = "en"),
                    initialDeviceName = "Pixel Node",
                    initialOwnerEmail = "",
                    onSignIn = { _, _ -> },
                    onRegister = { _, _, _, _ -> registerCalled = true },
                    onSendVerificationEmail = {},
                    onCheckVerificationStatus = {},
                    onDismiss = {}
                )
            }
        }

        assertTrue(
            "Hardcoded inkingiguard@gmail.com must not be prefilled",
            composeTestRule.onAllNodesWithText("inkingiguard@gmail.com", substring = true)
                .fetchSemanticsNodes().isEmpty()
        )

        composeTestRule.onNodeWithTag("auth_email_input").performTextInput("owner@domain.org")
        composeTestRule.onNodeWithTag("auth_password_input").performTextInput("StrongPass123")
        composeTestRule.onNodeWithTag("auth_confirm_password_input").performTextInput("StrongPass123")
        composeTestRule.onNodeWithTag("auth_master_pin_input").performTextInput("1234")
        composeTestRule.onNodeWithTag("auth_confirm_pin_input").performTextInput("1234")
        composeTestRule.onNodeWithTag("auth_submit_btn").performClick()
        composeTestRule.waitForIdle()

        assertFalse("Weak default PIN 1234 must block registration", registerCalled)
        composeTestRule.onNodeWithTag("auth_error_text").performScrollTo()
        composeTestRule.onNodeWithTag("auth_error_text").assertIsDisplayed()
        composeTestRule.onNodeWithTag("auth_error_text").assertTextContains("Weak or sequential PINs", substring = true)
    }

    @Test
    fun settingsScreenEnrollsMasterPinThroughHardwareVaultDialog() {
        val repo = DeviceSettingsRepository(context)
        var enrolledSuccess = false
        var currentState by mutableStateOf(
            InkingiUiState(
                currentLanguage = "en",
                isPinConfigured = false,
                isVaultKeystoreHealthy = true,
                deviceProfile = DeviceProfile(
                    deviceId = "IG-TEST",
                    deviceName = "Pixel 9",
                    ownerEmail = "",
                    isRegistered = false,
                    masterPinHash = ""
                )
            )
        )

        composeTestRule.setContent {
            MyApplicationTheme(language = "en") {
                SettingsScreen(
                    uiState = currentState,
                    onSetLanguage = {},
                    onSwitchUserRole = { _, _ -> false },
                    onAddTrustedPerson = { _, _, _, _ -> false },
                    onRemoveTrustedPerson = { _, _ -> false },
                    onSimulateSimChange = {},
                    onSimulateDisplacement = {},
                    onSimulateKineticImpact = {},
                    onSimulateReboot = {},
                    onSimulateNetworkDrop = {},
                    onResetSimulations = {},
                    onEnrollMasterPin = { pin ->
                        val ok = repo.enrollMasterPin(pin) is OfflineSecurityVault.PinEnrollmentResult.Success
                        if (ok) {
                            enrolledSuccess = true
                            currentState = currentState.copy(isPinConfigured = true, pinActionError = null)
                        } else {
                            currentState = currentState.copy(pinActionError = "Weak PIN rejected.")
                        }
                        ok
                    },
                    onChangeMasterPin = { cur, new ->
                        repo.changeMasterPin(cur, new) is OfflineSecurityVault.PinEnrollmentResult.Success
                    }
                )
            }
        }

        // Scroll to PIN enrollment CTA and open dialog
        composeTestRule.onNodeWithTag("settings_list")
            .performScrollToNode(hasTestTag("enroll_or_change_pin_button"))
        composeTestRule.onNodeWithTag("enroll_or_change_pin_button").performClick()
        composeTestRule.waitForIdle()

        // Try weak PIN 1234 -> stays open and displays error
        composeTestRule.onNodeWithTag("new_pin_setup_input").performTextInput("1234")
        composeTestRule.onNodeWithTag("confirm_pin_setup_input").performTextInput("1234")
        composeTestRule.onNodeWithTag("save_pin_setup_button").performClick()
        composeTestRule.waitForIdle()
        assertFalse(enrolledSuccess)
        composeTestRule.onNodeWithTag("pin_setup_error_text").assertIsDisplayed()

        // Replace with strong PIN 8492 -> enrolls via PBKDF2 + Keystore
        composeTestRule.onNodeWithTag("new_pin_setup_input").performTextReplacement("8492")
        composeTestRule.onNodeWithTag("confirm_pin_setup_input").performTextReplacement("8492")
        composeTestRule.onNodeWithTag("save_pin_setup_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue("Strong PIN 8492 must enroll cleanly via Settings UI", enrolledSuccess)
        assertTrue(repo.isPinConfigured.value)
        assertTrue(repo.verifyPin("8492"))
        assertFalse(repo.verifyPin("1234"))
    }
}
