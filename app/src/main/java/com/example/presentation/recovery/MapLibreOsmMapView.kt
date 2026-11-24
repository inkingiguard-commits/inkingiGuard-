package com.example.presentation.recovery

import android.annotation.SuppressLint
import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.GpsNotFixed
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.core.model.TrustedZone
import com.example.domain.location.LocationBreadcrumbPolicy
import com.example.domain.recovery.LocationBreadcrumbPoint
import com.example.ui.theme.InkingiCyan
import com.example.ui.theme.InkingiNavyBorder
import com.example.ui.theme.InkingiNavyCard
import com.example.ui.theme.InkingiNavyDark
import com.example.ui.theme.InkingiRiskHigh
import com.example.ui.theme.InkingiRiskLow
import com.example.ui.theme.InkingiRiskMedium
import com.example.ui.theme.InkingiTextMuted
import com.example.ui.theme.InkingiTextPrimary
import com.example.ui.theme.InkingiTextSecondary
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

internal enum class WaitingMapCondition {
    PERMISSION_DENIED,
    GPS_DISABLED,
    WAITING_FIRST_FIX,
    UNAVAILABLE
}

internal enum class MapTileErrorType {
    NONE,
    OFFLINE_INTERNET_REQUIRED,
    LEAFLET_SCRIPT_ERROR,
    TILE_LOAD_ERROR,
    PAGE_LOAD_ERROR
}

internal data class MapWebViewState(
    val isPageLoading: Boolean = true,
    val isLeafletInitialized: Boolean = false,
    val loadedTileCount: Int = 0,
    val failedTileCount: Int = 0,
    val errorType: MapTileErrorType = MapTileErrorType.NONE,
    val errorDetail: String? = null
)

internal object MapWebViewStateReducer {
    fun onPageStarted(isNetworkConnected: Boolean): MapWebViewState {
        return if (!isNetworkConnected) {
            MapWebViewState(
                isPageLoading = false,
                isLeafletInitialized = false,
                errorType = MapTileErrorType.OFFLINE_INTERNET_REQUIRED,
                errorDetail = "No active internet connection for OpenStreetMap/Leaflet tiles."
            )
        } else {
            MapWebViewState(
                isPageLoading = true,
                isLeafletInitialized = false,
                loadedTileCount = 0,
                failedTileCount = 0,
                errorType = MapTileErrorType.NONE,
                errorDetail = null
            )
        }
    }

    fun onPageFinished(current: MapWebViewState, isNetworkConnected: Boolean): MapWebViewState {
        return if (!isNetworkConnected && current.errorType == MapTileErrorType.NONE) {
            current.copy(
                isPageLoading = false,
                errorType = MapTileErrorType.OFFLINE_INTERNET_REQUIRED,
                errorDetail = "Internet connection is required to load OpenStreetMap tiles."
            )
        } else {
            current.copy(isPageLoading = false)
        }
    }

    fun onLeafletReady(current: MapWebViewState): MapWebViewState {
        return current.copy(
            isPageLoading = false,
            isLeafletInitialized = true,
            errorType = if (current.errorType == MapTileErrorType.LEAFLET_SCRIPT_ERROR) {
                MapTileErrorType.NONE
            } else {
                current.errorType
            }
        )
    }

    fun onTileLoaded(current: MapWebViewState): MapWebViewState {
        val nextLoaded = current.loadedTileCount + 1
        val nextError = if (current.errorType == MapTileErrorType.TILE_LOAD_ERROR ||
            current.errorType == MapTileErrorType.OFFLINE_INTERNET_REQUIRED
        ) {
            MapTileErrorType.NONE
        } else {
            current.errorType
        }
        return current.copy(
            loadedTileCount = nextLoaded,
            errorType = nextError,
            errorDetail = if (nextError == MapTileErrorType.NONE) null else current.errorDetail
        )
    }

    fun onTileError(current: MapWebViewState, isNetworkConnected: Boolean): MapWebViewState {
        val nextFailed = current.failedTileCount + 1
        if (!isNetworkConnected) {
            return current.copy(
                failedTileCount = nextFailed,
                errorType = MapTileErrorType.OFFLINE_INTERNET_REQUIRED,
                errorDetail = "OpenStreetMap tiles require internet access."
            )
        }
        return if (nextFailed >= 3 && current.loadedTileCount == 0) {
            current.copy(
                failedTileCount = nextFailed,
                errorType = MapTileErrorType.TILE_LOAD_ERROR,
                errorDetail = "Unable to download OpenStreetMap tiles."
            )
        } else {
            current.copy(failedTileCount = nextFailed)
        }
    }

    fun onResourceOrPageError(
        current: MapWebViewState,
        requestUrl: String?,
        isMainFrame: Boolean,
        detail: String?,
        isNetworkConnected: Boolean
    ): MapWebViewState {
        if (!isNetworkConnected) {
            return current.copy(
                isPageLoading = false,
                errorType = MapTileErrorType.OFFLINE_INTERNET_REQUIRED,
                errorDetail = detail ?: "Internet connection required for OpenStreetMap/Leaflet tiles."
            )
        }
        val urlLower = requestUrl.orEmpty().lowercase(Locale.US)
        return when {
            urlLower.contains("leaflet") || urlLower.contains("unpkg.com") -> {
                current.copy(
                    isPageLoading = false,
                    isLeafletInitialized = false,
                    errorType = MapTileErrorType.LEAFLET_SCRIPT_ERROR,
                    errorDetail = detail ?: "Failed to load Leaflet map library from CDN."
                )
            }
            urlLower.contains("tile.openstreetmap.org") -> {
                onTileError(current, isNetworkConnected = true)
            }
            isMainFrame -> {
                current.copy(
                    isPageLoading = false,
                    errorType = MapTileErrorType.PAGE_LOAD_ERROR,
                    errorDetail = detail ?: "Map container failed to load."
                )
            }
            else -> current
        }
    }

    fun onJsBridgeError(
        current: MapWebViewState,
        errorCode: String?,
        detail: String?,
        isNetworkConnected: Boolean
    ): MapWebViewState {
        if (!isNetworkConnected) {
            return current.copy(
                isPageLoading = false,
                errorType = MapTileErrorType.OFFLINE_INTERNET_REQUIRED,
                errorDetail = detail ?: "Internet access is required to load OpenStreetMap tiles."
            )
        }
        val type = when (errorCode?.uppercase(Locale.US)) {
            "LEAFLET_SCRIPT_ERROR" -> MapTileErrorType.LEAFLET_SCRIPT_ERROR
            "TILE_LOAD_ERROR" -> MapTileErrorType.TILE_LOAD_ERROR
            else -> MapTileErrorType.PAGE_LOAD_ERROR
        }
        return current.copy(
            isPageLoading = false,
            errorType = type,
            errorDetail = detail ?: "Map rendering error."
        )
    }

