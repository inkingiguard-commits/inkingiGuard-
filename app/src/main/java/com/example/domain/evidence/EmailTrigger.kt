package com.example.domain.evidence

import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

interface EmailRelayGateway {
    suspend fun queueOwnerEmail(
        ownerEmail: String?,
        ownerUid: String?,
        reportText: String
    ): Result<String>
}

class FirestoreEmailRelayGateway : EmailRelayGateway {
    override suspend fun queueOwnerEmail(
        ownerEmail: String?,
        ownerUid: String?,
        reportText: String
    ): Result<String> = EmailTrigger.queueEmail(
        ownerEmail = ownerEmail,
        ownerUid = ownerUid,
        reportText = reportText
    )
}

/**
 * Triggers backend Cloud Function email relay to the owner's own address by writing to
 * users/{uid}/mailTriggers/{timestamp}.
 * Strictly requires authenticated owner UID, safe Firebase initialization, and never claims
 * police received a report or that an email was sent unless Firestore write succeeded.
 */
object EmailTrigger {

    suspend fun queueEmail(
        ownerEmail: String?,
        ownerUid: String? = safeCurrentUid(),
        reportText: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val firebaseReady = isFirebaseReady()
        if (!firebaseReady) {
            return@withContext Result.failure(IllegalStateException("Firebase n'est pas initialisé sur cet appareil."))
        }

        val targetEmail = ownerEmail?.trim()?.takeIf { it.isNotBlank() && it != "Non configuré" }
            ?: safeCurrentEmail()
        if (targetEmail.isNullOrBlank() || targetEmail == "Non configuré") {
            return@withContext Result.failure(IllegalArgumentException("Aucune adresse email valide configurée."))
        }

        val uid = ownerUid?.trim()?.takeIf { it.isNotBlank() } ?: safeCurrentUid()
        if (uid == null) {
            return@withContext Result.failure(IllegalStateException("Utilisateur non authentifié. Impossible d'envoyer la demande de relais."))
        }

        val timestamp = System.currentTimeMillis()
        val mailDoc = mapOf(
            "to" to targetEmail,
            "subject" to "Alerte InkingiGuard — Rapport Technique d'Incident",
            "body" to reportText,
            "timestamp" to timestamp,
            "status" to "QUEUED"
        )

        try {
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("mailTriggers")
                .document(timestamp.toString())
                .set(mailDoc)
                .await()
            Log.i("EmailTrigger", "Queued owner email trigger to Firestore mailTriggers: $timestamp")
            Result.success("Copie propriétaire mise en file d'attente serveur.")
        } catch (e: Exception) {
            Log.w("EmailTrigger", "Email trigger write failed: ${e.message}")
            Result.failure(e)
        }
    }

    private fun isFirebaseReady(): Boolean = try {
        FirebaseApp.getInstance()
        true
    } catch (_: Exception) {
        false
    }

    private fun safeCurrentUid(): String? = try {
        if (!isFirebaseReady()) null else FirebaseAuth.getInstance().currentUser?.uid
    } catch (_: Exception) {
        null
    }

    private fun safeCurrentEmail(): String? = try {
        if (!isFirebaseReady()) null else FirebaseAuth.getInstance().currentUser?.email
    } catch (_: Exception) {
        null
    }
}
