package com.example.domain.management

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * Standard Android Device Administration Receiver.
 * Enables legitimate DevicePolicyManager capabilities such as lockNow()
 * when activated by the user or enterprise provisioning.
 */
class InkingiDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "InkingiGuard: Device Admin Authorized", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "WARNING: Disabling InkingiGuard Device Administration reduces hardware protection and recovery capabilities."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "InkingiGuard: Device Admin Deactivated", Toast.LENGTH_SHORT).show()
    }
}
