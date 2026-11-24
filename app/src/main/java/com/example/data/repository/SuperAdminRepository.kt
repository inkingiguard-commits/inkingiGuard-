package com.example.data.repository

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Result of Super Admin verification against Firestore security document:
 * Document path: appconfig/admins (or appConfig/admins)
 * Required field: adminEmails (array of strings)
 */
sealed class AdminVerificationResult {
    data class Authorized(val email: String, val uid: String) : AdminVerificationResult()
    data class Denied(val reason: String) : AdminVerificationResult()
    data class Error(val message: String, val throwable: Throwable? = null) : AdminVerificationResult()
}

/**
 * Clean Architecture repository responsible for validating Super Admin authorization.
 *
 * Rules:
 * 1. Must be authenticated with Firebase.
 * 2. User's email must be verified (isEmailVerified == true).
 * 3. User's email must be listed in Firestore "appconfig/admins" -> "adminEmails".
 * 4. Token is refreshed on verification to ensure up-to-date claims.
 * 5. Fails closed (DENIED) on network errors, missing docs, or missing permissions.
 */
interface SuperAdminRepository {
    val isSuperAdmin: StateFlow<Boolean>
    val adminVerificationResult: StateFlow<AdminVerificationResult>

    suspend fun verifySuperAdminStatus(forceRefresh: Boolean = false): AdminVerificationResult
    suspend fun fetchRegisteredUsersCount(): Result<Int>
    fun clearAdminStatus()
}

class FirebaseSuperAdminRepository(
    private val context: Context
) : SuperAdminRepository {

    companion object {
        private const val TAG = "SuperAdminRepo"
    }

    private val auth: FirebaseAuth? by lazy {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseAuth.getInstance()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseAuth unavailable: ${e.message}")
            null
        }
    }

    private val firestore: FirebaseFirestore? by lazy {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseFirestore.getInstance()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "FirebaseFirestore unavailable: ${e.message}")
            null
        }
    }

    private val _isSuperAdmin = MutableStateFlow(false)
    override val isSuperAdmin: StateFlow<Boolean> = _isSuperAdmin.asStateFlow()

    private val _adminVerificationResult = MutableStateFlow<AdminVerificationResult>(
        AdminVerificationResult.Denied("Non vérifié")
    )
    override val adminVerificationResult: StateFlow<AdminVerificationResult> = _adminVerificationResult.asStateFlow()

    override suspend fun verifySuperAdminStatus(forceRefresh: Boolean): AdminVerificationResult = withContext(Dispatchers.IO) {
        val fbAuth = auth
        val db = firestore

        if (fbAuth == null || db == null) {
            val res = AdminVerificationResult.Denied("Services Firebase non initialisés sur cet appareil.")
            _isSuperAdmin.value = false
            _adminVerificationResult.value = res
            return@withContext res
        }

        val currentUser = fbAuth.currentUser
        if (currentUser == null) {
            val res = AdminVerificationResult.Denied("Utilisateur non authentifié.")
            _isSuperAdmin.value = false
            _adminVerificationResult.value = res
            return@withContext res
        }

        // 1. Reload user to ensure latest email verification state and refresh token
        try {
            currentUser.reload().await()
            if (forceRefresh) {
                currentUser.getIdToken(true).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh user session: ${e.message}")
        }

        val userEmail = currentUser.email?.trim()?.lowercase()
        if (userEmail.isNullOrBlank()) {
            val res = AdminVerificationResult.Denied("Adresse email du compte introuvable.")
            _isSuperAdmin.value = false
            _adminVerificationResult.value = res
            return@withContext res
        }

        // 2. Strict Requirement: Email must be verified
        if (!currentUser.isEmailVerified) {
            val res = AdminVerificationResult.Denied("L'adresse email '$userEmail' n'est pas encore vérifiée.")
            _isSuperAdmin.value = false
            _adminVerificationResult.value = res
            return@withContext res
        }

        // 3. Query Firestore document: "appconfig/admins" (fallback to "appConfig/admins")
        // Firestore appconfig/admins is the single authoritative source of truth.
        // No hardcoded email bypass is permitted.
        try {
            var doc = db.collection("appconfig").document("admins").get().await()
            if (!doc.exists()) {
                doc = db.collection("appConfig").document("admins").get().await()
            }

            if (!doc.exists()) {
                Log.w(TAG, "Document appconfig/admins does not exist in Firestore. Access denied.")
                val res = AdminVerificationResult.Denied("Configuration appconfig/admins absente.")
                _isSuperAdmin.value = false
                _adminVerificationResult.value = res
                return@withContext res
            }

            val adminEmails = (doc.get("adminEmails") as? List<*>)
                ?.mapNotNull { it?.toString()?.trim()?.lowercase() }
                ?: emptyList()

            val isListed = adminEmails.contains(userEmail)

            if (isListed) {
                Log.i(TAG, "Super Admin access granted for UID: ${currentUser.uid}")
                val res = AdminVerificationResult.Authorized(userEmail, currentUser.uid)
                _isSuperAdmin.value = true
                _adminVerificationResult.value = res
                res
            } else {
                Log.w(TAG, "Access Denied for UID ${currentUser.uid}: account is not present in appconfig/admins adminEmails list")
                val res = AdminVerificationResult.Denied("Accès refusé : ce compte n'a pas les privilèges Super Admin.")
                _isSuperAdmin.value = false
                _adminVerificationResult.value = res
                res
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network or security failure checking Super Admin permissions: ${e.message}", e)
            val res = AdminVerificationResult.Error(
                message = "Erreur lors de la vérification des privilèges administrateur : ${e.localizedMessage ?: e.message}",
                throwable = e
            )
            _isSuperAdmin.value = false
            _adminVerificationResult.value = res
            res
        }
    }

    override suspend fun fetchRegisteredUsersCount(): Result<Int> = withContext(Dispatchers.IO) {
        val db = firestore ?: return@withContext Result.failure(IllegalStateException("Firestore non disponible."))
        if (!_isSuperAdmin.value) {
            return@withContext Result.failure(SecurityException("Opération réservée au Super Admin."))
        }

        // Read aggregated statistics document if configured in appconfig/stats,
        // rather than downloading private user collections across accounts.
        try {
            var statsDoc = db.collection("appconfig").document("stats").get().await()
            if (!statsDoc.exists()) {
                statsDoc = db.collection("appConfig").document("stats").get().await()
            }
            if (statsDoc.exists()) {
                val count = statsDoc.getLong("totalUsers")?.toInt()
                if (count != null) {
                    return@withContext Result.success(count)
                }
            }
            // If aggregated count document is not provisioned, return failure so UI cleanly indicates "Indisponible / Unavailable"
            Result.failure(NoSuchElementException("Statistique des utilisateurs non configurée."))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun clearAdminStatus() {
        _isSuperAdmin.value = false
        _adminVerificationResult.value = AdminVerificationResult.Denied("Session terminée")
    }
}