    fun onConnectivityChanged(current: MapWebViewState, isNetworkConnected: Boolean): MapWebViewState {
        return if (!isNetworkConnected) {
            current.copy(
                isPageLoading = false,
                errorType = MapTileErrorType.OFFLINE_INTERNET_REQUIRED,
                errorDetail = "Internet connection unavailable. OpenStreetMap/Leaflet tiles require internet access."
            )
        } else if (current.errorType == MapTileErrorType.OFFLINE_INTERNET_REQUIRED) {
            current.copy(
                errorType = MapTileErrorType.NONE,
                errorDetail = null
            )
        } else {
            current
        }
    }
}

internal object RecoveryMapStatusFormatter {
    fun resolveWaitingCondition(
        hasValidCoords: Boolean,
        hasLocationPermission: Boolean,
        isGpsAvailable: Boolean,
        isTrackingActive: Boolean
    ): WaitingMapCondition? {
        if (hasValidCoords) return null
        return when {
            !hasLocationPermission -> WaitingMapCondition.PERMISSION_DENIED
            !isGpsAvailable -> WaitingMapCondition.GPS_DISABLED
            isTrackingActive -> WaitingMapCondition.WAITING_FIRST_FIX
            else -> WaitingMapCondition.UNAVAILABLE
        }
    }

    fun resolveTraceStatus(
        hasValidCoords: Boolean,
        isGpsAvailable: Boolean,
        timestampMs: Long,
        isFreshFix: Boolean,
        connectivity: String,
        isEn: Boolean = false,
        nowMs: Long = System.currentTimeMillis(),
        hasLocationPermission: Boolean = true,
        isTrackingActive: Boolean = false
    ): String {
        val isOffline = connectivity.contains("OFFLINE", ignoreCase = true) ||
            connectivity.contains("DÉCONNECTÉ", ignoreCase = true) ||
            connectivity.contains("HORS LIGNE", ignoreCase = true) ||
            connectivity.contains("DISCONNECTED", ignoreCase = true)
        val isStale = LocationBreadcrumbPolicy.isLocationStale(timestampMs, isFreshFix, nowMs)

        return when {
            !hasValidCoords && !hasLocationPermission ->
                if (isEn) "GPS PERMISSION DENIED" else "PERMISSION GPS REFUSÉE"
            !isGpsAvailable && !hasValidCoords ->
                if (isEn) "GPS DISABLED" else "GPS DÉSACTIVÉ"
            !hasValidCoords && isTrackingActive ->
                if (isEn) "WAITING FOR FIRST GPS FIX" else "EN ATTENTE DU PREMIER FIX GPS"
            !hasValidCoords ->
                if (isEn) "LOCATION UNAVAILABLE" else "LOCALISATION INDISPONIBLE"
            isOffline -> if (isEn) "OFFLINE (LAST KNOWN)" else "HORS LIGNE (DERNIÈRE POSITION)"
            !isStale -> if (isEn) "LIVE" else "EN DIRECT"
            else -> if (isEn) "LAST KNOWN POSITION" else "DERNIÈRE POSITION CONNUE"
        }
    }
}

internal fun deduplicateHistoryBreadcrumbs(
    history: List<LocationBreadcrumbPoint>
): List<LocationBreadcrumbPoint> {
    val validSorted = history
        .filter { LocationBreadcrumbPolicy.isValidCoordinate(it.latitude, it.longitude) }
        .sortedBy { it.timestamp }
    if (validSorted.isEmpty()) return emptyList()

    val result = mutableListOf<LocationBreadcrumbPoint>()
    for (point in validSorted) {
        val last = result.lastOrNull()
        val isDuplicate = last != null &&
            kotlin.math.abs(last.latitude - point.latitude) < 1e-6 &&
            kotlin.math.abs(last.longitude - point.longitude) < 1e-6 &&
            kotlin.math.abs(last.timestamp - point.timestamp) < 5_000L
        if (!isDuplicate) {
            result.add(point)
        }
    }
    return result
}

internal fun serializeHistoryBreadcrumbsJson(history: List<LocationBreadcrumbPoint>): String {
    val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val items = deduplicateHistoryBreadcrumbs(history).joinToString(separator = ",") { pt ->
        val labelTime = sdf.format(Date(pt.timestamp))
        val batText = if (pt.batteryLevel >= 0) "${pt.batteryLevel}%" else "—"
        "{\"lat\":${pt.latitude},\"lng\":${pt.longitude},\"acc\":${pt.accuracy},\"time\":\"$labelTime\",\"bat\":\"$batText\",\"fresh\":${pt.isFreshFix}}"
    }
    return "[$items]"
}

internal fun buildUpdateMapStateJs(
    latitude: Double,
    longitude: Double,
    accuracyMeters: Float,
    isLive: Boolean,
    shouldFollow: Boolean,
    historyJson: String,
    isRemoteDevice: Boolean
): String {
    val liveFlag = if (isLive) "true" else "false"
    val followFlag = if (shouldFollow) "true" else "false"
    val remoteFlag = if (isRemoteDevice) "true" else "false"
    return "if (typeof updateMapState === 'function') { updateMapState($latitude, $longitude, $accuracyMeters, $liveFlag, $followFlag, $historyJson, $remoteFlag); }"
}

private class InkingiMapJsBridge(
    private val onReady: () -> Unit,
    private val onTileLoaded: () -> Unit,
    private val onTileError: () -> Unit,
    private val onError: (String?, String?) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onMapReady() {
        mainHandler.post { onReady() }
    }

    @JavascriptInterface
    fun onTileLoaded() {
        mainHandler.post { onTileLoaded() }
    }

    @JavascriptInterface
    fun onTileError() {
        mainHandler.post { onTileError() }
    }

    @JavascriptInterface
    fun onMapError(code: String?, detail: String?) {
        mainHandler.post { onError(code, detail) }
    }
}

