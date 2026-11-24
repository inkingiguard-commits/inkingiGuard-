package com.example.presentation.recovery

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.core.model.TrustedZone
import com.example.domain.recovery.LocationBreadcrumbPoint

/**
 * Real Recovery Map & Phone Traceability Display.
 * Integrates OpenStreetMap interactive tile map with live accuracy radius,
 * pulsing target beacon, historical breadcrumb trail, date filtering,
 * freshness/offline/stale distinction, and tactical radar fallback.
 */
@Composable
fun RealRecoveryMapView(
    deviceId: String,
    latitude: Double?,
    longitude: Double?,
    accuracy: Float?,
    timestamp: Long,
    battery: String,
    isCharging: Boolean,
    connectivity: String,
    securityStatus: String,
    isGpsAvailable: Boolean,
    historyPoints: List<LocationBreadcrumbPoint>,
    trustedZones: List<TrustedZone> = emptyList(),
    deviceName: String = deviceId,
    isFreshFix: Boolean = false,
    locationSource: String = "SYSTEM",
    altitude: Double? = null,
    speed: Float? = null,
    bearing: Float? = null,
    syncStatus: String = "LOCAL",
    lastSyncedAt: Long? = null,
    selectedDateMillis: Long? = null,
    onSelectDate: ((Long) -> Unit)? = null,
    isEn: Boolean = false,
    isRemoteDevice: Boolean = false,
    hasLocationPermission: Boolean = true,
    isTrackingActive: Boolean = false,
    isNetworkConnected: Boolean = true,
    onRetryGps: (() -> Unit)? = null,
    onRequestLocationPermission: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    MapLibreOsmMapView(
        deviceId = deviceId,
        latitude = latitude,
        longitude = longitude,
        accuracy = accuracy,
        timestamp = timestamp,
        battery = battery,
        isCharging = isCharging,
        connectivity = connectivity,
        securityStatus = securityStatus,
        isGpsAvailable = isGpsAvailable,
        historyPoints = historyPoints,
        trustedZones = trustedZones,
        deviceName = deviceName,
        isFreshFix = isFreshFix,
        locationSource = locationSource,
        altitude = altitude,
        speed = speed,
        bearing = bearing,
        syncStatus = syncStatus,
        lastSyncedAt = lastSyncedAt,
        selectedDateMillis = selectedDateMillis,
        onSelectDate = onSelectDate,
        isEn = isEn,
        isRemoteDevice = isRemoteDevice,
        hasLocationPermission = hasLocationPermission,
        isTrackingActive = isTrackingActive,
        isNetworkConnected = isNetworkConnected,
        onRetryGps = onRetryGps,
        onRequestLocationPermission = onRequestLocationPermission,
        modifier = modifier
    )
}
