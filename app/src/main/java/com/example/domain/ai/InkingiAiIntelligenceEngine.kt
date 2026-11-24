package com.example.domain.ai

import com.example.core.model.ConfidenceLevel
import com.example.core.model.DeviceHealthInfo
import com.example.core.model.LocationEvent
import com.example.core.model.MotionEvent
import com.example.core.model.NetworkSecurityInfo
import com.example.core.model.RiskAssessment
import com.example.core.model.RiskLevel
import com.example.core.model.SecurityEvent
import com.example.core.model.SimSecurityInfo

/**
 * InkingiGuard AI Intelligence Layer.
 * Provides contextual explanations, threat assessments, and protective advice.
 *
 * CRITICAL BOUNDARY:
 * Deterministic rules strictly override advisory AI.
 * AI cannot unlock devices, override security controls, or make unverified theft accusations.
 */
class InkingiAiIntelligenceEngine {

    data class AiAdvisoryReport(
        val executiveSummary: String,
        val telemetryExplanation: String,
        val confidenceAssessment: ConfidenceLevel,
        val recommendedAction: String,
        val disclaimer: String = "AI advisory generated for security operator awareness. Deterministic rules maintain authoritative governance."
    )

    fun generateAdvisory(
        riskAssessment: RiskAssessment,
        simInfo: SimSecurityInfo,
        location: LocationEvent?,
        motion: MotionEvent?,
        health: DeviceHealthInfo,
        network: NetworkSecurityInfo,
        recentEvents: List<SecurityEvent>,
        lang: String = "fr"
    ): AiAdvisoryReport {
        val score = riskAssessment.score
        val reasons = riskAssessment.reasons
        val isFr = lang != "en"

        val summary = if (isFr) {
            when (riskAssessment.level) {
                RiskLevel.LOW -> "Posture nominale : le réseau de capteurs InkingiGuard indique des opérations normales. Aucune infraction physique ou cryptographique détectée."
                RiskLevel.MEDIUM -> "Avis de vigilance renforcée : le réseau de capteurs a identifié des schémas anormaux nécessitant vérification. La télémétrie s'écarte légèrement de la ligne de base."
                RiskLevel.HIGH -> "Corrélation de menace critique : divergence sévère sur plusieurs vecteurs de capteurs et d'identité. Déplacement de l'appareil ou compromission physique fortement suspectés."
            }
        } else {
            when (riskAssessment.level) {
                RiskLevel.LOW -> "Nominal posture: InkingiGuard sensor mesh indicates standard baseline operations. No physical or cryptographic security violations detected."
                RiskLevel.MEDIUM -> "Elevated vigilance advisory: Sensor mesh identified anomalous patterns requiring review. Telemetry signals deviate slightly from baseline configuration."
                RiskLevel.HIGH -> "High severity threat correlation: Critical divergence across multiple sensor and identity vectors. Device displacement or physical compromise strongly indicated."
            }
        }

        val explanation = buildString {
            if (reasons.isNotEmpty()) {
                append(if (isFr) "Signaux observés : " else "Observed signals: ")
                append(reasons.joinToString("; "))
                append(". ")
            }
            if (location != null && !location.isInsideTrustedZone) {
                append(if (isFr) "Déplacement : L'appareil est actuellement hors des coordonnées de confiance. " else "Displacement: Device is currently outside designated trusted coordinates. ")
            }
            if (simInfo.hasSimChanged) {
                append(if (isFr) "Alerte téléphonie : L'opérateur ou la carte SIM diffère du profil enregistré. " else "Telephony alert: Carrier identity or SIM module differs from initial enrollment profile. ")
            }
            if (motion != null && motion.accelerationMagnitude > 18.0f) {
                append((if (isFr) "Capteur cinétique : Forte accélération soudaine détectée (%.1f m/s²). " else "Kinetic sensor: Sudden high-g acceleration registered (%.1f m/s²). ").format(motion.accelerationMagnitude))
            }
            if (health.isRebootAnomalyDetected) {
                append(if (isFr) "Cycle de vie système : Redémarrage inattendu détecté dans des conditions suspectes. " else "System lifecycle: Unexpected reboot detected under anomalous circumstances. ")
            }
            if (!network.isConnected) {
                append(if (isFr) "Liaison réseau : Signal radio coupé ou déconnecté. " else "Network link: Radio transmission lost or severed. ")
            }
        }

        val action = if (isFr) {
            when (riskAssessment.level) {
                RiskLevel.LOW -> "Maintenir la surveillance défensive standard. Vérifier régulièrement les zones de confiance."
                RiskLevel.MEDIUM -> "Vérifier la possession physique de l'appareil. Confirmer le code PIN maître ou l'autorisation biométrique."
                RiskLevel.HIGH -> "Intervention immédiate de l'opérateur recommandée. Si l'appareil est introuvable, activer le mode vol immédiatement."
            }
        } else {
            when (riskAssessment.level) {
                RiskLevel.LOW -> "Maintain current defensive monitoring. Ensure periodic trusted zone radius audit."
                RiskLevel.MEDIUM -> "Verify device physical custody. Confirm master PIN credentials or biometric check."
                RiskLevel.HIGH -> "Immediate operator intervention recommended. If device is unaccounted for, initiate Stolen Mode protocol immediately."
            }
        }

        val confidence = when {
            score >= 70 -> ConfidenceLevel.HIGH_CONFIDENCE
            score >= 30 -> ConfidenceLevel.MEDIUM_CONFIDENCE
            else -> ConfidenceLevel.LOW_CONFIDENCE
        }

        val fallbackExplanation = if (isFr) "Capteurs fonctionnant dans les tolérances nominales." else "Sensors operating within expected tolerances."

        return AiAdvisoryReport(
            executiveSummary = summary,
            telemetryExplanation = explanation.ifBlank { fallbackExplanation },
            confidenceAssessment = confidence,
            recommendedAction = action
        )
    }
}
