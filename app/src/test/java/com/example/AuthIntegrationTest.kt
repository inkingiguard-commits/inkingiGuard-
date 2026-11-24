package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.core.model.SecurityState
import com.example.data.local.InkingiDatabase
import com.example.data.repository.AuthRepository
import com.example.data.repository.AuthResult
import com.example.data.repository.FirebaseAuthRepository
import com.example.di.InkingiContainer
import com.example.domain.recovery.SecureRecoveryBridge
import com.example.presentation.InkingiViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AuthIntegrationTest {

    private lateinit var context: Context
    private lateinit var container: InkingiContainer
    private lateinit var viewModel: InkingiViewModel

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(com.example.data.repository.DeviceSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(com.example.domain.security.OfflineSecurityVault.VAULT_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val database = InkingiDatabase.getInstance(context)
        container = InkingiContainer(context, database)
        viewModel = InkingiViewModel(container)
    }

    @Test
    fun `auth repository degrades gracefully when google-services json is absent`() = runBlocking {
        val repo: AuthRepository = FirebaseAuthRepository(context)
        // In local test JVM without google-services.json, isFirebaseAvailable must be handled safely
        if (!repo.isFirebaseAvailable) {
            val regResult = repo.registerWithEmail("test@example.com", "secret123")
            assertTrue("Must report error when Firebase is uninitialized", regResult is AuthResult.Error)

            val loginResult = repo.signInWithEmail("test@example.com", "secret123")
            assertTrue("Must report error when Firebase is uninitialized", loginResult is AuthResult.Error)

            val googleResult = repo.signInWithGoogle("fake-id-token")
            assertTrue("Must report error when Firebase is uninitialized", googleResult is AuthResult.Error)

            val resetResult = repo.sendPasswordResetEmail("test@example.com")
            assertTrue("Must report error when Firebase is uninitialized", resetResult is AuthResult.Error)

            val verifyResult = repo.sendEmailVerification()
            assertTrue("Must report error when Firebase is uninitialized", verifyResult is AuthResult.Error)

            val isVerified = repo.checkEmailVerification()
            assertFalse(isVerified)

            // Sign out must complete without crashing
            repo.signOut()
            assertNull(repo.currentUser)
        }
    }

    @Test
    fun `master pin remains strictly independent from firebase credentials`() {
        // Without explicit enrollment, default PIN "1234" must fail closed
        assertFalse("Unconfigured device must not accept default 1234", viewModel.verifyMasterPin("1234"))
        assertFalse("Weak default PIN 1234 must be rejected at enrollment", viewModel.enrollMasterPin("1234"))

        // Enroll a valid strong Master PIN locally even when Firebase is unavailable
        assertTrue("Valid Master PIN must enroll cleanly", viewModel.enrollMasterPin("8492"))
        assertTrue("Enrolled Master PIN must verify successfully", viewModel.verifyMasterPin("8492"))
        assertFalse("Incorrect PIN must be rejected", viewModel.verifyMasterPin("9999"))

        // Ensure failed count tracked on local node
        val initialFailures = viewModel.uiState.value.failedAuthAttempts
        viewModel.verifyMasterPin("7319")
        assertEquals(initialFailures + 1, viewModel.uiState.value.failedAuthAttempts)
    }

    @Test
    fun `auth dialog visibility and error state controls work in ViewModel`() {
        assertFalse(viewModel.uiState.value.showAuthDialog)
        viewModel.setAuthDialogVisible(true)
        assertTrue(viewModel.uiState.value.showAuthDialog)

        viewModel.setAuthDialogVisible(false)
        assertFalse(viewModel.uiState.value.showAuthDialog)
        assertNull(viewModel.uiState.value.authErrorMessage)
    }

    @Test
    fun `secure recovery bridge without authenticated user does not use local_owner fallback`() = runBlocking {
        val bridge = container.secureRecoveryBridge
        val telemetry = bridge.syncTelemetry(
            state = SecurityState.NORMAL,
            location = null,
            health = null,
            network = null,
            recentEvents = emptyList()
        )

        // When unauthenticated, telemetry must be marked as LOCAL_NON_AUTHENTIFIÉ and not local_owner
        assertEquals("LOCAL_NON_AUTHENTIFIÉ", telemetry.syncStatus)
        assertEquals("", telemetry.ownerUid)
    }
}
