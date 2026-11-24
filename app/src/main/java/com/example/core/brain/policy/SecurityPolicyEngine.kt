package com.example.core.brain.policy

import com.example.core.brain.model.ActionType
import com.example.core.brain.model.PolicyDecision
import com.example.core.brain.model.PolicyEvaluationResult
import com.example.core.brain.model.ProposedAction
import com.example.core.model.SecurityState

/**
 * Security Policy Engine.
 * Authoritative security gatekeeper that strictly validates every proposed response or state transition.
 *
 * Core Principle: FAIL CLOSED.
 * Prevents unauthorized locks, covert surveillance, unconfirmed theft declarations, and destructive actions.
 */
class SecurityPolicyEngine(
    private val minActionIntervalMillis: Long = 0L
) {

    private var lastActionTimestamp = 0L

    @Synchronized
    fun evaluateAction(proposedAction: ProposedAction): PolicyEvaluationResult {
        val violations = mutableListOf<String>()

        // 1. Anti-Spam Rate Limiting Guard (when configured > 0)
        if (minActionIntervalMillis > 0 && proposedAction.timestamp - lastActionTimestamp < minActionIntervalMillis) {
            violations.add("Action request frequency throttled (<${minActionIntervalMillis}ms since last action).")
        }
        lastActionTimestamp = proposedAction.timestamp

        // 2. Policy rules based on ActionType
        when (proposedAction.actionType) {
            ActionType.ACTIVATE_STOLEN_MODE -> {
                if (!proposedAction.isOwnerExplicitlyConfirmed) {
                    violations.add("STOLEN_MODE declaration prohibited without explicit owner verification.")
                    return PolicyEvaluationResult(
                        decision = PolicyDecision.CHALLENGE_REQUIRED,
                        explanation = "Explicit owner confirmation challenge required before engaging STOLEN_MODE.",
                        violations = violations,
                        challengeRequirement = "VERIFY_MASTER_CREDENTIALS"
                    )
                }
                if (proposedAction.authorizationToken.isNullOrBlank()) {
                    violations.add("Missing cryptographic authorization token for stolen mode engagement.")
                }
            }

            ActionType.LOCK_DEVICE_SCREEN -> {
                if (!proposedAction.hasDeviceAdminPrivilege) {
                    violations.add("Cannot execute hardware screen lock: Device Administrator privilege is inactive.")
                }
            }

            ActionType.REQUEST_REMOTE_WIPE_PLACEHOLDER -> {
                // FAIL CLOSED: Destructive wipe is prohibited by security policy
                violations.add("Emergency wipe is strictly prohibited by application policy. Destructive actions fail closed.")
                return PolicyEvaluationResult(
                    decision = PolicyDecision.REJECTED,
                    explanation = "Action rejected by security baseline policy: Remote factory reset is disabled.",
                    violations = violations
                )
            }

            ActionType.DISARM_SYSTEM, ActionType.MARK_RECOVERED -> {
                if (proposedAction.authorizationToken.isNullOrBlank()) {
                    violations.add("Disarming requires verified master authentication token.")
                }
            }

            ActionType.CHANGE_SECURITY_STATE -> {
                val target = proposedAction.targetState
                if (target == SecurityState.DANGER && proposedAction.currentRiskScore < 50) {
                    violations.add("Cannot escalate to DANGER: risk score (${proposedAction.currentRiskScore}) is below required threshold (50).")
                }
                if (target == SecurityState.SUSPECTED_LOST && proposedAction.currentRiskScore < 80) {
                    violations.add("Cannot escalate to SUSPECTED_LOST: risk score (${proposedAction.currentRiskScore}) is below required threshold (80).")
                }
                if ((target == SecurityState.OWNER_CONFIRMED_STOLEN || target == SecurityState.STOLEN_MODE) && !proposedAction.isOwnerExplicitlyConfirmed) {
                    violations.add("Cannot escalate to Stolen states without explicit owner verification.")
                }
                if (target == SecurityState.RECOVERY_MODE && proposedAction.authorizationToken.isNullOrBlank()) {
                    violations.add("Activating RECOVERY_MODE requires a verified owner authorization token.")
                }
            }

            ActionType.TRIGGER_HIGH_FREQUENCY_TRACKING -> {
                if (proposedAction.currentRiskScore < 30) {
                    violations.add("High frequency tracking unnecessary for nominal risk levels (<30).")
                }
            }
        }

        return if (violations.isEmpty()) {
            PolicyEvaluationResult(
                decision = PolicyDecision.APPROVED,
                explanation = "Action '${proposedAction.actionType.name}' authorized and compliant with InkingiGuard policy rules."
            )
        } else {
            PolicyEvaluationResult(
                decision = PolicyDecision.REJECTED,
                explanation = "Action '${proposedAction.actionType.name}' rejected due to policy violations: ${violations.joinToString("; ")}",
                violations = violations
            )
        }
    }
}
