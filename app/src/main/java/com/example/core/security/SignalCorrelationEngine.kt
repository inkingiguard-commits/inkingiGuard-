package com.example.core.security

import com.example.core.model.ConfidenceLevel
import com.example.core.model.CorrelationThreatState
import com.example.core.model.EventSeverity
import com.example.core.model.LocationEvent
import com.example.core.model.MotionEvent
import com.example.core.model.MovementType
import com.example.core.model.RiskAssessment
import com.example.core.model.SecurityEvent
import com.example.core.model.SimSecurityInfo
import com.example.core.model.UserRole
import java.util.UUID

/**
 * Security Signal Correlation Engine.
 * Correlates disparate sensor vectors:
 * - GPS displacement & known/unknown zones
 * - Kinetic motion / unusual movement
 * - SIM status / carrier alteration
 * - Radio link integrity / network disconnection
 * - Authentication failures
 * - Unexpected system reboots
 * - Charging & behavior baseline deviations
 * - Active UserRole (OWNER vs GUEST vs TRUSTED_PERSON)
 *
 * Guarantees:
 * - A guest or trusted person is NEVER automatically classified as a thief.
 * - One abnormal behavior, unfamiliar zone, SIM change, or unusual movement NEVER confirms theft alone.
 * - Multi-signal correlation requires independent converging evidence items to escalate risk.
 * - Produces: NORMAL, LOW_RISK, SUSPICIOUS, HIGH_RISK, THEFT_CONFIRMATION_REQUIRED.
 * - Only the authorized OWNER can confirm theft.
 */
