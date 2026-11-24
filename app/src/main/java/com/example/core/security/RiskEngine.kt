package com.example.core.security

import com.example.core.model.ConfidenceLevel
import com.example.core.model.RiskAssessment
import com.example.core.model.RiskLevel

/**
 * Deterministic InkingiGuard Risk Engine.
 *
 * Scored factors strictly defined by specifications:
 * - SIM change = +30
 * - 3+ authentication failures = +40
 * - Outside trusted zone = +20
 * - Unusual movement = +15
 * - Unexpected restart anomaly = +20
 * - Multiple correlated sensor anomalies = +10
 * - Network anomaly = +10
 *
 * Scoring:
 * 0 - 29: LOW
 * 30 - 69: MEDIUM
 * 70 - 100: HIGH
 *
 * Deterministic rules strictly override advisory AI.
 */
class RiskEngine {

    companion object {
        const val SCORE_SIM_CHANGE = 30
        const val SCORE_AUTH_FAILURES_THRESHOLD = 40
        const val SCORE_OUTSIDE_TRUSTED_ZONE = 20
        const val SCORE_UNUSUAL_MOVEMENT = 15
        const val SCORE_UNEXPECTED_RESTART = 20
        const val SCORE_CORRELATED_SENSOR_ANOMALIES = 10
        const val SCORE_NETWORK_ANOMALY = 10

        const val THRESHOLD_LOW_MAX = 29
        const val THRESHOLD_MEDIUM_MAX = 69
    }

    data class EvaluationInput(
        val isSimChanged: Boolean = false,
        val failedAuthAttempts: Int = 0,
        val isOutsideTrustedZone: Boolean = false,
        val isUnusualMovementDetected: Boolean = false,
        val isUnexpectedRestartDetected: Boolean = false,
        val hasCorrelatedSensorAnomalies: Boolean = false,
        val hasNetworkAnomaly: Boolean = false
    )

    fun evaluate(input: EvaluationInput): RiskAssessment {
        var totalScore = 0
        val reasons = mutableListOf<String>()

        if (input.isSimChanged) {
            totalScore += SCORE_SIM_CHANGE
            reasons.add("SIM alteration or unexpected subscriber change detected (+$SCORE_SIM_CHANGE)")
        }

        if (input.failedAuthAttempts >= 3) {
            totalScore += SCORE_AUTH_FAILURES_THRESHOLD
            reasons.add("${input.failedAuthAttempts} consecutive master authentication failures (+$SCORE_AUTH_FAILURES_THRESHOLD)")
        }

        if (input.isOutsideTrustedZone) {
            totalScore += SCORE_OUTSIDE_TRUSTED_ZONE
            reasons.add("Device displaced beyond configured trusted perimeter (+$SCORE_OUTSIDE_TRUSTED_ZONE)")
        }

        if (input.isUnusualMovementDetected) {
            totalScore += SCORE_UNUSUAL_MOVEMENT
            reasons.add("Unusual kinetic acceleration or rapid rotation spike detected (+$SCORE_UNUSUAL_MOVEMENT)")
        }

        if (input.isUnexpectedRestartDetected) {
            totalScore += SCORE_UNEXPECTED_RESTART
            reasons.add("Unexpected system reboot or forced power cycle pattern (+$SCORE_UNEXPECTED_RESTART)")
        }

        if (input.hasCorrelatedSensorAnomalies) {
            totalScore += SCORE_CORRELATED_SENSOR_ANOMALIES
            reasons.add("Multi-sensor telemetry divergence (accelerometer, proximity, light) (+$SCORE_CORRELATED_SENSOR_ANOMALIES)")
        }

        if (input.hasNetworkAnomaly) {
            totalScore += SCORE_NETWORK_ANOMALY
            reasons.add("Sudden radio disconnection or forced carrier disassociation (+$SCORE_NETWORK_ANOMALY)")
        }

        // Clamp to max 100
        val clampedScore = totalScore.coerceIn(0, 100)

        val level = when {
            clampedScore <= THRESHOLD_LOW_MAX -> RiskLevel.LOW
            clampedScore <= THRESHOLD_MEDIUM_MAX -> RiskLevel.MEDIUM
            else -> RiskLevel.HIGH
        }

        return RiskAssessment(
            score = clampedScore,
            level = level,
            reasons = if (reasons.isEmpty()) listOf("All verified signals operating within nominal baseline parameters.") else reasons,
            timestamp = System.currentTimeMillis()
        )
    }

    fun assessConfidence(riskAssessment: RiskAssessment, input: EvaluationInput): ConfidenceLevel {
        val signalsCount = listOf(
            input.isSimChanged,
            input.failedAuthAttempts >= 3,
            input.isOutsideTrustedZone,
            input.isUnusualMovementDetected,
            input.isUnexpectedRestartDetected,
            input.hasCorrelatedSensorAnomalies,
            input.hasNetworkAnomaly
        ).count { it }

        return when {
            signalsCount >= 3 || riskAssessment.score >= 70 -> ConfidenceLevel.HIGH_CONFIDENCE
            signalsCount >= 1 || riskAssessment.score >= 30 -> ConfidenceLevel.MEDIUM_CONFIDENCE
            else -> ConfidenceLevel.LOW_CONFIDENCE
        }
    }
}
