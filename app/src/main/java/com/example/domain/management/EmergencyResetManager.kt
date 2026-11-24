package com.example.domain.management

import android.content.Context

/**
 * Emergency Factory Reset Safeguard.
 *
 * CRITICAL SAFETY PRINCIPLE:
 * FAIL CLOSED FOR DESTRUCTIVE ACTIONS.
 * Remote or automatic factory reset is strictly prohibited; `<wipe-data />` is intentionally
 * excluded from Device Administration policies so destructive erasure cannot be triggered.
 */
class EmergencyResetManager(
    private val context: Context,
    private val lockManager: DeviceLockManager
) {

    sealed class ResetAuthorizationResult {
        object ProhibitedByPolicy : ResetAuthorizationResult()
        data class MissingPrivileges(val reason: String) : ResetAuthorizationResult()
        data class ConfirmationRequired(val challengeToken: String) : ResetAuthorizationResult()
    }

    fun requestWipeAuthorization(
        isOwnerConfirmed: Boolean,
        hasExplicitDoubleConfirmation: Boolean
    ): ResetAuthorizationResult {
        val capabilities = lockManager.getCapabilities()

        if (!capabilities.canApplyManagedPolicies && !capabilities.isDeviceOwner) {
            return ResetAuthorizationResult.MissingPrivileges(
                "Emergency reset unavailable: Application does not possess Device Owner management privileges required for legitimate wipeData() API execution."
            )
        }

        if (!isOwnerConfirmed || !hasExplicitDoubleConfirmation) {
            return ResetAuthorizationResult.ConfirmationRequired(
                "Destructive wipe blocked: Strict 2-step owner verification required. Fails closed by design."
            )
        }

        // Even with privileges, safeguard prevents automatic execution without explicit user manual verification
        return ResetAuthorizationResult.ProhibitedByPolicy
    }
}
