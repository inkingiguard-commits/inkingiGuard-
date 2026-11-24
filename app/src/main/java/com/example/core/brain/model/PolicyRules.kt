package com.example.core.brain.model

import com.example.core.model.SecurityState

enum class ActionType {
    CHANGE_SECURITY_STATE,
    ACTIVATE_STOLEN_MODE,
    LOCK_DEVICE_SCREEN,
    TRIGGER_HIGH_FREQUENCY_TRACKING,
    MARK_RECOVERED,
    DISARM_SYSTEM,
    REQUEST_REMOTE_WIPE_PLACEHOLDER
}

enum class PolicyDecision {
    APPROVED,
    REJECTED,
    CHALLENGE_REQUIRED
}

data class ProposedAction(
    val actionType: ActionType,
    val deviceId: String,
    val requestingActor: String,
    val targetState: SecurityState? = null,
    val authorizationToken: String? = null,
    val isOwnerExplicitlyConfirmed: Boolean = false,
    val hasDeviceAdminPrivilege: Boolean = false,
    val currentRiskScore: Int = 0,
    val timestamp: Long = System.currentTimeMillis()
)

data class PolicyEvaluationResult(
    val decision: PolicyDecision,
    val explanation: String,
    val violations: List<String> = emptyList(),
    val challengeRequirement: String? = null
)
