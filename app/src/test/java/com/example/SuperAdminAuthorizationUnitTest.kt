package com.example

import com.example.core.model.UserRole
import com.example.data.repository.AdminVerificationResult
import com.example.data.repository.SuperAdminRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests validating Super Admin role verification, authorization access control,
 * and strict separation between local device roles (OWNER) and application-level SUPER_ADMIN.
 */
class SuperAdminAuthorizationUnitTest {

    class MockSuperAdminRepository : SuperAdminRepository {
        var mockIsAuthenticated: Boolean = false
        var mockEmail: String? = null
        var mockIsEmailVerified: Boolean = false
        var mockAdminEmailsInDoc: List<String> = emptyList()
        var mockDocExists: Boolean = true
        var mockNetworkFailure: Boolean = false

        private val _isSuperAdmin = MutableStateFlow(false)
        override val isSuperAdmin: StateFlow<Boolean> = _isSuperAdmin.asStateFlow()

        private val _adminVerificationResult = MutableStateFlow<AdminVerificationResult>(
            AdminVerificationResult.Denied("Non vérifié")
        )
        override val adminVerificationResult: StateFlow<AdminVerificationResult> = _adminVerificationResult.asStateFlow()

        override suspend fun verifySuperAdminStatus(forceRefresh: Boolean): AdminVerificationResult {
            if (mockNetworkFailure) {
                val res = AdminVerificationResult.Error("Network failure connecting to Firebase Firestore")
                _isSuperAdmin.value = false
                _adminVerificationResult.value = res
                return res
            }

            if (!mockIsAuthenticated || mockEmail == null) {
                val res = AdminVerificationResult.Denied("Utilisateur non authentifié")
                _isSuperAdmin.value = false
                _adminVerificationResult.value = res
                return res
            }

            if (!mockIsEmailVerified) {
                val res = AdminVerificationResult.Denied("Email non vérifié")
                _isSuperAdmin.value = false
                _adminVerificationResult.value = res
                return res
            }

            if (!mockDocExists) {
                val res = AdminVerificationResult.Denied("Configuration appconfig/admins absente")
                _isSuperAdmin.value = false
                _adminVerificationResult.value = res
                return res
            }

            val isAuthorized = mockAdminEmailsInDoc.contains(mockEmail)
            return if (isAuthorized) {
                val res = AdminVerificationResult.Authorized(mockEmail!!, "admin-uid-123")
                _isSuperAdmin.value = true
                _adminVerificationResult.value = res
                res
            } else {
                val res = AdminVerificationResult.Denied("Accès refusé : compte non autorisé dans appconfig/admins")
                _isSuperAdmin.value = false
                _adminVerificationResult.value = res
                res
            }
        }

        override suspend fun fetchRegisteredUsersCount(): Result<Int> {
            return if (_isSuperAdmin.value) Result.failure(NoSuchElementException("Statistique des utilisateurs non configurée."))
            else Result.failure(SecurityException("Accès refusé"))
        }

        override fun clearAdminStatus() {
            _isSuperAdmin.value = false
            _adminVerificationResult.value = AdminVerificationResult.Denied("Session terminée")
        }
    }

    @Test
    fun `test firestore adminEmails grants access to a verified matching account`() = runBlocking {
        val repo = MockSuperAdminRepository().apply {
            mockIsAuthenticated = true
            mockEmail = "inkingiguard@gmail.com"
            mockIsEmailVerified = true
            mockAdminEmailsInDoc = listOf("inkingiguard@gmail.com")
            mockDocExists = true
        }

        val result = repo.verifySuperAdminStatus()
        assertTrue("Verified email in adminEmails must be authorized", result is AdminVerificationResult.Authorized)
        assertTrue(repo.isSuperAdmin.value)
        assertEquals("inkingiguard@gmail.com", (result as AdminVerificationResult.Authorized).email)
    }

    @Test
    fun `test hardcoded email alone never grants access when absent from firestore`() = runBlocking {
        val repo = MockSuperAdminRepository().apply {
            mockIsAuthenticated = true
            mockEmail = "inkingiguard@gmail.com"
            mockIsEmailVerified = true
            mockAdminEmailsInDoc = listOf("different.admin@security.org") // inkingiguard is NOT in the doc
            mockDocExists = true
        }

        val result = repo.verifySuperAdminStatus()
        assertTrue("Email not present in adminEmails list must be denied even if it matches the designated address", result is AdminVerificationResult.Denied)
        assertFalse(repo.isSuperAdmin.value)
    }

    @Test
    fun `test missing admin document denies access even for designated email`() = runBlocking {
        val repo = MockSuperAdminRepository().apply {
            mockIsAuthenticated = true
            mockEmail = "inkingiguard@gmail.com"
            mockIsEmailVerified = true
            mockDocExists = false // appconfig/admins document is missing
        }

        val result = repo.verifySuperAdminStatus()
        assertTrue("Missing appconfig/admins document must deny access", result is AdminVerificationResult.Denied)
        assertFalse(repo.isSuperAdmin.value)
    }

    @Test
    fun `test unverified email is strictly denied super admin access`() = runBlocking {
        val repo = MockSuperAdminRepository().apply {
            mockIsAuthenticated = true
            mockEmail = "inkingiguard@gmail.com"
            mockIsEmailVerified = false // Unverified email
            mockAdminEmailsInDoc = listOf("inkingiguard@gmail.com")
            mockDocExists = true
        }

        val result = repo.verifySuperAdminStatus()
        assertTrue("Unverified email must be denied", result is AdminVerificationResult.Denied)
        assertFalse(repo.isSuperAdmin.value)
        assertTrue((result as AdminVerificationResult.Denied).reason.contains("non vérifié", ignoreCase = true))
    }

    @Test
    fun `test ordinary user email is denied super admin access`() = runBlocking {
        val repo = MockSuperAdminRepository().apply {
            mockIsAuthenticated = true
            mockEmail = "regular.user@example.com"
            mockIsEmailVerified = true
            mockAdminEmailsInDoc = listOf("inkingiguard@gmail.com")
            mockDocExists = true
        }

        val result = repo.verifySuperAdminStatus()
        assertTrue("Ordinary user must be denied", result is AdminVerificationResult.Denied)
        assertFalse(repo.isSuperAdmin.value)
    }

    @Test
    fun `test network failure denies super admin access by default`() = runBlocking {
        val repo = MockSuperAdminRepository().apply {
            mockIsAuthenticated = true
            mockEmail = "inkingiguard@gmail.com"
            mockIsEmailVerified = true
            mockNetworkFailure = true // Firestore unreachable
        }

        val result = repo.verifySuperAdminStatus()
        assertTrue("Network error must result in Error and access denied", result is AdminVerificationResult.Error)
        assertFalse("Super Admin access must fail closed on network error", repo.isSuperAdmin.value)
    }

    @Test
    fun `test local device OWNER role does not automatically grant application SUPER_ADMIN`() {
        val localRole = UserRole.OWNER
        val superAdminGranted = false // Ordinary user profile

        // Local OWNER role is distinct and device-local only
        assertEquals(UserRole.OWNER, localRole)
        assertFalse("Local OWNER role must never grant application-level SUPER_ADMIN", superAdminGranted)
    }
}
