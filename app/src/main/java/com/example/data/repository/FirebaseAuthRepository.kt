package com.example.data.repository

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

/**
 * Concrete implementation of [AuthRepository] backed by Firebase Authentication.
 * Handles credential verification, session restoration, email verification, and
 * graceful degradation when Firebase is unavailable.
 */
class FirebaseAuthRepository(
    private val context: Context
) : AuthRepository {

    companion object {
        private const val TAG = "FirebaseAuthRepository"
    }

    private val auth: FirebaseAuth? by lazy {
        try {
            if (FirebaseApp.getApps(context).isNotEmpty()) {
                FirebaseAuth.getInstance()
            } else {
                Log.w(TAG, "Default FirebaseApp is not initialized. Firebase Auth operations will be unavailable.")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize FirebaseAuth: ${e.message}", e)
            null
        }
    }

    override val isFirebaseAvailable: Boolean
        get() = auth != null

    private val _authState = MutableStateFlow<FirebaseUser?>(null)
    override val authState: StateFlow<FirebaseUser?> = _authState.asStateFlow()

    override val currentUser: FirebaseUser?
        get() = auth?.currentUser

    init {
        try {
            auth?.let { fbAuth ->
                _authState.value = fbAuth.currentUser
                fbAuth.addAuthStateListener { updatedAuth ->
                    _authState.value = updatedAuth.currentUser
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not attach AuthStateListener: ${e.message}", e)
        }
    }

    override suspend fun registerWithEmail(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val fbAuth = auth ?: return@withContext AuthResult.Error(
            message = "Firebase non configuré sur cet appareil. Impossible de créer un compte cloud.",
            errorCode = "FIREBASE_UNAVAILABLE"
        )

        try {
            val result = fbAuth.createUserWithEmailAndPassword(email.trim(), password).await()
            val user = result.user ?: return@withContext AuthResult.Error(
                message = "Échec de création du compte utilisateur.",
                errorCode = "NULL_USER"
            )

            // Attempt to send email verification immediately
            try {
                user.sendEmailVerification().await()
                Log.i(TAG, "Verification email dispatched for UID: ${user.uid}")
            } catch (e: Exception) {
                Log.w(TAG, "Could not send initial verification email: ${e.message}")
            }

            _authState.value = user
            AuthResult.Success(user = user, isEmailVerified = user.isEmailVerified)
        } catch (e: Exception) {
            Log.e(TAG, "Registration failed: ${e.message}", e)
            AuthResult.Error(
                message = mapFirebaseError(e),
                errorCode = e.javaClass.simpleName
            )
        }
    }

    override suspend fun signInWithEmail(email: String, password: String): AuthResult = withContext(Dispatchers.IO) {
        val fbAuth = auth ?: return@withContext AuthResult.Error(
            message = "Firebase non configuré sur cet appareil. Impossible de se connecter au cloud.",
            errorCode = "FIREBASE_UNAVAILABLE"
        )

        try {
            val result = fbAuth.signInWithEmailAndPassword(email.trim(), password).await()
            val user = result.user ?: return@withContext AuthResult.Error(
                message = "Utilisateur introuvable après connexion.",
                errorCode = "NULL_USER"
            )

            _authState.value = user
            AuthResult.Success(user = user, isEmailVerified = user.isEmailVerified)
        } catch (e: Exception) {
            Log.e(TAG, "Sign in failed: ${e.message}", e)
            AuthResult.Error(
                message = mapFirebaseError(e),
                errorCode = e.javaClass.simpleName
            )
        }
    }

    override suspend fun signInWithGoogle(idToken: String): AuthResult = withContext(Dispatchers.IO) {
        val fbAuth = auth ?: return@withContext AuthResult.Error(
            message = "Firebase non initialisé sur cet appareil. Connexion Google impossible.",
            errorCode = "FIREBASE_UNAVAILABLE"
        )

        try {
            val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
            val authResult = fbAuth.signInWithCredential(credential).await()
            val user = authResult.user ?: return@withContext AuthResult.Error(
                message = "Échec de récupération du compte Google.",
                errorCode = "NULL_USER"
            )

            _authState.value = user
            Log.i(TAG, "Google sign in successful for UID: ${user.uid}")
            AuthResult.Success(user = user, isEmailVerified = user.isEmailVerified)
        } catch (e: Exception) {
            Log.e(TAG, "Google sign in failed: ${e.message}", e)
            AuthResult.Error(
                message = mapFirebaseError(e),
                errorCode = e.javaClass.simpleName
            )
        }
    }

    override suspend fun sendPasswordResetEmail(email: String): AuthResult = withContext(Dispatchers.IO) {
        val fbAuth = auth ?: return@withContext AuthResult.Error(
            message = "Firebase non configuré sur cet appareil.",
            errorCode = "FIREBASE_UNAVAILABLE"
        )

        if (email.isBlank()) {
            return@withContext AuthResult.Error(
                message = "Veuillez saisir votre adresse email.",
                errorCode = "EMPTY_EMAIL"
            )
        }

        try {
            fbAuth.sendPasswordResetEmail(email.trim()).await()
            Log.i(TAG, "Password reset email dispatched.")
            AuthResult.Success(user = fbAuth.currentUser, isEmailVerified = fbAuth.currentUser?.isEmailVerified ?: false)
        } catch (e: Exception) {
            Log.e(TAG, "Password reset failed: ${e.message}", e)
            AuthResult.Error(
                message = mapFirebaseError(e),
                errorCode = e.javaClass.simpleName
            )
        }
    }

    override suspend fun sendEmailVerification(): AuthResult = withContext(Dispatchers.IO) {
        val user = auth?.currentUser ?: return@withContext AuthResult.Error(
            message = "Aucun utilisateur actuellement connecté.",
            errorCode = "NO_CURRENT_USER"
        )

        try {
            user.sendEmailVerification().await()
            Log.i(TAG, "Verification email resent for UID: ${user.uid}")
            AuthResult.Success(user = user, isEmailVerified = user.isEmailVerified)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send email verification: ${e.message}", e)
            AuthResult.Error(
                message = mapFirebaseError(e),
                errorCode = e.javaClass.simpleName
            )
        }
    }

    override suspend fun checkEmailVerification(): Boolean = withContext(Dispatchers.IO) {
        val user = auth?.currentUser ?: return@withContext false
        try {
            user.reload().await()
            val verified = user.isEmailVerified
            _authState.value = user
            verified
        } catch (e: Exception) {
            Log.w(TAG, "Failed to reload user for verification check: ${e.message}")
            user.isEmailVerified
        }
    }

    override suspend fun signOut(): Unit = withContext(Dispatchers.IO) {
        try {
            auth?.signOut()
            _authState.value = null
            Log.i(TAG, "Firebase user signed out successfully.")
        } catch (e: Exception) {
            Log.w(TAG, "Error during sign out: ${e.message}", e)
        }
    }

    private fun mapFirebaseError(e: Exception): String {
        return when (e) {
            is FirebaseAuthWeakPasswordException ->
                "Mot de passe trop faible : veuillez utiliser au moins 6 caractères (lettres et chiffres recommandés)."
            is FirebaseAuthInvalidCredentialsException ->
                "Format d'email invalide ou mot de passe incorrect."
            is FirebaseAuthUserCollisionException ->
                "Un compte existe déjà avec cette adresse email. Veuillez vous connecter."
            is FirebaseAuthInvalidUserException ->
                "Aucun compte trouvé avec cet email ou le compte a été désactivé."
            is FirebaseNetworkException ->
                "Erreur réseau : impossible de joindre les serveurs Firebase. Vérifiez votre connexion internet."
            is FirebaseTooManyRequestsException ->
                "Trop de tentatives infructueuses. Veuillez patienter quelques instants avant de réessayer."
            else ->
                e.localizedMessage ?: "Une erreur d'authentification est survenue. Veuillez réessayer."
        }
    }
}
