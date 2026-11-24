package com.example.domain.device

import android.content.Context
import android.telephony.TelephonyManager
import com.example.core.model.SimSecurityInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SimSecurityEngine(
    private val context: Context
) {

    private val telephonyManager =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    private var registeredCarrierName: String? = null
    private var registeredOperatorNumeric: String? = null

    private val _simInfo = MutableStateFlow(inspectSim())
    val simInfo: StateFlow<SimSecurityInfo> = _simInfo.asStateFlow()

    fun inspectSim(): SimSecurityInfo {
        val tm = telephonyManager ?: return SimSecurityInfo(
            simState = "UNAVAILABLE",
            isSimPresent = false,
            carrierName = "Unknown",
            simOperatorNumeric = ""
        )

        val stateStr = when (tm.simState) {
            TelephonyManager.SIM_STATE_READY -> "READY"
            TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_LOCKED"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_LOCKED"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
            else -> "UNKNOWN"
        }

        val simName = tm.simOperatorName ?: ""
        val netName = tm.networkOperatorName ?: ""
        val carrier = simName.ifBlank { netName.ifBlank { "Mobile Carrier" } }
        val operator = (tm.simOperator ?: "").ifBlank { tm.networkOperator ?: "" }

        if (registeredCarrierName == null && carrier.isNotBlank()) {
            registeredCarrierName = carrier
            registeredOperatorNumeric = operator
        }

        val hasChanged = if (registeredCarrierName != null && registeredCarrierName != carrier) {
            true
        } else {
            false
        }

        return SimSecurityInfo(
            simState = stateStr,
            isSimPresent = tm.simState == TelephonyManager.SIM_STATE_READY,
            carrierName = carrier,
            simOperatorNumeric = operator,
            hasSimChanged = hasChanged
        )
    }

    fun triggerSimulatedSimAlteration() {
        _simInfo.value = _simInfo.value.copy(
            hasSimChanged = true,
            carrierName = "UNAUTHORIZED_UNKNOWN_CARRIER",
            simState = "ALTERED_UNRECOGNIZED"
        )
    }

    fun clearSimAlteration() {
        _simInfo.value = inspectSim().copy(hasSimChanged = false)
    }
}