/**
 * OpenStreetMap Interactive Map for InkingiGuard Anti-Theft Traceability & Remote Tracking.
 * Distinguishes live GPS fixes from cached/stale last-known positions, distinguishes local vs
 * remote devices, renders a 240.dp waiting/denied viewport instead of collapsing or fabricating
 * coordinates, and reports WebView / OSM tile failures honestly with retry controls.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun MapLibreOsmMapView(
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
    val context = LocalContext.current
    val hasValidCoords = LocationBreadcrumbPolicy.isValidCoordinate(latitude, longitude)
    val isConnectivityOffline = connectivity.contains("OFFLINE", ignoreCase = true) ||
        connectivity.contains("DÉCONNECTÉ", ignoreCase = true) ||
        connectivity.contains("HORS LIGNE", ignoreCase = true) ||
        connectivity.contains("DISCONNECTED", ignoreCase = true)
    val effectiveNetworkConnected = isNetworkConnected && !isConnectivityOffline

    val isLivePosition = hasValidCoords &&
        !LocationBreadcrumbPolicy.isLocationStale(timestamp, isFreshFix) &&
        !isConnectivityOffline

    val traceStatus = RecoveryMapStatusFormatter.resolveTraceStatus(
        hasValidCoords = hasValidCoords,
        isGpsAvailable = isGpsAvailable,
        timestampMs = timestamp,
        isFreshFix = isFreshFix,
        connectivity = connectivity,
        isEn = isEn,
        hasLocationPermission = hasLocationPermission,
        isTrackingActive = isTrackingActive
    )

    val waitingCondition = RecoveryMapStatusFormatter.resolveWaitingCondition(
        hasValidCoords = hasValidCoords,
        hasLocationPermission = hasLocationPermission,
        isGpsAvailable = isGpsAvailable,
        isTrackingActive = isTrackingActive
    )

    val isStaleWarning = traceStatus.contains("HORS LIGNE") ||
        traceStatus.contains("OFFLINE") ||
        traceStatus.contains("ATTENTE") ||
        traceStatus.contains("WAITING")

    val statusColor = when {
        isLivePosition -> InkingiRiskLow
        isRemoteDevice && hasValidCoords -> Color(0xFFFF5252)
        isStaleWarning -> InkingiRiskMedium
        hasValidCoords -> InkingiCyan
        else -> InkingiRiskHigh
    }

    var isMapModeActive by remember { mutableStateOf(true) }
    var isFollowingCamera by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var mapReloadTrigger by remember { mutableIntStateOf(0) }
    var webViewState by remember(deviceId, isRemoteDevice) {
        mutableStateOf(MapWebViewStateReducer.onPageStarted(effectiveNetworkConnected))
    }

    LaunchedEffect(effectiveNetworkConnected) {
        webViewState = MapWebViewStateReducer.onConnectivityChanged(webViewState, effectiveNetworkConnected)
    }

    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.stopLoading()
            webViewRef = null
        }
    }

    val deduplicatedHistory = remember(historyPoints) {
        deduplicateHistoryBreadcrumbs(historyPoints)
    }
    val historyPayloadJson = remember(deduplicatedHistory) {
        serializeHistoryBreadcrumbsJson(deduplicatedHistory)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, InkingiNavyBorder, RoundedCornerShape(14.dp)),
        colors = CardDefaults.cardColors(containerColor = InkingiNavyDark)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header Row: Device Name + Local/Remote Scope + Freshness Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = when {
                            hasValidCoords && isLivePosition -> Icons.Default.GpsFixed
                            hasValidCoords -> Icons.Default.GpsNotFixed
                            else -> Icons.Default.GpsOff
                        },
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = deviceName.ifBlank { deviceId },
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                color = InkingiTextPrimary
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        if (isRemoteDevice) Color(0xFFFF5252).copy(alpha = 0.16f)
                                        else InkingiCyan.copy(alpha = 0.14f)
                                    )
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                                    .testTag("map_device_scope_badge")
                            ) {
                                Text(
                                    text = if (isRemoteDevice) {
                                        if (isEn) "REMOTE" else "DISTANT"
                                    } else {
                                        if (isEn) "THIS DEVICE" else "CET APPAREIL"
                                    },
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isRemoteDevice) Color(0xFFFF5252) else InkingiCyan
                                )
                            }
                        }
                        if (deviceName.isNotBlank() && deviceName != deviceId) {
                            Text(
                                text = "ID: $deviceId",
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = InkingiTextMuted,
                                fontSize = 10.sp
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(statusColor.copy(alpha = 0.15f))
                        .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                        .testTag("map_trace_status_badge")
                ) {
                    Text(
                        text = traceStatus,
                        color = statusColor,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }

            // Map View Switcher: Interactive OSM Map vs. Cyber Radar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(InkingiNavyCard)
                    .border(1.dp, InkingiNavyBorder, RoundedCornerShape(8.dp))
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isMapModeActive) InkingiCyan.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { isMapModeActive = true }
                        .padding(vertical = 8.dp)
                        .testTag("switch_to_osm_map_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Map,
                            contentDescription = null,
                            tint = if (isMapModeActive) InkingiCyan else InkingiTextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (isEn) "OPENSTREETMAP" else "CARTE OPENSTREETMAP",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isMapModeActive) InkingiCyan else InkingiTextMuted
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (!isMapModeActive) InkingiCyan.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { isMapModeActive = false }
                        .padding(vertical = 8.dp)
                        .testTag("switch_to_radar_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Radar,
                            contentDescription = null,
                            tint = if (!isMapModeActive) InkingiCyan else InkingiTextMuted,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (isEn) "CYBER RADAR" else "RADAR TACTIQUE CYBER",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (!isMapModeActive) InkingiCyan else InkingiTextMuted
                        )
                    }
                }
            }

            // Date selector bar for breadcrumb movement inspection
            if (onSelectDate != null && selectedDateMillis != null) {
                val selectedDateStr = remember(selectedDateMillis) {
                    SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(selectedDateMillis))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { onSelectDate(System.currentTimeMillis()) },
                        modifier = Modifier.height(38.dp).testTag("history_date_today_button")
                    ) {
                        Text(if (isEn) "Today" else "Aujourd'hui", fontSize = 10.sp, color = InkingiCyan)
                    }
                    OutlinedButton(
                        onClick = { onSelectDate(System.currentTimeMillis() - 86_400_000L) },
                        modifier = Modifier.height(38.dp).testTag("history_date_yesterday_button")
                    ) {
                        Text(if (isEn) "Yesterday" else "Hier", fontSize = 10.sp, color = InkingiTextSecondary)
                    }
                    OutlinedButton(
                        onClick = {
                            val cal = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
                            DatePickerDialog(
                                context,
                                { _, year, month, dayOfMonth ->
                                    val picked = Calendar.getInstance().apply {
                                        set(year, month, dayOfMonth, 12, 0, 0)
                                    }.timeInMillis
                                    onSelectDate(picked)
                                },
                                cal.get(Calendar.YEAR),
                                cal.get(Calendar.MONTH),
                                cal.get(Calendar.DAY_OF_MONTH)
                            ).apply {
                                datePicker.maxDate = System.currentTimeMillis()
                            }.show()
                        },
                        modifier = Modifier.weight(1f).height(38.dp).testTag("history_date_picker_button")
                    ) {
                        Icon(Icons.Default.CalendarToday, contentDescription = null, tint = InkingiCyan, modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(selectedDateStr, fontSize = 10.sp, color = InkingiTextPrimary)
                    }
                }
            }

            // Interactive Map Viewport OR Honest 240.dp Waiting / Permission / Unavailable Frame
            if (hasValidCoords && latitude != null && longitude != null) {
                val safeAcc = (accuracy ?: 15f).coerceAtLeast(1f)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(InkingiNavyCard)
                        .border(1.dp, InkingiCyan.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .testTag("recovery_map_viewport")
                ) {
                    if (isMapModeActive) {
                        val initialHtmlContent = remember(deviceId, isRemoteDevice, mapReloadTrigger) {
                            generateLeafletOsmHtml(
                                lat = latitude,
                                lng = longitude,
                                accuracyMeters = safeAcc,
                                isLive = isLivePosition,
                                isRemoteDevice = isRemoteDevice,
                                historyJson = historyPayloadJson,
                                zones = trustedZones
                            )
                        }

                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.javaScriptEnabled = true
                                    settings.domStorageEnabled = true
                                    settings.cacheMode = WebSettings.LOAD_DEFAULT
                                    settings.userAgentString = "InkingiGuard-AI/1.0 (Android; contact@inkingiguard.com)"
                                    addJavascriptInterface(
                                        InkingiMapJsBridge(
                                            onReady = {
                                                webViewState = MapWebViewStateReducer.onLeafletReady(webViewState)
                                            },
                                            onTileLoaded = {
                                                webViewState = MapWebViewStateReducer.onTileLoaded(webViewState)
                                            },
                                            onTileError = {
                                                webViewState = MapWebViewStateReducer.onTileError(
                                                    webViewState,
                                                    effectiveNetworkConnected
                                                )
                                            },
                                            onError = { code, detail ->
                                                webViewState = MapWebViewStateReducer.onJsBridgeError(
                                                    webViewState,
                                                    code,
                                                    detail,
                                                    effectiveNetworkConnected
                                                )
                                            }
                                        ),
                                        "InkingiMapBridge"
                                    )
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageStarted(
                                            view: WebView?,
                                            url: String?,
                                            favicon: android.graphics.Bitmap?
                                        ) {
                                            webViewState = MapWebViewStateReducer.onPageStarted(effectiveNetworkConnected)
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            webViewState = MapWebViewStateReducer.onPageFinished(
                                                webViewState,
                                                effectiveNetworkConnected
                                            )
                                            view?.evaluateJavascript(
                                                buildUpdateMapStateJs(
                                                    latitude = latitude,
                                                    longitude = longitude,
                                                    accuracyMeters = safeAcc,
                                                    isLive = isLivePosition,
                                                    shouldFollow = isFollowingCamera,
                                                    historyJson = historyPayloadJson,
                                                    isRemoteDevice = isRemoteDevice
                                                ),
                                                null
                                            )
                                        }

                                        override fun onReceivedError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            error: WebResourceError?
                                        ) {
                                            webViewState = MapWebViewStateReducer.onResourceOrPageError(
                                                current = webViewState,
                                                requestUrl = request?.url?.toString(),
                                                isMainFrame = request?.isForMainFrame == true,
                                                detail = error?.description?.toString(),
                                                isNetworkConnected = effectiveNetworkConnected
                                            )
                                        }

                                        override fun onReceivedHttpError(
                                            view: WebView?,
                                            request: WebResourceRequest?,
                                            errorResponse: WebResourceResponse?
                                        ) {
                                            if ((errorResponse?.statusCode ?: 0) >= 400) {
                                                webViewState = MapWebViewStateReducer.onResourceOrPageError(
                                                    current = webViewState,
                                                    requestUrl = request?.url?.toString(),
                                                    isMainFrame = request?.isForMainFrame == true,
                                                    detail = "HTTP ${errorResponse?.statusCode}",
                                                    isNetworkConnected = effectiveNetworkConnected
                                                )
                                            }
                                        }
                                    }
                                    webChromeClient = WebChromeClient()
                                    loadDataWithBaseURL(
                                        "https://www.openstreetmap.org",
                                        initialHtmlContent,
                                        "text/html",
                                        "UTF-8",
                                        null
                                    )
                                    webViewRef = this
                                }
                            },
                            update = { view ->
                                view.evaluateJavascript(
                                    buildUpdateMapStateJs(
                                        latitude = latitude,
                                        longitude = longitude,
                                        accuracyMeters = safeAcc,
                                        isLive = isLivePosition,
                                        shouldFollow = isFollowingCamera,
                                        historyJson = historyPayloadJson,
                                        isRemoteDevice = isRemoteDevice
                                    ),
                                    null
                                )
                            },
                            modifier = Modifier.fillMaxSize().testTag("interactive_osm_webview")
                        )

                        LaunchedEffect(mapReloadTrigger) {
                            if (mapReloadTrigger > 0) {
                                webViewState = MapWebViewStateReducer.onPageStarted(effectiveNetworkConnected)
                                webViewRef?.loadDataWithBaseURL(
                                    "https://www.openstreetmap.org",
                                    initialHtmlContent,
                                    "text/html",
                                    "UTF-8",
                                    null
                                )
                            }
                        }

                        LaunchedEffect(
                            latitude,
                            longitude,
                            safeAcc,
                            isLivePosition,
                            isRemoteDevice,
                            historyPayloadJson,
                            isFollowingCamera,
                            webViewState.isLeafletInitialized
                        ) {
                            webViewRef?.evaluateJavascript(
                                buildUpdateMapStateJs(
                                    latitude = latitude,
                                    longitude = longitude,
                                    accuracyMeters = safeAcc,
                                    isLive = isLivePosition,
                                    shouldFollow = isFollowingCamera,
                                    historyJson = historyPayloadJson,
                                    isRemoteDevice = isRemoteDevice
                                ),
                                null
                            )
                        }

                        // Floating Map Overlay Controls (48dp touch targets)
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(InkingiNavyDark.copy(alpha = 0.9f))
                                    .border(1.dp, InkingiCyan, CircleShape)
                                    .clickable {
                                        webViewRef?.evaluateJavascript(
                                            "if (typeof recenterMap === 'function') { recenterMap($latitude, $longitude); }",
                                            null
                                        )
                                    }
                                    .testTag("map_recenter_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.MyLocation,
                                    contentDescription = if (isEn) "Recenter map on device" else "Recentrer la carte",
                                    tint = InkingiCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }

                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(if (isFollowingCamera) InkingiCyan else InkingiNavyDark.copy(alpha = 0.9f))
                                    .border(1.dp, InkingiCyan, CircleShape)
                                    .clickable { isFollowingCamera = !isFollowingCamera }
                                    .testTag("map_toggle_follow_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.Navigation,
                                    contentDescription = if (isEn) "Toggle camera follow mode" else "Suivi automatique caméra",
                                    tint = if (isFollowingCamera) InkingiNavyDark else InkingiCyan,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        // Loading indicator before first tile/Leaflet ready
                        if (webViewState.isPageLoading && webViewState.errorType == MapTileErrorType.NONE) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(8.dp)
                                    .background(InkingiNavyDark.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .testTag("map_loading_indicator")
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 2.dp,
                                        color = InkingiCyan
                                    )
                                    Text(
                                        text = if (isEn) "Loading map tiles..." else "Chargement des tuiles...",
                                        fontSize = 10.sp,
                                        color = InkingiTextPrimary
                                    )
                                }
                            }
                        }

                        // Honest Error / Offline Notice Overlay (never shows silent blank box or fake offline tiles)
                        if (webViewState.errorType != MapTileErrorType.NONE) {
                            MapTileErrorOverlay(
                                errorType = webViewState.errorType,
                                detail = webViewState.errorDetail,
                                isEn = isEn,
                                onRetryMap = {
                                    mapReloadTrigger++
                                    onRetryGps?.invoke()
                                },
                                onSwitchToRadar = { isMapModeActive = false },
                                modifier = Modifier.matchParentSize()
                            )
                        }

                        // OpenStreetMap Attribution (Preserved in both live and fallback views)
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(6.dp)
                                .background(InkingiNavyDark.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "© OpenStreetMap contributors",
                                fontSize = 9.sp,
                                color = InkingiTextMuted,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Floating GPS Accuracy Tag
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp)
                                .background(InkingiNavyDark.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                                .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (isEn) "Accuracy: ±${accuracy?.toInt() ?: 0}m" else "Précision: ±${accuracy?.toInt() ?: 0}m",
                                fontSize = 10.sp,
                                color = statusColor,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        CyberRadarCanvas(
                            latitude = latitude,
                            longitude = longitude,
                            accuracy = safeAcc,
                            statusColor = statusColor,
                            historyPoints = deduplicatedHistory,
                            isEn = isEn
                        )
                    }
                }
            } else {
                // Visible 240.dp Waiting / Permission Denied / Unavailable Viewport (NEVER fabricates coordinates)
                NoCoordinatesMapViewport(
                    waitingCondition = waitingCondition ?: WaitingMapCondition.UNAVAILABLE,
                    isRemoteDevice = isRemoteDevice,
                    isEn = isEn,
                    onRetryGps = onRetryGps,
                    onRequestLocationPermission = onRequestLocationPermission,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .testTag("gps_unavailable_banner")
                )
            }

            // Real Telemetry Metrics Grid
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (hasValidCoords && latitude != null && longitude != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(if (isEn) "Coordinates:" else "Coordonnées GPS :", color = InkingiTextSecondary, fontSize = 12.sp)
                        Text(
                            text = "%.5f, %.5f".format(latitude, longitude),
                            color = InkingiTextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.testTag("map_coordinates_text")
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(if (isEn) "Fix Type:" else "Source GPS :", color = InkingiTextSecondary, fontSize = 12.sp)
                        Text(
                            text = if (isLivePosition) "$locationSource (LIVE)" else "$locationSource (${if (isEn) "Cached/Older" else "Ancienne"})",
                            color = statusColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 11.sp
                        )
                    }

                    if (altitude != null && altitude != 0.0 || speed != null && speed > 0f) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(if (isEn) "Motion / Alt:" else "Vitesse / Alt :", color = InkingiTextSecondary, fontSize = 12.sp)
                            val speedKmh = ((speed ?: 0f) * 3.6f).toInt()
                            val altText = altitude?.let { "${it.toInt()}m" } ?: "—"
                            Text(
                                text = "$speedKmh km/h • Alt $altText" + (bearing?.takeIf { it > 0f }?.let { " • ${it.toInt()}°" } ?: ""),
                                color = InkingiTextPrimary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(if (isEn) "Last Fix Time:" else "Horodatage GPS :", color = InkingiTextSecondary, fontSize = 12.sp)
                    val dateFormatted = if (timestamp > 0L) {
                        SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
                    } else "—"
                    Text(text = dateFormatted, color = InkingiTextPrimary, fontSize = 12.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.CloudDone, contentDescription = null, tint = InkingiCyan, modifier = Modifier.size(14.dp))
                        Text(if (isEn) "Cloud Sync:" else "Sync Cloud :", color = InkingiTextSecondary, fontSize = 12.sp)
                    }
                    val syncTimeStr = lastSyncedAt?.let {
                        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(it))
                    } ?: "—"
                    Text(
                        text = "$syncStatus ($syncTimeStr)",
                        color = InkingiTextPrimary,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(
                            imageVector = if (isCharging) Icons.Default.BatteryChargingFull else Icons.Default.BatteryFull,
                            contentDescription = null,
                            tint = if (isCharging) InkingiCyan else InkingiRiskLow,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(if (isEn) "Battery:" else "Batterie :", color = InkingiTextSecondary, fontSize = 12.sp)
                    }
                    Text(
                        text = "$battery ${if (isCharging) (if (isEn) "(Charging)" else "(En charge)") else ""}",
                        color = InkingiTextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = InkingiCyan, modifier = Modifier.size(14.dp))
                        Text(if (isEn) "Connectivity:" else "Connectivité :", color = InkingiTextSecondary, fontSize = 12.sp)
                    }
                    Text(text = connectivity, color = InkingiTextPrimary, fontSize = 12.sp)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(if (isEn) "Security State:" else "État de Sécurité :", color = InkingiTextSecondary, fontSize = 12.sp)
                    Text(text = securityStatus, color = statusColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            // Movement History Legend & Summary
            if (deduplicatedHistory.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Timeline, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(14.dp))
                    Text(
                        text = if (isEn)
                            "Movement Trail (${deduplicatedHistory.size} points — amber path vs current marker)"
                        else
                            "Trajet Historique (${deduplicatedHistory.size} points — tracé ambre vs position actuelle)",
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color(0xFFFFB300)
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    deduplicatedHistory.takeLast(3).reversed().forEachIndexed { idx, pt ->
                        val ptTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(pt.timestamp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "#${idx + 1} [$ptTime] %.4f, %.4f (±${pt.accuracy.toInt()}m)".format(pt.latitude, pt.longitude),
                                color = InkingiTextSecondary,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                text = if (pt.isInsideTrustedZone) {
                                    if (isEn) "Safe Zone" else "Zone sûre"
                                } else {
                                    if (isEn) "Outside" else "Extérieur"
                                },
                                color = if (pt.isInsideTrustedZone) InkingiRiskLow else InkingiRiskMedium,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            } else if (onSelectDate != null) {
                Text(
                    text = if (isEn) "No recorded movement breadcrumbs for selected date." else "Aucun point de déplacement enregistré pour cette date.",
                    color = InkingiTextMuted,
                    fontSize = 11.sp
                )
            }

            // External Maps Launch Button
            if (hasValidCoords) {
                Button(
                    onClick = {
                        try {
                            val uri = Uri.parse("https://maps.google.com/?q=$latitude,$longitude")
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("open_maps_trace_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                ) {
                    Icon(Icons.Default.Map, contentDescription = null, tint = InkingiNavyDark, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isEn) "OPEN IN GOOGLE MAPS" else "OUVRIR DANS GOOGLE MAPS",
                        color = InkingiNavyDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun NoCoordinatesMapViewport(
    waitingCondition: WaitingMapCondition,
    isRemoteDevice: Boolean,
    isEn: Boolean,
    onRetryGps: (() -> Unit)?,
    onRequestLocationPermission: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val borderColor = when (waitingCondition) {
        WaitingMapCondition.PERMISSION_DENIED, WaitingMapCondition.GPS_DISABLED -> InkingiRiskHigh
        WaitingMapCondition.WAITING_FIRST_FIX -> InkingiCyan
        WaitingMapCondition.UNAVAILABLE -> InkingiRiskMedium
    }

    val title = when {
        isRemoteDevice -> if (isEn) "No Remote GPS Fix Recorded Yet" else "Aucun Fix GPS Distant Enregistré"
        waitingCondition == WaitingMapCondition.PERMISSION_DENIED ->
            if (isEn) "GPS Permission Denied" else "Permission de Localisation Refusée"
        waitingCondition == WaitingMapCondition.GPS_DISABLED ->
            if (isEn) "Device GPS / Location Disabled" else "Localisation GPS Désactivée"
        waitingCondition == WaitingMapCondition.WAITING_FIRST_FIX ->
            if (isEn) "Waiting for First GPS Fix..." else "En Attente du Premier Fix GPS..."
        else ->
            if (isEn) "GPS Location Unavailable" else "Localisation GPS Non Disponible"
    }

    val body = when {
        isRemoteDevice ->
            if (isEn)
                "This remote device has not uploaded a verified GPS coordinate yet. No fallback pin is displayed."
            else
                "L'appareil distant n'a pas encore transmis de coordonnée GPS vérifiée. Aucune position simulée n'est affichée."
        waitingCondition == WaitingMapCondition.PERMISSION_DENIED ->
            if (isEn)
                "Location access is required to lock onto GPS satellites and track this device on the recovery map."
            else
                "L'autorisation de localisation est nécessaire pour acquérir le signal GPS et positionner cet appareil sur la carte."
        waitingCondition == WaitingMapCondition.GPS_DISABLED ->
            if (isEn)
                "Enable Location / GPS in Android Settings, then tap Retry to acquire verified coordinates."
            else
                "Activez la localisation / GPS dans les paramètres Android, puis appuyez sur Réessayer."
        waitingCondition == WaitingMapCondition.WAITING_FIRST_FIX ->
            if (isEn)
                "GPS listener is active and awaiting the first verified coordinate. No placeholder pin is fabricated."
            else
                "Le récepteur GPS est actif et attend la première coordonnée vérifiée. Aucune position fictive n'est affichée."
        else ->
            if (isEn)
                "No valid GPS coordinates have been acquired yet."
            else
                "Aucune coordonnée GPS valide n'a encore été acquise."
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(InkingiNavyCard)
            .border(1.dp, borderColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
    ) {
        // Subtle architectural grid background (strictly NO fake device coordinates or pins)
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridColor = borderColor.copy(alpha = 0.10f)
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
            val stepX = size.width / 6f
            val stepY = size.height / 4f
            for (i in 1..5) {
                drawLine(gridColor, Offset(stepX * i, 0f), Offset(stepX * i, size.height), strokeWidth = 1f, pathEffect = dash)
            }
            for (j in 1..3) {
                drawLine(gridColor, Offset(0f, stepY * j), Offset(size.width, stepY * j), strokeWidth = 1f, pathEffect = dash)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = when (waitingCondition) {
                    WaitingMapCondition.PERMISSION_DENIED -> Icons.Default.Lock
                    WaitingMapCondition.WAITING_FIRST_FIX -> Icons.Default.GpsNotFixed
                    else -> Icons.Default.GpsOff
                },
                contentDescription = null,
                tint = borderColor,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                color = borderColor,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = InkingiTextSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isRemoteDevice &&
                    waitingCondition == WaitingMapCondition.PERMISSION_DENIED &&
                    onRequestLocationPermission != null
                ) {
                    Button(
                        onClick = onRequestLocationPermission,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("request_location_permission_map_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                    ) {
                        Text(
                            text = if (isEn) "Grant GPS" else "Autoriser GPS",
                            color = InkingiNavyDark,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }

                if (onRetryGps != null) {
                    OutlinedButton(
                        onClick = onRetryGps,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("retry_gps_button"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = InkingiCyan)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isEn) "Retry GPS" else "Réessayer GPS",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MapTileErrorOverlay(
    errorType: MapTileErrorType,
    detail: String?,
    isEn: Boolean,
    onRetryMap: () -> Unit,
    onSwitchToRadar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val headline = when (errorType) {
        MapTileErrorType.OFFLINE_INTERNET_REQUIRED ->
            if (isEn) "Internet Required for Map Tiles" else "Internet Requis pour la Carte"
        MapTileErrorType.LEAFLET_SCRIPT_ERROR ->
            if (isEn) "Map Engine Failed to Initialize" else "Moteur Cartographique Indisponible"
        MapTileErrorType.TILE_LOAD_ERROR ->
            if (isEn) "OpenStreetMap Tiles Unavailable" else "Tuiles OpenStreetMap Indisponibles"
        MapTileErrorType.PAGE_LOAD_ERROR, MapTileErrorType.NONE ->
            if (isEn) "Map Loading Error" else "Erreur de Chargement Carte"
    }

    val message = when (errorType) {
        MapTileErrorType.OFFLINE_INTERNET_REQUIRED ->
            if (isEn)
                "OpenStreetMap and Leaflet map tiles require active internet access. Verified GPS coordinates remain shown below."
            else
                "Les tuiles OpenStreetMap / Leaflet nécessitent une connexion Internet active. Les coordonnées GPS vérifiées restent affichées ci-dessous."
        MapTileErrorType.LEAFLET_SCRIPT_ERROR ->
            if (isEn)
                "Unable to load the interactive map script (internet connection or CDN blocked)."
            else
                "Impossible de charger le moteur Leaflet (connexion Internet absente ou CDN bloqué)."
        MapTileErrorType.TILE_LOAD_ERROR ->
            if (isEn)
                "Unable to download OpenStreetMap map tiles from the network."
            else
                "Impossible de télécharger les tuiles OpenStreetMap depuis le réseau."
        else -> detail ?: if (isEn) "Map tiles could not be loaded." else "Impossible de charger les tuiles."
    }

    Box(
        modifier = modifier
            .background(InkingiNavyDark.copy(alpha = 0.92f))
            .padding(16.dp)
            .testTag("map_tile_error_overlay"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = null,
                tint = InkingiRiskMedium,
                modifier = Modifier.size(28.dp)
            )
            Text(
                text = headline,
                fontWeight = FontWeight.Bold,
                color = InkingiRiskMedium,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = InkingiTextSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onRetryMap,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("retry_map_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = InkingiNavyDark, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (isEn) "Retry" else "Réessayer",
                        color = InkingiNavyDark,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
                OutlinedButton(
                    onClick = onSwitchToRadar,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("fallback_radar_button"),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = InkingiCyan)
                ) {
                    Text(
                        text = if (isEn) "Radar View" else "Vue Radar",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

internal fun generateLeafletOsmHtml(
    lat: Double,
    lng: Double,
    accuracyMeters: Float,
    isLive: Boolean,
    isRemoteDevice: Boolean = false,
    historyJson: String,
    zones: List<TrustedZone>
): String {
    val zonesJson = zones.filter { it.isEnabled }.joinToString(separator = ",") {
        "{\"name\":\"${it.name.replace("\"", "\\\"")}\", \"lat\":${it.latitude}, \"lng\":${it.longitude}, \"radius\":${it.radiusMeters}}"
    }

    val markerColor = when {
        isRemoteDevice -> "#FF5252"
        isLive -> "#00E5FF"
        else -> "#FFB300"
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                body, html { margin: 0; padding: 0; width: 100%; height: 100%; background: #0B1120; }
                #map { width: 100%; height: 100%; background: #0B1120; }
                .leaflet-control-attribution { display: none !important; }
                .cyber-pin-live {
                    width: 16px;
                    height: 16px;
                    background: #00E5FF;
                    border: 2px solid #FFFFFF;
                    border-radius: 50%;
                    box-shadow: 0 0 10px #00E5FF, 0 0 20px #00E5FF;
                    animation: pulse 1.5s infinite;
                }
                .cyber-pin-remote {
                    width: 16px;
                    height: 16px;
                    background: #FF5252;
                    border: 2px solid #FFFFFF;
                    border-radius: 50%;
                    box-shadow: 0 0 10px #FF5252, 0 0 20px #FF5252;
                    animation: pulseRemote 1.4s infinite;
                }
                .cyber-pin-stale {
                    width: 16px;
                    height: 16px;
                    background: #FFB300;
                    border: 2px solid #FFFFFF;
                    border-radius: 50%;
                    box-shadow: 0 0 8px #FFB300;
                }
                @keyframes pulse {
                    0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(0, 229, 255, 0.7); }
                    70% { transform: scale(1.1); box-shadow: 0 0 0 10px rgba(0, 229, 255, 0); }
                    100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(0, 229, 255, 0); }
                }
                @keyframes pulseRemote {
                    0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(255, 82, 82, 0.75); }
                    70% { transform: scale(1.12); box-shadow: 0 0 0 12px rgba(255, 82, 82, 0); }
                    100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(255, 82, 82, 0); }
                }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                window.__inkingiMapReady = false;
                window.__pendingMapUpdate = null;
                window.__pendingRecenter = null;

                var map = null;
                var accuracyCircle = null;
                var marker = null;
                var historyLayer = null;
                var zonesLayer = null;

                function resolvePinClass(live, remote) {
                    if (remote) return 'cyber-pin-remote';
                    return live ? 'cyber-pin-live' : 'cyber-pin-stale';
                }

                function resolvePinColor(live, remote) {
                    if (remote) return '#FF5252';
                    return live ? '#00E5FF' : '#FFB300';
                }

                function buildPinIcon(live, remote) {
                    return L.divIcon({
                        className: resolvePinClass(live, remote),
                        iconSize: [16, 16],
                        iconAnchor: [8, 8]
                    });
                }

                function renderHistory(historyList) {
                    if (!historyLayer) return;
                    historyLayer.clearLayers();
                    if (!Array.isArray(historyList) || historyList.length === 0) return;

                    var seenKeys = {};
                    var deduped = [];
                    historyList.forEach(function(pt) {
                        var key = pt.lat.toFixed(6) + ',' + pt.lng.toFixed(6) + '@' + pt.time;
                        if (!seenKeys[key]) {
                            seenKeys[key] = true;
                            deduped.push(pt);
                        }
                    });

                    var latLngs = deduped.map(function(pt) { return [pt.lat, pt.lng]; });
                    if (latLngs.length > 1) {
                        L.polyline(latLngs, {
                            color: '#FFB300',
                            weight: 3,
                            opacity: 0.85,
                            dashArray: '6, 6'
                        }).addTo(historyLayer);
                    }

                    deduped.forEach(function(pt, idx) {
                        L.circleMarker([pt.lat, pt.lng], {
                            radius: 4,
                            color: '#FFFFFF',
                            fillColor: '#FFB300',
                            fillOpacity: 0.9,
                            weight: 1
                        }).bindPopup(
                            "<b>#" + (idx + 1) + " • " + pt.time + "</b><br/>±" + Math.round(pt.acc) + "m • Bat: " + pt.bat
                        ).addTo(historyLayer);
                    });
                }

                function updateMapState(newLat, newLng, newAcc, live, shouldFollow, historyList, remote) {
                    var isRemote = !!remote;
                    if (!window.__inkingiMapReady || !map || !marker || !accuracyCircle) {
                        window.__pendingMapUpdate = [newLat, newLng, newAcc, live, shouldFollow, historyList, isRemote];
                        return;
                    }
                    marker.setLatLng([newLat, newLng]);
                    marker.setIcon(buildPinIcon(live, isRemote));
                    accuracyCircle.setLatLng([newLat, newLng]);
                    accuracyCircle.setRadius(newAcc);
                    var clr = resolvePinColor(live, isRemote);
                    accuracyCircle.setStyle({ color: clr, fillColor: clr });
                    renderHistory(historyList);
                    if (shouldFollow) {
                        map.panTo([newLat, newLng]);
                    }
                }

                function recenterMap(centerLat, centerLng) {
                    if (!window.__inkingiMapReady || !map) {
                        window.__pendingRecenter = [centerLat, centerLng];
                        return;
                    }
                    map.setView([centerLat, centerLng], 16, { animate: true });
                }

                try {
                    if (typeof L === 'undefined') {
                        throw new Error('Leaflet library failed to load from external CDN.');
                    }

                    map = L.map('map', {
                        center: [$lat, $lng],
                        zoom: 16,
                        zoomControl: false,
                        attributionControl: false
                    });

                    var tileLayer = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
                        maxZoom: 19,
                        className: 'osm-tiles'
                    });

                    tileLayer.on('tileload', function() {
                        if (window.InkingiMapBridge && typeof window.InkingiMapBridge.onTileLoaded === 'function') {
                            window.InkingiMapBridge.onTileLoaded();
                        }
                    });
                    tileLayer.on('tileerror', function() {
                        if (window.InkingiMapBridge && typeof window.InkingiMapBridge.onTileError === 'function') {
                            window.InkingiMapBridge.onTileError();
                        }
                    });
                    tileLayer.addTo(map);

                    accuracyCircle = L.circle([$lat, $lng], {
                        radius: $accuracyMeters,
                        color: '$markerColor',
                        fillColor: '$markerColor',
                        fillOpacity: 0.18,
                        weight: 1.5
                    }).addTo(map);

                    marker = L.marker([$lat, $lng], {
                        icon: buildPinIcon(${if (isLive) "true" else "false"}, ${if (isRemoteDevice) "true" else "false"})
                    }).addTo(map);

                    historyLayer = L.layerGroup().addTo(map);
                    zonesLayer = L.layerGroup().addTo(map);

                    renderHistory($historyJson);

                    var geofences = [$zonesJson];
                    geofences.forEach(function(z) {
                        L.circle([z.lat, z.lng], {
                            radius: z.radius,
                            color: '#00E676',
                            fillColor: '#00E676',
                            fillOpacity: 0.12,
                            weight: 1.5,
                            dashArray: '4, 4'
                        }).bindPopup("<b>Zone:</b> " + z.name).addTo(zonesLayer);
                    });

                    window.__inkingiMapReady = true;
                    if (window.InkingiMapBridge && typeof window.InkingiMapBridge.onMapReady === 'function') {
                        window.InkingiMapBridge.onMapReady();
                    }

                    if (window.__pendingMapUpdate) {
                        var pending = window.__pendingMapUpdate;
                        window.__pendingMapUpdate = null;
                        updateMapState(pending[0], pending[1], pending[2], pending[3], pending[4], pending[5], pending[6]);
                    }
                    if (window.__pendingRecenter) {
                        var center = window.__pendingRecenter;
                        window.__pendingRecenter = null;
                        recenterMap(center[0], center[1]);
                    }
                } catch (err) {
                    window.__inkingiMapReady = false;
                    if (window.InkingiMapBridge && typeof window.InkingiMapBridge.onMapError === 'function') {
                        window.InkingiMapBridge.onMapError('LEAFLET_SCRIPT_ERROR', err && err.message ? err.message : String(err));
                    }
                }
            </script>
        </body>
        </html>
    """.trimIndent()
}

@Composable
private fun CyberRadarCanvas(
    latitude: Double,
    longitude: Double,
    accuracy: Float,
    statusColor: Color,
    historyPoints: List<LocationBreadcrumbPoint>,
    isEn: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize().testTag("cyber_radar_canvas")) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val maxR = minOf(cx, cy) * 0.85f
            val gridColor = Color(0xFF00E5FF).copy(alpha = 0.15f)
            val dashedEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)

            drawCircle(gridColor, radius = maxR * 0.33f, style = Stroke(width = 1f, pathEffect = dashedEffect))
            drawCircle(gridColor, radius = maxR * 0.66f, style = Stroke(width = 1f, pathEffect = dashedEffect))
            drawCircle(gridColor, radius = maxR, style = Stroke(width = 1.5f))

            drawLine(gridColor, Offset(cx - maxR, cy), Offset(cx + maxR, cy), strokeWidth = 1f)
            drawLine(gridColor, Offset(cx, cy - maxR), Offset(cx, cy + maxR), strokeWidth = 1f)

            if (historyPoints.size > 1) {
                val baseLat = latitude
                val baseLng = longitude
                val scale = 8000f

                var prevOffset: Offset? = null
                for (point in historyPoints) {
                    val dx = ((point.longitude - baseLng) * scale).toFloat()
                    val dy = -((point.latitude - baseLat) * scale).toFloat()
                    val pointOffset = Offset(
                        (cx + dx).coerceIn(10f, size.width - 10f),
                        (cy + dy).coerceIn(10f, size.height - 10f)
                    )

                    if (prevOffset != null) {
                        drawLine(
                            color = Color(0xFFFFB300).copy(alpha = 0.7f),
                            start = prevOffset,
                            end = pointOffset,
                            strokeWidth = 2f
                        )
                    }
                    drawCircle(
                        color = Color(0xFFFFB300),
                        radius = 3.5f,
                        center = pointOffset
                    )
                    prevOffset = pointOffset
                }
            }

            drawCircle(
                color = statusColor.copy(alpha = 0.25f),
                radius = 16f,
                center = Offset(cx, cy)
            )
            drawCircle(
                color = statusColor,
                radius = 6f,
                center = Offset(cx, cy)
            )
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp)
                .background(InkingiNavyDark.copy(alpha = 0.85f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = if (isEn) "Accuracy: ±${accuracy.toInt()}m" else "Précision: ±${accuracy.toInt()}m",
                fontSize = 10.sp,
                color = InkingiTextSecondary,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
