package com.example.domain.evidence

import android.util.Log
import com.example.core.model.DeviceHealthInfo
import com.example.core.model.LocationEvent
import com.example.core.model.QueuePriority
import com.example.core.model.RiskAssessment
import com.example.core.model.SimSecurityInfo
import com.example.domain.sync.SecurityEventQueue
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

enum class EvidenceSyncOutcome {
    SUBMITTED,
    PENDING_OFFLINE,
    FAILED,
    UNAVAILABLE
}

data class PoliceEvidenceSubmissionResult(
    val outcome: EvidenceSyncOutcome,
    val evidence: Map<String, Any?>,
    val syncStatusLabel: String,
    val errorMessage: String? = null
)

interface PoliceEvidenceCloudGateway {
    fun currentOwnerUid(): String?
    fun isCloudConfigured(): Boolean
    suspend fun uploadEvidence(
        ownerUid: String,
        deviceId: String,
        documentId: String,
        evidenceData: Map<String, Any?>
    ): Result<Unit>
}

class FirebasePoliceEvidenceGateway : PoliceEvidenceCloudGateway {
    override fun currentOwnerUid(): String? {
        return try {
            if (!isCloudConfigured()) return null
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Exception) {
            null
        }
    }

    override fun isCloudConfigured(): Boolean {
        return try {
            FirebaseApp.getInstance()
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun uploadEvidence(
        ownerUid: String,
        deviceId: String,
        documentId: String,
        evidenceData: Map<String, Any?>
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            if (!isCloudConfigured()) {
                return@withContext Result.failure(IllegalStateException("Firebase is not initialized on this device."))
            }
            FirebaseFirestore.getInstance()
                .collection("users")
                .document(ownerUid)
                .collection("devices")
                .document(deviceId)
                .collection("policeEvidence")
                .document(documentId)
                .set(evidenceData)
                .await()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Collects evidence from genuine local telemetry without simulating data.
 * Securely stores the technical dossier under users/{ownerUid}/devices/{deviceId}/policeEvidence/{timestamp}
 * and returns explicit delivery outcomes (SUBMITTED, PENDING_OFFLINE, FAILED, UNAVAILABLE).
 */
class EvidenceRepository(
    private val eventQueue: SecurityEventQueue? = null,
    private val cloudGateway: PoliceEvidenceCloudGateway = FirebasePoliceEvidenceGateway()
) {
    suspend fun collectEvidenceForPoliceWithResult(
        deviceId: String,
        ownerUid: String? = cloudGateway.currentOwnerUid(),
        isNetworkConnected: Boolean = true,
        location: LocationEvent?,
        simInfo: SimSecurityInfo?,
        batteryInfo: DeviceHealthInfo?,
        riskAssessment: RiskAssessment?
    ): PoliceEvidenceSubmissionResult = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis()
        val lat = location?.latitude
        val lng = location?.longitude
        val accuracy = location?.accuracy ?: -1f

        val simStatus = when {
            simInfo == null -> "Non disponible"
            simInfo.hasSimChanged -> "ALERTE: Carte SIM modifiée ou absente"
            simInfo.isSimPresent -> "SIM présente (Opérateur: ${simInfo.carrierName})"
            else -> "SIM absente"
        }

        val batteryPct = batteryInfo?.batteryPercentage ?: -1
        val riskScore = riskAssessment?.score ?: 0
        val observedReasons = riskAssessment?.reasons ?: listOf("Vérification standard")

        val evidenceData = mutableMapOf<String, Any?>(
            "deviceId" to deviceId,
            "ownerUid" to (ownerUid ?: "NON_AUTHENTIFIE"),
            "timestamp" to timestamp,
            "simStatus" to simStatus,
            "battery" to if (batteryPct >= 0) "$batteryPct%" else "Inconnu",
            "riskScore" to riskScore,
            "events" to observedReasons
        )

        if (lat != null && lng != null && (lat != 0.0 || lng != 0.0)) {
            evidenceData["lastKnownLat"] = lat
            evidenceData["lastKnownLng"] = lng
            evidenceData["locationAccuracy"] = accuracy
            evidenceData["locationAvailable"] = true
        } else {
            evidenceData["lastKnownLat"] = null
            evidenceData["lastKnownLng"] = null
            evidenceData["locationAvailable"] = false
        }

        if (ownerUid.isNullOrBlank() || ownerUid == "anonymous_owner" || !cloudGateway.isCloudConfigured()) {
            val statusLabel = "LOCAL_UNIQUEMENT_NON_AUTHENTIFIÉ"
            evidenceData["syncStatus"] = statusLabel
            return@withContext PoliceEvidenceSubmissionResult(
                outcome = EvidenceSyncOutcome.UNAVAILABLE,
                evidence = evidenceData,
                syncStatusLabel = statusLabel,
                errorMessage = "Cloud backup unavailable: signed-in Firebase owner session required."
            )
        }

        if (!isNetworkConnected) {
            val statusLabel = "EN_ATTENTE_HORS_LIGNE"
            evidenceData["syncStatus"] = statusLabel
            try {
                val payload = org.json.JSONObject().apply {
                    put("deviceId", deviceId)
                    put("ownerUid", ownerUid)
                    put("timestamp", timestamp)
                    put("riskScore", riskScore)
                }.toString()
                eventQueue?.enqueue(
                    eventType = "POLICE_REPORT_PENDING",
                    payload = payload,
                    priority = QueuePriority.HIGH,
                    idempotencyKey = "police_report_${deviceId}_$timestamp"
                )
            } catch (e: Exception) {
                Log.w("EvidenceRepository", "Failed to enqueue offline police report: ${e.message}")
            }
            return@withContext PoliceEvidenceSubmissionResult(
                outcome = EvidenceSyncOutcome.PENDING_OFFLINE,
                evidence = evidenceData,
                syncStatusLabel = statusLabel,
                errorMessage = "Offline: dossier saved locally and queued for later sync."
            )
        }

        val uploadResult = cloudGateway.uploadEvidence(
            ownerUid = ownerUid,
            deviceId = deviceId,
            documentId = timestamp.toString(),
            evidenceData = evidenceData.toMap()
        )

        if (uploadResult.isSuccess) {
            val statusLabel = "SYNCHRONISÉ"
            evidenceData["syncStatus"] = statusLabel
            PoliceEvidenceSubmissionResult(
                outcome = EvidenceSyncOutcome.SUBMITTED,
                evidence = evidenceData,
                syncStatusLabel = statusLabel,
                errorMessage = null
            )
        } else {
            val errorMsg = uploadResult.exceptionOrNull()?.message ?: "Unknown Firestore error"
            val statusLabel = "ÉCHEC_SYNCHRONISATION ($errorMsg)"
            evidenceData["syncStatus"] = statusLabel
            Log.w("EvidenceRepository", "Firestore write failed: $errorMsg")
            PoliceEvidenceSubmissionResult(
                outcome = EvidenceSyncOutcome.FAILED,
                evidence = evidenceData,
                syncStatusLabel = statusLabel,
                errorMessage = errorMsg
            )
        }
    }

    suspend fun collectEvidenceForPolice(
        deviceId: String,
        ownerUid: String? = cloudGateway.currentOwnerUid(),
        location: LocationEvent?,
        simInfo: SimSecurityInfo?,
        batteryInfo: DeviceHealthInfo?,
        riskAssessment: RiskAssessment?
    ): Map<String, Any?> = collectEvidenceForPoliceWithResult(
        deviceId = deviceId,
        ownerUid = ownerUid,
        isNetworkConnected = true,
        location = location,
        simInfo = simInfo,
        batteryInfo = batteryInfo,
        riskAssessment = riskAssessment
    ).evidence
}
