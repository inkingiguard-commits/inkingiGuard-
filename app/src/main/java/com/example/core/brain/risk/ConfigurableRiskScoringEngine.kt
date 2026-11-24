package com.example.core.brain.risk

import com.example.core.model.RiskAssessment
import com.example.core.model.RiskLevel
import kotlin.math.pow
import kotlin.math.roundToInt

data class RiskWeightsConfig(
    val simChangeWeight: Int = 30,
    val authFailuresThresholdWeight: Int = 40,
    val outsideTrustedZoneWeight: Int = 20,
    val unusualMovementWeight: Int = 15,
    val unexpectedRestartWeight: Int = 20,
    val sensorAnomaliesWeight: Int = 10,
    val networkAnomalyWeight: Int = 10,
    val behavioralDeviationWeight: Int = 20,
    val evidenceHalfLifeMillis: Long = 30 * 60 * 1000L, // 30-minute half-life
    val thresholdLowMax: Int = 29,
    val thresholdMediumMax: Int = 69
)

data class ScoredEvidenceItem(
    val factorKey: String,
    val description: String,
    val baseScore: Int,
    val timestamp: Long,
    val decayFactor: Float,
    val effectiveScore: Int,
    val itemizedExplanation: String
)

data class DecayedRiskEvaluation(
    val totalScore: Int,
    val riskLevel: RiskLevel,
    val evidenceItems: List<ScoredEvidenceItem>,
    val explanationAuditTrail: List<String>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Deterministic, Configurable Risk-Scoring Engine with Evidence Decay.
 *
 * Provides:
 * 1. Fully configurable factor weights.
 * 2. Exponential evidence decay so transient anomalies do not leave the device permanently in high-risk states.
 * 3. Itemized mathematical and descriptive explanations for every score calculation.
 */
class ConfigurableRiskScoringEngine(
    val config: RiskWeightsConfig = RiskWeightsConfig()
) {

    data class RawSignals(
        val isSimChanged: Boolean = false,
        val simChangeTimestamp: Long = 0L,
        val failedAuthAttempts: Int = 0,
        val lastAuthFailureTimestamp: Long = 0L,
        val isOutsideTrustedZone: Boolean = false,
        val zoneExitTimestamp: Long = 0L,
        val isUnusualMovementDetected: Boolean = false,
        val movementTimestamp: Long = 0L,
        val isUnexpectedRestartDetected: Boolean = false,
        val restartTimestamp: Long = 0L,
        val hasCorrelatedSensorAnomalies: Boolean = false,
        val sensorAnomalyTimestamp: Long = 0L,
        val hasNetworkAnomaly: Boolean = false,
        val networkAnomalyTimestamp: Long = 0L,
        val isBehavioralDeviation: Boolean = false,
        val behavioralDeviationTimestamp: Long = 0L,
        val behavioralDeviationDescription: String? = null,
        val userRole: com.example.core.model.UserRole = com.example.core.model.UserRole.OWNER
    )

    fun evaluate(signals: RawSignals, now: Long = System.currentTimeMillis()): DecayedRiskEvaluation {
        val scoredItems = mutableListOf<ScoredEvidenceItem>()

        if (signals.isSimChanged) {
            val ts = if (signals.simChangeTimestamp > 0) signals.simChangeTimestamp else now
            scoredItems.add(calculateEvidence("SIM_CHANGE", "SIM module alteration detected", config.simChangeWeight, ts, now))
        }

        if (signals.failedAuthAttempts >= 3) {
            val ts = if (signals.lastAuthFailureTimestamp > 0) signals.lastAuthFailureTimestamp else now
            scoredItems.add(calculateEvidence("AUTH_FAILURES", "${signals.failedAuthAttempts} consecutive master authentication failures", config.authFailuresThresholdWeight, ts, now))
        }

        if (signals.isOutsideTrustedZone) {
            val ts = if (signals.zoneExitTimestamp > 0) signals.zoneExitTimestamp else now
            scoredItems.add(calculateEvidence("PERIMETER_EXIT", "Device displaced outside trusted perimeter", config.outsideTrustedZoneWeight, ts, now))
        }

        if (signals.isUnusualMovementDetected) {
            val ts = if (signals.movementTimestamp > 0) signals.movementTimestamp else now
            scoredItems.add(calculateEvidence("UNUSUAL_MOVEMENT", "Kinetic jerk or angular velocity spike detected", config.unusualMovementWeight, ts, now))
        }

        if (signals.isUnexpectedRestartDetected) {
            val ts = if (signals.restartTimestamp > 0) signals.restartTimestamp else now
            scoredItems.add(calculateEvidence("UNEXPECTED_REBOOT", "Forced reboot / unexpected restart anomaly", config.unexpectedRestartWeight, ts, now))
        }

        if (signals.hasCorrelatedSensorAnomalies) {
            val ts = if (signals.sensorAnomalyTimestamp > 0) signals.sensorAnomalyTimestamp else now
            scoredItems.add(calculateEvidence("SENSOR_ANOMALIES", "Multi-sensor cross-divergence", config.sensorAnomaliesWeight, ts, now))
        }

        if (signals.hasNetworkAnomaly) {
            val ts = if (signals.networkAnomalyTimestamp > 0) signals.networkAnomalyTimestamp else now
            scoredItems.add(calculateEvidence("NETWORK_ANOMALY", "Sudden radio drop / forced disassociation", config.networkAnomalyWeight, ts, now))
        }

        if (signals.isBehavioralDeviation && signals.userRole == com.example.core.model.UserRole.OWNER) {
            val ts = if (signals.behavioralDeviationTimestamp > 0) signals.behavioralDeviationTimestamp else now
            val desc = signals.behavioralDeviationDescription?.takeIf { it.isNotBlank() }
                ?: "Observation outside learned behavioral baseline"
            scoredItems.add(calculateEvidence("BEHAVIORAL_DEVIATION", desc, config.behavioralDeviationWeight, ts, now))
        }

        val totalEffectiveScore = scoredItems.sumOf { it.effectiveScore }.coerceIn(0, 100)

        val level = when {
            totalEffectiveScore <= config.thresholdLowMax -> RiskLevel.LOW
            totalEffectiveScore <= config.thresholdMediumMax -> RiskLevel.MEDIUM
            else -> RiskLevel.HIGH
        }

        val roleNote = when (signals.userRole) {
            com.example.core.model.UserRole.GUEST -> "Active session: GUEST (Owner-authorized temporary usage; behavioral alerts suppressed)."
            com.example.core.model.UserRole.TRUSTED_PERSON -> "Active session: TRUSTED_PERSON (Authorized custody)."
            com.example.core.model.UserRole.OWNER -> null
        }

        val explanations = if (scoredItems.isEmpty()) {
            listOfNotNull(
                roleNote,
                "Nominal baseline: all monitored telemetry vectors operating within baseline parameters (0 pts)."
            )
        } else {
            scoredItems.map { it.itemizedExplanation } + listOfNotNull(roleNote)
        }

        return DecayedRiskEvaluation(
            totalScore = totalEffectiveScore,
            riskLevel = level,
            evidenceItems = scoredItems,
            explanationAuditTrail = explanations,
            timestamp = now
        )
    }

    private fun calculateEvidence(
        factorKey: String,
        description: String,
        baseScore: Int,
        eventTimestamp: Long,
        now: Long
    ): ScoredEvidenceItem {
        val elapsedMillis = (now - eventTimestamp).coerceAtLeast(0L)
        // Exponential decay using half-life: 2^(-elapsed / halfLife)
        val decayFactor = if (elapsedMillis < 2 * 60 * 1000L) {
            // Under 2 minutes is considered 100% fresh
            1.0f
        } else {
            (0.5.pow(elapsedMillis.toDouble() / config.evidenceHalfLifeMillis.toDouble())).toFloat()
        }

        val effectiveScore = (baseScore * decayFactor).roundToInt().coerceAtLeast(0)
        val decayPercent = (decayFactor * 100).roundToInt()
        val elapsedMinutes = elapsedMillis / (60 * 1000L)

        val explanation = "$description (+$baseScore base pts, recorded ${elapsedMinutes}m ago, ${decayPercent}% weight, decay factor $decayFactor -> +$effectiveScore pts)"

        return ScoredEvidenceItem(
            factorKey = factorKey,
            description = description,
            baseScore = baseScore,
            timestamp = eventTimestamp,
            decayFactor = decayFactor,
            effectiveScore = effectiveScore,
            itemizedExplanation = explanation
        )
    }

    fun toRiskAssessment(evaluation: DecayedRiskEvaluation): RiskAssessment {
        return RiskAssessment(
            score = evaluation.totalScore,
            level = evaluation.riskLevel,
            reasons = evaluation.explanationAuditTrail,
            timestamp = evaluation.timestamp
        )
    }
}
