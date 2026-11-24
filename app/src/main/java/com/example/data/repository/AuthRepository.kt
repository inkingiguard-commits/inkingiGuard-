package com.example.data.repository

import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.flow.StateFlow

sealed class AuthResult {
    data class Success(val user: FirebaseUser? = null, val isEmailVerified: Boolean = false) : AuthResult()
    data class Error(val message: String, val errorCode: String? = null) : AuthResult()
}

interface AuthRepository {
    val currentUser: FirebaseUser?
    val authState: StateFlow<FirebaseUser?>
    val isFirebaseAvailable: Boolean

    suspend fun registerWithEmail(email: String, password: String): AuthResult
    suspend fun signInWithEmail(email: String, password: String): AuthResult
    suspend fun signInWithGoogle(idToken: String): AuthResult
    suspend fun sendPasswordResetEmail(email: String): AuthResult
    suspend fun sendEmailVerification(): AuthResult
    suspend fun checkEmailVerification(): Boolean
    suspend fun signOut()
}
