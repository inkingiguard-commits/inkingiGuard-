package com.example.domain.device

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.example.core.model.NetworkSecurityInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class NetworkIntelligenceEngine(
    private val context: Context
) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _networkInfo = MutableStateFlow(inspectCurrentNetwork())
    val networkInfo: StateFlow<NetworkSecurityInfo> = _networkInfo.asStateFlow()

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    init {
        registerCallback()
    }

    private fun registerCallback() {
        val cm = connectivityManager ?: return
        try {
            val builder = NetworkRequest.Builder()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    _networkInfo.value = inspectCurrentNetwork()
                }

                override fun onLost(network: Network) {
                    _networkInfo.value = inspectCurrentNetwork()
                }

                override fun onCapabilitiesChanged(
                    network: Network,
                    networkCapabilities: NetworkCapabilities
                ) {
                    _networkInfo.value = inspectCurrentNetwork()
                }
            }
            networkCallback = callback
            cm.registerNetworkCallback(builder.build(), callback)
        } catch (_: Exception) {}
    }

    fun inspectCurrentNetwork(): NetworkSecurityInfo {
        val cm = connectivityManager ?: return NetworkSecurityInfo(
            isConnected = false,
            isWifi = false,
            isCellular = false,
            networkType = "DISCONNECTED"
        )

        val activeNetwork = cm.activeNetwork
        val caps = cm.getNetworkCapabilities(activeNetwork)

        if (caps == null) {
            return NetworkSecurityInfo(
                isConnected = false,
                isWifi = false,
                isCellular = false,
                networkType = "DISCONNECTED"
            )
        }

        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        val type = when {
            isVpn -> "ENCRYPTED_VPN"
            isWifi -> "SECURE_WIFI"
            isCellular -> "CELLULAR_LTE_5G"
            else -> "OTHER"
        }

        return NetworkSecurityInfo(
            isConnected = true,
            isWifi = isWifi,
            isCellular = isCellular,
            networkType = type,
            isVpnActive = isVpn
        )
    }

    fun triggerSimulatedNetworkDrop() {
        _networkInfo.value = NetworkSecurityInfo(
            isConnected = false,
            isWifi = false,
            isCellular = false,
            networkType = "UNEXPECTED_RADIO_DISCONNECTION"
        )
    }

    fun restoreNetwork() {
        _networkInfo.value = inspectCurrentNetwork()
    }

    fun cleanup() {
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Exception) {}
    }
}