class SignalCorrelationEngine(
    private val riskEngine: RiskEngine
) {

    data class CorrelatedThreatReport(
        val assessment: RiskAssessment,
        val confidence: ConfidenceLevel,
        val correlatedAnomaliesCount: Int,
        val threatState: CorrelationThreatState,
        val isTheftPatternDetected: Boolean,
        val summaryStatement: String,
        val contributingSignals: List<String>,
        val generatedSecurityEvents: List<SecurityEvent>
    )

    fun correlateSignals(
        simInfo: SimSecurityInfo,
        lastLocation: LocationEvent?,
        recentMotion: MotionEvent?,
        failedAuthCount: Int,
        isRebootDetected: Boolean,
        isNetworkDropped: Boolean,
        sensorAnomalyFlag: Boolean,
        isWithinBaseline: Boolean = true,
        baselineExplanation: String? = null,
        userRole: UserRole = UserRole.OWNER,
        isChargingAnomaly: Boolean = false
    ): CorrelatedThreatReport {
        val isSimChanged = simInfo.hasSimChanged
        val isOutsideTrustedZone = lastLocation?.let { !it.isInsideTrustedZone } ?: false
        val isUnusualMotion = recentMotion?.let {
            it.movementType == MovementType.SUDDEN_ACCELERATION ||
            it.movementType == MovementType.UNUSUAL_ROTATION
        } ?: false

        val evalInput = RiskEngine.EvaluationInput(
            isSimChanged = isSimChanged,
            failedAuthAttempts = failedAuthCount,
            isOutsideTrustedZone = isOutsideTrustedZone,
            isUnusualMovementDetected = isUnusualMotion,
            isUnexpectedRestartDetected = isRebootDetected,
            hasCorrelatedSensorAnomalies = sensorAnomalyFlag,
            hasNetworkAnomaly = isNetworkDropped
        )

        var baseRiskAssessment = riskEngine.evaluate(evalInput)
        val confidence = riskEngine.assessConfidence(baseRiskAssessment, evalInput)

        // Gather real explainable contributing signals
        val contributingSignals = mutableListOf<String>()

        if (isSimChanged) contributingSignals.add("SIM alteration / carrier modification")
        if (failedAuthCount >= 3) contributingSignals.add("$failedAuthCount consecutive auth failures")
        if (isOutsideTrustedZone) contributingSignals.add("Outside trusted perimeter")
        if (isUnusualMotion) contributingSignals.add("Unusual kinetic acceleration / rotation spike")
        if (isRebootDetected) contributingSignals.add("Unscheduled reboot anomaly")
        if (isNetworkDropped) contributingSignals.add("Radio connectivity lost / dropped")
        if (sensorAnomalyFlag) contributingSignals.add("Sensor mesh cross-divergence")
        if (!isWithinBaseline && userRole == UserRole.OWNER) {
            contributingSignals.add(baselineExplanation ?: "Outside learned behavioral baseline (+20 pts)")
        }
        if (isChargingAnomaly && userRole == UserRole.OWNER) {
            contributingSignals.add("Anomalous power disconnect during critical motion")
        }

        // Adjust score for behavioral baseline when owner is active
        var totalScore = baseRiskAssessment.score
        if (!isWithinBaseline && userRole == UserRole.OWNER) {
            totalScore = (totalScore + 20).coerceAtMost(100)
        }

        // SAFETY GATE 1: If current user is GUEST or TRUSTED_PERSON, phone usage is legitimate
        if (userRole == UserRole.GUEST) {
            // A guest is NEVER classified as a thief
            contributingSignals.add("Active session: GUEST (Usage authorized by owner)")
        } else if (userRole == UserRole.TRUSTED_PERSON) {
            contributingSignals.add("Active session: TRUSTED_PERSON (Authorized custody)")
        }

        val activeAnomalyCount = listOf(
            isSimChanged,
            failedAuthCount >= 3,
            isOutsideTrustedZone,
            isUnusualMotion,
            isRebootDetected,
            sensorAnomalyFlag,
            isNetworkDropped,
            (!isWithinBaseline && userRole == UserRole.OWNER)
        ).count { it }

        // SAFETY GATE 2: SINGLE SIGNAL ANOMALY NEVER CONFIRMS THEFT
        // Multiple independent signals must converge to reach THEFT_CONFIRMATION_REQUIRED
        val isMultipleSignalsConverged = activeAnomalyCount >= 3 ||
                (isSimChanged && isOutsideTrustedZone && isUnusualMotion) ||
                (isSimChanged && isOutsideTrustedZone && isNetworkDropped) ||
                (isOutsideTrustedZone && isUnusualMotion && failedAuthCount >= 3) ||
                (isOutsideTrustedZone && !isWithinBaseline && isUnusualMotion && isSimChanged)

        val threatState = when {
            // Guest or trusted person cannot trigger theft confirmation
            userRole != UserRole.OWNER -> {
                if (totalScore >= 70) CorrelationThreatState.SUSPICIOUS else CorrelationThreatState.LOW_RISK
            }
            // Multi-signal convergence with high score
            isMultipleSignalsConverged && totalScore >= 70 -> CorrelationThreatState.THEFT_CONFIRMATION_REQUIRED
            totalScore >= 70 -> CorrelationThreatState.HIGH_RISK
            totalScore >= 30 || activeAnomalyCount in 1..2 -> CorrelationThreatState.SUSPICIOUS
            totalScore > 0 -> CorrelationThreatState.LOW_RISK
            else -> CorrelationThreatState.NORMAL
        }

        val isTheftPattern = threatState == CorrelationThreatState.THEFT_CONFIRMATION_REQUIRED

        val summary = buildString {
            when (threatState) {
                CorrelationThreatState.THEFT_CONFIRMATION_REQUIRED -> {
                    append("MULTIPLE SIGNALS CORRELATED: Independent vectors converged (${contributingSignals.joinToString(" + ")}). ")
                    append("THEFT CONFIRMATION REQUIRED by Owner.")
                }
                CorrelationThreatState.HIGH_RISK -> {
                    append("HIGH RISK EVALUATION: Score $totalScore/100. Signals: ${contributingSignals.joinToString(", ")}. ")
                    append("Single anomaly or unverified pattern — Owner confirmation required before theft declaration.")
                }
                CorrelationThreatState.SUSPICIOUS -> {
                    append("SUSPICIOUS ACTIVITY: $activeAnomalyCount signal divergence(s) detected (${contributingSignals.joinToString(", ")}).")
                }
                CorrelationThreatState.LOW_RISK -> {
                    append("LOW RISK: Minor baseline variance noted (${contributingSignals.joinToString(", ")}).")
                }
                CorrelationThreatState.NORMAL -> {
                    append("NOMINAL: Multi-sensor mesh, behavioral baseline, and location within trusted profile.")
                }
            }
        }

        val updatedAssessment = baseRiskAssessment.copy(
            score = totalScore,
            reasons = if (contributingSignals.isNotEmpty()) contributingSignals else listOf("Nominal baseline: all telemetry within baseline.")
        )

        val generatedEvents = mutableListOf<SecurityEvent>()
        if (isTheftPattern) {
            generatedEvents.add(
                SecurityEvent(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    severity = EventSeverity.CRITICAL,
                    title = "Multi-Signal Threat Correlated",
                    description = summary,
                    source = "SignalCorrelationEngine",
                    riskContribution = totalScore
                )
            )
        }

        return CorrelatedThreatReport(
            assessment = updatedAssessment,
            confidence = confidence,
            correlatedAnomaliesCount = activeAnomalyCount,
            threatState = threatState,
            isTheftPatternDetected = isTheftPattern,
            summaryStatement = summary,
            contributingSignals = contributingSignals,
            generatedSecurityEvents = generatedEvents
        )
    }
}
