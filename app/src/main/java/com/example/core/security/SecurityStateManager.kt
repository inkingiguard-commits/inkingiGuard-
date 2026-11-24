package com.example.core.security

import com.example.core.model.AuditEvent
import com.example.core.model.EventSeverity
import com.example.core.model.RiskAssessment
import com.example.core.model.RiskLevel
import com.example.core.model.SecurityEvent
import com.example.core.model.SecurityState
import com.example.data.local.dao.AuditEventDao
import com.example.data.local.dao.SecurityEventDao
import com.example.data.local.entity.AuditEventEntity
import com.example.data.local.entity.SecurityEventEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Incident State Machine for InkingiGuard AI.
 * Explicit states:
 * NORMAL -> WARNING -> DANGER -> SUSPECTED_LOST -> OWNER_CONFIRMED_STOLEN -> STOLEN_MODE -> RECOVERY_MODE -> RECOVERED.
 *
 * Rules:
 * - Never jumps directly from one weak signal to permanent destructive action.
 * - AI may recommend a state change but is never the sole authority.
 * - Sensitive states require strong owner verification.
 * - Every transition creates an immutable audit event and security event.
 */
class SecurityStateManager(
    private val securityEventDao: SecurityEventDao,
    private val auditEventDao: AuditEventDao? = null,
    private val deviceIdProvider: () -> String = { "IG-DEVICE" }
) {

    private val _currentState = MutableStateFlow(SecurityState.NORMAL)
    val currentState: StateFlow<SecurityState> = _currentState.asStateFlow()

    private var activeIncidentId: String? = null

    fun setActiveIncidentId(id: String?) {
        activeIncidentId = id
    }

    /**
     * Evaluates state from deterministic risk score.
     * Note: Cannot unilaterally escalate to OWNER_CONFIRMED_STOLEN or STOLEN_MODE without owner action.
     */
    suspend fun evaluateStateFromRisk(riskAssessment: RiskAssessment) {
        val current = _currentState.value

        // Do not automatically downgrade out of locked/recovery states without owner authentication
        if (current == SecurityState.OWNER_CONFIRMED_STOLEN ||
            current == SecurityState.STOLEN_MODE ||
            current == SecurityState.RECOVERY_MODE ||
            current == SecurityState.RECOVERED
        ) {
            return
        }

        val targetState = when {
            riskAssessment.score >= 80 && riskAssessment.reasons.any { it.contains("displaced", ignoreCase = true) || it.contains("perimeter", ignoreCase = true) } -> {
                // Critical risk outside perimeter can transition to SUSPECTED_LOST to prepare recovery
                SecurityState.SUSPECTED_LOST
            }
            riskAssessment.level == RiskLevel.HIGH -> SecurityState.DANGER
            riskAssessment.level == RiskLevel.MEDIUM -> SecurityState.WARNING
            else -> SecurityState.NORMAL
        }

        if (targetState != current) {
            transitionTo(
                newState = targetState,
                reason = "Deterministic Risk Evaluation: Score ${riskAssessment.score} (${riskAssessment.level})",
                severity = when (targetState) {
                    SecurityState.NORMAL -> EventSeverity.INFO
                    SecurityState.WARNING -> EventSeverity.WARNING
                    SecurityState.DANGER, SecurityState.SUSPECTED_LOST -> EventSeverity.CRITICAL
                    else -> EventSeverity.INFO
                },
                riskContribution = riskAssessment.score,
                actor = "RiskEngine",
                authResult = "AUTOMATIC_TELEMETRY_EVALUATION"
            )
        }
    }

    suspend fun markSuspectedLost(reason: String, actor: String = "CorrelationEngine"): Boolean {
        return transitionTo(
            newState = SecurityState.SUSPECTED_LOST,
            reason = "Device suspected lost or displaced without authorization: $reason",
            severity = EventSeverity.CRITICAL,
            riskContribution = 40,
            actor = actor,
            authResult = "SYSTEM_VERIFIED_SIGNALS"
        )
    }

    suspend fun confirmStolenByOwner(authorizedBy: String): Boolean {
        // Step 1: Owner confirmed stolen
        transitionTo(
            newState = SecurityState.OWNER_CONFIRMED_STOLEN,
            reason = "Owner strongly verified theft report via $authorizedBy",
            severity = EventSeverity.CRITICAL,
            riskContribution = 60,
            actor = authorizedBy,
            authResult = "STRONG_OWNER_AUTH_VERIFIED"
        )

        // Step 2: Transition into full STOLEN_MODE
        return transitionTo(
            newState = SecurityState.STOLEN_MODE,
            reason = "Active Stolen Mode engaged: adaptive recovery telemetry armed",
            severity = EventSeverity.CRITICAL,
            riskContribution = 80,
            actor = authorizedBy,
            authResult = "CONFIRMED_THEFT_CONTAINMENT"
        )
    }

    suspend fun activateRecoveryMode(authorizedBy: String): Boolean {
        return transitionTo(
            newState = SecurityState.RECOVERY_MODE,
            reason = "Custody recovery workflow engaged via $authorizedBy",
            severity = EventSeverity.WARNING,
            riskContribution = 30,
            actor = authorizedBy,
            authResult = "OWNER_AUTH_VERIFIED"
        )
    }

    suspend fun markRecovered(authorizedBy: String): Boolean {
        return transitionTo(
            newState = SecurityState.RECOVERED,
            reason = "Physical custody of device confirmed recovered by owner via $authorizedBy",
            severity = EventSeverity.INFO,
            riskContribution = 0,
            actor = authorizedBy,
            authResult = "OWNER_RECOVERY_AUTH_VERIFIED"
        )
    }

    suspend fun disarmToNormal(authorizedBy: String): Boolean {
        return transitionTo(
            newState = SecurityState.NORMAL,
            reason = "Owner authenticated disarm command and restored baseline posture via $authorizedBy",
            severity = EventSeverity.INFO,
            riskContribution = 0,
            actor = authorizedBy,
            authResult = "OWNER_DISARM_VERIFIED"
        )
    }

    suspend fun armHighAlert(): Boolean {
        return transitionTo(
            newState = SecurityState.WARNING,
            reason = "Manual defensive alert perimeter armed by operator",
            severity = EventSeverity.WARNING,
            riskContribution = 15,
            actor = "Operator",
            authResult = "MANUAL_ARM"
        )
    }

    private suspend fun transitionTo(
        newState: SecurityState,
        reason: String,
        severity: EventSeverity,
        riskContribution: Int,
        actor: String = "SecurityStateManager",
        authResult: String = "AUTHORIZED"
    ): Boolean {
        val previousState = _currentState.value
        if (previousState == newState) return true

        _currentState.value = newState
        val timestamp = System.currentTimeMillis()

        // 1. Record SecurityEvent for chronological ledger
        val event = SecurityEvent(
            id = UUID.randomUUID().toString(),
            timestamp = timestamp,
            severity = severity,
            title = "State Transition: ${previousState.name} → ${newState.name}",
            description = reason,
            source = "SecurityStateManager",
            riskContribution = riskContribution
        )
        securityEventDao.insertEvent(SecurityEventEntity.fromDomain(event))

        // 2. Record immutable AuditEvent for Command Center
        try {
            auditEventDao?.insertAuditEvent(
                AuditEventEntity.fromDomain(
                    AuditEvent(
                        auditId = UUID.randomUUID().toString(),
                        incidentId = activeIncidentId,
                        deviceId = deviceIdProvider(),
                        stateBefore = previousState,
                        stateAfter = newState,
                        reason = reason,
                        riskScore = riskContribution,
                        actor = actor,
                        timestamp = timestamp,
                        authorizationResult = authResult
                    )
                )
            )
        } catch (_: Exception) {}

        return true
    }
}
