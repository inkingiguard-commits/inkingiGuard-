package com.example.domain.management

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import com.example.core.model.DeviceManagementCapability

open class DeviceLockManager(
    private val context: Context
) {

    private val devicePolicyManager: DevicePolicyManager? =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager

    val adminComponent = ComponentName(context, InkingiDeviceAdminReceiver::class.java)

    sealed class LockExecutionResult {
        object Success : LockExecutionResult()
        data class Unavailable(val reason: String, val enrollmentRequired: Boolean) : LockExecutionResult()
        data class SecurityError(val message: String) : LockExecutionResult()
    }

    /**
     * Inspects runtime device management capabilities.
     * Never assumes the app is Device Owner or Device Admin.
     */
    open fun getCapabilities(): DeviceManagementCapability {
        val dpm = devicePolicyManager
        val packageName = context.packageName

        val isAdminActive = dpm?.isAdminActive(adminComponent) ?: false
        val isDeviceOwner = if (dpm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try { dpm.isDeviceOwnerApp(packageName) } catch (_: Exception) { false }
        } else false

        val isProfileOwner = if (dpm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try { dpm.isProfileOwnerApp(packageName) } catch (_: Exception) { false }
        } else false

        val canLock = isAdminActive || isDeviceOwner || isProfileOwner

        val note = when {
            isDeviceOwner -> "Fully provisioned as Enterprise Device Owner. Full policy control active."
            isAdminActive -> "Active Android Device Administrator. Screen lockNow() capability enabled."
            else -> "Standard Application Mode. Device Admin permission not granted; dpm.lockNow() requires user activation in Settings -> Security -> Device Admin."
        }

        return DeviceManagementCapability(
            isDeviceOwner = isDeviceOwner,
            isProfileOwner = isProfileOwner,
            isAdminActive = isAdminActive,
            canLockDevice = canLock,
            canApplyManagedPolicies = isDeviceOwner || isProfileOwner,
            canPerformAuthorizedRecoveryActions = canLock,
            provisioningRequirementNote = note
        )
    }

    /**
     * Attempts to lock the device using legitimate Android DevicePolicyManager.
     * If privileges are unavailable, reports Unavailable instead of faking the action.
     */
    open fun lockDeviceNow(): LockExecutionResult {
        val dpm = devicePolicyManager
            ?: return LockExecutionResult.Unavailable("DevicePolicyManager service not found on this system.", false)

        val capabilities = getCapabilities()
        if (!capabilities.canLockDevice) {
            return LockExecutionResult.Unavailable(
                "Device lock unavailable: App is not activated as Device Administrator. Enable Device Admin in InkingiGuard Settings to permit hardware screen lock.",
                enrollmentRequired = true
            )
        }

        return try {
            dpm.lockNow()
            LockExecutionResult.Success
        } catch (e: SecurityException) {
            LockExecutionResult.SecurityError("SecurityException executing lockNow(): ${e.message}")
        } catch (e: Exception) {
            LockExecutionResult.SecurityError("Unexpected error executing lockNow(): ${e.message}")
        }
    }

    /**
     * Deprecated password mutation intentionally disabled for Google Play / least-privilege safety.
     * Executes only [lockDeviceNow] when Device Admin is active; never resets OS credentials.
     */
    @Deprecated("Password reset policy disabled for least-privilege safety; use lockDeviceNow().")
    open fun resetPasswordAndLockNow(pin: String): LockExecutionResult {
        return lockDeviceNow()
    }

    /**
     * Sets a recovery message on the device lock screen if supported by Android DevicePolicyManager.
     * On Android N (API 24)+, device owner or profile owner can call setDeviceOwnerLockScreenInfo.
     * Returns true if applied via DPM, false if unsupported (fallback to notification).
     */
    open fun setLockScreenRecoveryMessage(message: String): Boolean {
        val dpm = devicePolicyManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                if (dpm.isDeviceOwnerApp(context.packageName)) {
                    dpm.setDeviceOwnerLockScreenInfo(adminComponent, message)
                    true
                } else {
                    false
                }
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
