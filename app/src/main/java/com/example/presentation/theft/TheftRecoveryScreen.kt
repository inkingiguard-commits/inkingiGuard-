package com.example.presentation.theft

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.CrisisAlert
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.model.SecurityState
import com.example.domain.management.DeviceLockManager
import com.example.presentation.InkingiUiState
import com.example.presentation.PoliceReportSubmissionStatus
import com.example.ui.theme.InkingiCyan
import com.example.ui.theme.InkingiNavyBorder
import com.example.ui.theme.InkingiNavyCard
import com.example.ui.theme.InkingiNavyCardElevated
import com.example.ui.theme.InkingiNavyDark
import com.example.ui.theme.InkingiRiskHigh
import com.example.ui.theme.InkingiRiskLow
import com.example.ui.theme.InkingiRiskMedium
import com.example.ui.theme.InkingiTextMuted
import com.example.ui.theme.InkingiTextPrimary
import com.example.ui.theme.InkingiTextSecondary

@Composable
fun TheftRecoveryScreen(
    uiState: InkingiUiState,
    onConfirmStolen: (String) -> Boolean,
    onActivateRecoveryMode: (String) -> Boolean,
    onMarkRecovered: (String) -> Boolean,
    onDisarm: (String) -> Boolean,
    onVerifyMasterPin: (String) -> Boolean = { false },
    onAttemptDeviceLock: () -> DeviceLockManager.LockExecutionResult,
    onTriggerSync: () -> Unit,
    onGeneratePoliceReport: (() -> Unit)? = null,
    onTriggerFindMyPhone: (() -> Boolean)? = null,
    onStopFindMyPhone: ((String) -> Boolean)? = null,
    onConfigureLockScreenMessage: ((String, String) -> String)? = null,
    onSelectLocalHistoryDate: ((Long) -> Unit)? = null,
    onPermissionsUpdated: () -> Unit = {},
    onRetryGps: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var showPinDialogForAction by remember { mutableStateOf<String?>(null) }
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var showDossierDialog by remember { mutableStateOf(false) }
    var showAdminRationaleDialog by remember { mutableStateOf(false) }
    var lockResultDialogMessage by remember { mutableStateOf<String?>(null) }

    // Two-step permission state tracking (Android 10 to 16)
    var showForegroundPermissionRationale by remember { mutableStateOf(false) }
    var showBackgroundPermissionRationale by remember { mutableStateOf(false) }
    var permissionRefreshTrigger by remember { mutableStateOf(0) }

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permissionRefreshTrigger++
                onPermissionsUpdated()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val hasFineLocation = remember(permissionRefreshTrigger, uiState.hasFineLocationPermission) {
        uiState.hasFineLocationPermission || androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val hasCoarseLocation = remember(permissionRefreshTrigger, uiState.hasCoarseLocationPermission) {
        uiState.hasCoarseLocationPermission || androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val hasLocationPermission = hasFineLocation || hasCoarseLocation

    val hasBackgroundLocation = remember(permissionRefreshTrigger, uiState.hasBackgroundLocationPermission) {
        uiState.hasBackgroundLocationPermission || if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    val foregroundPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionRefreshTrigger++
        onPermissionsUpdated()
        val fineGranted = permissions[android.Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        if (fineGranted && !hasBackgroundLocation && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            showBackgroundPermissionRationale = true
        }
    }

    val backgroundPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { _ ->
        permissionRefreshTrigger++
        onPermissionsUpdated()
    }

    val isEn = uiState.currentLanguage.equals("en", ignoreCase = true)
    val state = uiState.securityState
    val isStolen = state == SecurityState.STOLEN_MODE || state == SecurityState.OWNER_CONFIRMED_STOLEN
    val isRecovery = state == SecurityState.RECOVERY_MODE
    val isSuspectedLost = state == SecurityState.SUSPECTED_LOST
    val isRecovered = state == SecurityState.RECOVERED

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(InkingiNavyDark)
            .padding(horizontal = 16.dp)
            .testTag("theft_recovery_list"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Column {
                Text(
                    text = if (isEn) "Incident Response & Recovery Console" else "Console de Réponse aux Incidents et Récupération",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = InkingiTextPrimary
                )
                Text(
                    text = if (isEn)
                        "Owner-authorized protocol, legitimate Android device locking & telemetry beacon"
                    else
                        "Protocole autorisé par le propriétaire, verrouillage Android légitime et balise active",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkingiTextSecondary
                )
            }
        }

        // Active State Banner
        item {
            val stateColor = when {
                isStolen -> InkingiRiskHigh
                isSuspectedLost -> InkingiRiskHigh
                isRecovery -> InkingiRiskMedium
                isRecovered -> InkingiRiskLow
                else -> InkingiCyan
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, stateColor.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = InkingiNavyCardElevated)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(stateColor.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (isStolen || isSuspectedLost) Icons.Default.CrisisAlert else Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = stateColor,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Column {
                                Text(
                                    text = if (isEn) "INCIDENT STATE" else "ÉTAT DE L'INCIDENT",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = InkingiTextMuted
                                )
                                Text(
                                    text = state.name.replace("_", " "),
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = stateColor
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = if (isEn) {
                            when {
                                isStolen -> "CRITICAL STOLEN MODE: Owner confirmed unauthorized possession. Telemetry lock active."
                                isSuspectedLost -> "SUSPECTED LOST: Sensor divergence and displacement detected. Recovery protocol queued."
                                isRecovery -> "RECOVERY MODE ACTIVE: Identity custody verification sequence in progress."
                                isRecovered -> "DEVICE RECOVERED: Custody re-established. Authenticate PIN to restore baseline."
                                else -> "Nominal standby. Owner verification required to initiate high-consequence containment actions."
                            }
                        } else {
                            when {
                                isStolen -> "MODE VOL CRITIQUE : Possession non autorisée confirmée par le propriétaire. Balise active."
                                isSuspectedLost -> "PERTE SUSPECTÉE : Divergence des capteurs et déplacement détectés. Protocole engagé."
                                isRecovery -> "MODE RÉCUPÉRATION ACTIF : Séquence de vérification de garde en cours."
                                isRecovered -> "APPAREIL RÉCUPÉRÉ : Garde rétablie. Authentifiez le code PIN pour restaurer le statut nominal."
                                else -> "En veille nominale. Vérification propriétaire requise pour engager des actions à haute conséquence."
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiTextSecondary
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // Dynamic State Actions
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isStolen || isRecovery) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        enteredPin = ""
                                        pinError = null
                                        showPinDialogForAction = "MARK_RECOVERED"
                                    },
                                    modifier = Modifier.weight(1f).testTag("mark_recovered_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = InkingiRiskLow)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = InkingiNavyDark)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(stringResource(R.string.theft_action_recovered), color = InkingiNavyDark, fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        enteredPin = ""
                                        pinError = null
                                        showPinDialogForAction = "DISARM"
                                    },
                                    modifier = Modifier.weight(1f).testTag("disarm_theft_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = InkingiNavyCard)
                                ) {
                                    Text(stringResource(R.string.disarm_system), color = InkingiTextPrimary)
                                }
                            }
                        } else if (isRecovered) {
                            Button(
                                onClick = {
                                    enteredPin = ""
                                    pinError = null
                                    showPinDialogForAction = "DISARM"
                                },
                                modifier = Modifier.fillMaxWidth().testTag("restore_normal_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                            ) {
                                Text(stringResource(R.string.disarm_system), color = InkingiNavyDark, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        enteredPin = ""
                                        pinError = null
                                        showPinDialogForAction = "CONFIRM_STOLEN"
                                    },
                                    modifier = Modifier.weight(1f).testTag("confirm_stolen_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = InkingiRiskHigh)
                                ) {
                                    Text(stringResource(R.string.theft_action_stolen), fontWeight = FontWeight.Bold)
                                }

                                Button(
                                    onClick = {
                                        enteredPin = ""
                                        pinError = null
                                        showPinDialogForAction = "ACTIVATE_RECOVERY"
                                    },
                                    modifier = Modifier.weight(1f).testTag("activate_recovery_button"),
                                    colors = ButtonDefaults.buttonColors(containerColor = InkingiNavyCard)
                                ) {
                                    Text(stringResource(R.string.theft_action_recovery))
                                }
                            }

                            if (onTriggerFindMyPhone != null) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = {
                                        if (uiState.isFindMyPhoneAlarmActive) {
                                            enteredPin = ""
                                            pinError = null
                                            showPinDialogForAction = "STOP_ALARM"
                                        } else {
                                            onTriggerFindMyPhone()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().testTag("find_my_phone_button"),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (uiState.isFindMyPhoneAlarmActive) InkingiRiskHigh else InkingiCyan
                                    )
                                ) {
                                    Text(
                                        text = if (uiState.isFindMyPhoneAlarmActive) {
                                            if (isEn) "Stop Alarm (PIN Required)" else "Arrêter l'Alarme (Code PIN Requis)"
                                        } else {
                                            if (isEn) "Sound Loud Alarm (Local Siren)" else "Faire Sonner l'Appareil (Alarme Locale)"
                                        },
                                        color = if (uiState.isFindMyPhoneAlarmActive) androidx.compose.ui.graphics.Color.White else InkingiNavyDark,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Real Recovery Map & Phone Traceability (Prominent above diagnostic status cards)
        item {
            val activeHistory = if (onSelectLocalHistoryDate != null) {
                uiState.dateFilteredLocalHistory
            } else {
                uiState.recentLocationHistory
            }
            com.example.presentation.recovery.RealRecoveryMapView(
                deviceId = uiState.deviceProfile.deviceId,
                deviceName = uiState.deviceProfile.deviceName,
                latitude = uiState.location?.latitude,
                longitude = uiState.location?.longitude,
                accuracy = uiState.location?.accuracy,
                timestamp = uiState.location?.timestamp ?: 0L,
                battery = "${uiState.deviceHealth.batteryPercentage}%",
                isCharging = uiState.deviceHealth.isCharging,
                connectivity = if (uiState.networkInfo.isConnected) uiState.networkInfo.networkType else (if (isEn) "DISCONNECTED" else "DÉCONNECTÉ"),
                securityStatus = uiState.securityState.name,
                isGpsAvailable = uiState.isGpsAvailable,
                historyPoints = activeHistory.map {
                    com.example.domain.recovery.LocationBreadcrumbPoint(
                        latitude = it.latitude,
                        longitude = it.longitude,
                        accuracy = it.accuracy,
                        timestamp = it.timestamp,
                        isInsideTrustedZone = it.isInsideTrustedZone,
                        altitude = it.altitude,
                        speed = it.speed,
                        bearing = it.bearing,
                        batteryLevel = it.batteryLevel,
                        isFreshFix = it.isFreshFix,
                        provider = it.provider
                    )
                },
                trustedZones = uiState.trustedZones,
                isFreshFix = uiState.location?.isFreshFix ?: false,
                locationSource = uiState.location?.locationSource ?: "SYSTEM",
                altitude = uiState.location?.altitude,
                speed = uiState.location?.speed,
                bearing = uiState.location?.bearing,
                syncStatus = uiState.locationSyncStatus,
                lastSyncedAt = uiState.lastSuccessfulLocationSyncAt,
                selectedDateMillis = uiState.selectedHistoryDateMillis,
                onSelectDate = onSelectLocalHistoryDate,
                isEn = isEn,
                isRemoteDevice = false,
                hasLocationPermission = hasLocationPermission,
                isTrackingActive = uiState.isLocationUpdatesActive,
                isNetworkConnected = uiState.networkInfo.isConnected,
                onRetryGps = onRetryGps,
                onRequestLocationPermission = { showForegroundPermissionRationale = true }
            )
        }

        // Hardware Device Lock & Management Capabilities Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, InkingiNavyBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = InkingiNavyCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.AdminPanelSettings, contentDescription = null, tint = InkingiCyan)
                        Text(
                            text = if (isEn) "Hardware Device Lock & Management" else "Verrouillage Matériel et Gestion",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = InkingiTextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val caps = uiState.deviceCapabilities
                    CapabilityRow(if (isEn) "Device Administrator Active" else "Administrateur de Périphérique Actif", caps.isAdminActive)
                    CapabilityRow(if (isEn) "Enterprise Device Owner" else "Propriétaire d'Appareil d'Entreprise", caps.isDeviceOwner)
                    CapabilityRow(if (isEn) "Profile Owner Privilege" else "Privilège Propriétaire de Profil", caps.isProfileOwner)
                    CapabilityRow(if (isEn) "Authorized Hardware Lock (lockNow)" else "Verrouillage Matériel Autorisé (lockNow)", caps.canLockDevice)

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = caps.provisioningRequirementNote,
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiTextSecondary,
                        lineHeight = 16.sp
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                enteredPin = ""
                                pinError = null
                                showPinDialogForAction = "TEST_LOCK_CONFIRM"
                            },
                            modifier = Modifier.weight(1f).testTag("trigger_hardware_lock_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = InkingiNavyCardElevated)
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = InkingiCyan, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.theft_action_lock_now), color = InkingiCyan, fontSize = 12.sp)
                        }

                        if (!caps.isAdminActive) {
                            OutlinedButton(
                                onClick = { showAdminRationaleDialog = true },
                                modifier = Modifier.weight(1f).testTag("open_admin_settings_button"),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = InkingiTextPrimary)
                            ) {
                                Text(if (isEn) "Enable Admin" else "Activer Admin", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }

        // Adaptive Monitoring & Offline Sync Queue
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, InkingiNavyBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = InkingiNavyCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isEn) "Adaptive Recovery Monitoring" else "Surveillance Adaptative de Récupération",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = InkingiTextPrimary
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Text(
                        text = if (isEn)
                            "Telemetry Frequency: ${uiState.monitoringStrategy.frequency.name} (${uiState.monitoringStrategy.locationIntervalMillis / 1000}s beacon)"
                        else
                            "Fréquence Télémétrie : ${uiState.monitoringStrategy.frequency.name} (balise ${uiState.monitoringStrategy.locationIntervalMillis / 1000}s)",
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = InkingiCyan
                    )
                    Text(
                        text = uiState.monitoringStrategy.policyDescription,
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiTextSecondary
                    )

                    val syncTimeLabel = uiState.lastSuccessfulLocationSyncAt?.let {
                        java.text.SimpleDateFormat("dd/MM HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(it))
                    } ?: (if (isEn) "Never" else "Jamais")

                    Text(
                        text = if (isEn)
                            "Cloud Sync: ${uiState.locationSyncStatus} • Last sync: $syncTimeLabel • Unsynced GPS points: ${uiState.unsyncedLocationCount}"
                        else
                            "Sync Cloud : ${uiState.locationSyncStatus} • Dernière sync : $syncTimeLabel • Points GPS en attente : ${uiState.unsyncedLocationCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkingiCyan
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    val qSummary = uiState.queueStatusSummary
                    val alertStatus = uiState.alertDispatchStatus

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isEn)
                                "Event Queue: ${uiState.pendingQueueCount} waiting / actionable"
                            else
                                "File d'événements : ${uiState.pendingQueueCount} en attente / à traiter",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkingiTextMuted
                        )
                        OutlinedButton(
                            onClick = onTriggerSync,
                            modifier = Modifier.testTag("trigger_sync_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = InkingiCyan)
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.theft_action_sync_queue), fontSize = 11.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(InkingiNavyDark, RoundedCornerShape(10.dp))
                            .border(1.dp, InkingiNavyBorder, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                            .testTag("queue_status_summary_card"),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = if (isEn) {
                                "Saved locally: ${qSummary.localQueuedCount} • Waiting for backend: ${qSummary.pendingCount} • Sent to backend: ${qSummary.sentToBackendCount}"
                            } else {
                                "Enregistrée localement : ${qSummary.localQueuedCount} • En attente serveur : ${qSummary.pendingCount} • Envoyée au serveur : ${qSummary.sentToBackendCount}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = InkingiTextSecondary
                        )
                        Text(
                            text = if (isEn) {
                                "Accepted by dispatch: ${qSummary.dispatchAcceptedCount + qSummary.deliveryConfirmedCount} • Failed: ${qSummary.failedCount} • Not configured: ${qSummary.notConfiguredCount}"
                            } else {
                                "Acceptée par relais : ${qSummary.dispatchAcceptedCount + qSummary.deliveryConfirmedCount} • Échec : ${qSummary.failedCount} • Non configuré : ${qSummary.notConfiguredCount}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (qSummary.failedCount > 0) InkingiRiskHigh else if (qSummary.notConfiguredCount > 0) InkingiRiskMedium else InkingiCyan
                        )
                        val dispatchLine = if (isEn) {
                            alertStatus.lastStatusMessageEn.ifBlank {
                                if (alertStatus.isExternalContactDispatchConfigured) {
                                    "Trusted contact external dispatch: Configured"
                                } else {
                                    "Trusted contact external dispatch: Not configured (no Push/SMS relay active)"
                                }
                            }
                        } else {
                            alertStatus.lastStatusMessageFr.ifBlank {
                                if (alertStatus.isExternalContactDispatchConfigured) {
                                    "Envoi externe aux contacts de confiance : Configuré"
                                } else {
                                    "Envoi externe aux contacts de confiance : Non configuré (aucun relais Push/SMS actif)"
                                }
                            }
                        }
                        Text(
                            text = dispatchLine,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (alertStatus.trustedContactStage == com.example.domain.alerts.AlertDispatchStage.DELIVERY_CONFIRMED ||
                                alertStatus.trustedContactStage == com.example.domain.alerts.AlertDispatchStage.DISPATCH_ACCEPTED
                            ) InkingiRiskLow else InkingiRiskMedium,
                            modifier = Modifier.testTag("alert_dispatch_status_banner")
                        )
                        qSummary.lastErrorMessage?.let { safeErr ->
                            Text(
                                text = if (isEn) "Last queue note: $safeErr" else "Dernier statut file : $safeErr",
                                style = MaterialTheme.typography.labelSmall,
                                color = InkingiTextMuted
                            )
                        }
                    }
                }
            }
        }

        // Android Runtime Permissions Status & Two-Step Flow Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, InkingiNavyBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = InkingiNavyCard)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isEn) "Anti-Theft Permissions & Traceability" else "Autorisations de Traçabilité Antivol",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = InkingiTextPrimary
                        )
                        Icon(
                            imageVector = if (hasFineLocation && hasBackgroundLocation) Icons.Default.Check else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (hasFineLocation && hasBackgroundLocation) InkingiRiskLow else InkingiRiskMedium,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Status rows
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (isEn) "Precise GPS Location:" else "Localisation GPS précise :", color = InkingiTextSecondary, fontSize = 12.sp)
                        Text(
                            text = if (hasFineLocation) (if (isEn) "GRANTED" else "ACCORDÉE") else (if (isEn) "DENIED" else "NON ACCORDÉE"),
                            color = if (hasFineLocation) InkingiRiskLow else InkingiRiskHigh,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (isEn) "24/7 Background Tracking:" else "Suivi continu arrière-plan :", color = InkingiTextSecondary, fontSize = 12.sp)
                        Text(
                            text = when {
                                hasBackgroundLocation -> if (isEn) "ALL THE TIME" else "TOUT LE TEMPS"
                                hasLocationPermission -> if (isEn) "FOREGROUND ONLY" else "PREMIER PLAN SEULEMENT"
                                else -> if (isEn) "DENIED" else "NON ACCORDÉE"
                            },
                            color = if (hasBackgroundLocation) InkingiRiskLow else InkingiRiskMedium,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.testTag("background_location_status_label")
                        )
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (isEn) "Foreground Service Beacon:" else "Service balise antivol :", color = InkingiTextSecondary, fontSize = 12.sp)
                        Text(
                            text = when {
                                uiState.isForegroundServiceActive -> if (isEn) "ACTIVE" else "ACTIF"
                                uiState.lastForegroundServiceError != null -> if (isEn) "BLOCKED" else "BLOQUÉ"
                                else -> if (isEn) "STANDBY" else "EN ATTENTE"
                            },
                            color = when {
                                uiState.isForegroundServiceActive -> InkingiCyan
                                uiState.lastForegroundServiceError != null -> InkingiRiskHigh
                                else -> InkingiTextMuted
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.testTag("foreground_service_status_label")
                        )
                    }

                    uiState.lastForegroundServiceError?.let { fgsError ->
                        Text(
                            text = if (isEn) "Beacon restriction: $fgsError" else "Blocage balise : $fgsError",
                            style = MaterialTheme.typography.labelSmall,
                            color = InkingiRiskHigh,
                            modifier = Modifier.testTag("fgs_service_error_label")
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Action buttons for 2-step flow
                    if (!hasFineLocation) {
                        Button(
                            onClick = { showForegroundPermissionRationale = true },
                            modifier = Modifier.fillMaxWidth().testTag("request_foreground_location_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                        ) {
                            Text(
                                text = if (isEn) "Grant Location Permission (Step 1/2)" else "Autoriser la Localisation (Étape 1/2)",
                                color = InkingiNavyDark,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    } else if (!hasBackgroundLocation) {
                        Button(
                            onClick = { showBackgroundPermissionRationale = true },
                            modifier = Modifier.fillMaxWidth().testTag("request_background_location_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = InkingiRiskMedium)
                        ) {
                            Text(
                                text = if (isEn) "Enable 24/7 Background Tracking (Step 2/2)" else "Activer le Suivi 24/7 en Arrière-Plan (Étape 2/2)",
                                color = androidx.compose.ui.graphics.Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = android.net.Uri.parse("package:${context.packageName}")
                                    }
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            },
                            modifier = Modifier.fillMaxWidth().testTag("open_app_permission_settings_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = InkingiCyan)
                        ) {
                            Text(if (isEn) "Manage Permissions in Settings" else "Gérer les Permissions dans Paramètres", fontSize = 11.sp)
                        }
                    }

                    // Battery Optimization Status & Official Shortcut (Task 4)
                    val powerManager = context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager
                    val isIgnoringBatteryOptimizations = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
                    } else true

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (isEn) "Battery Optimization:" else "Optimisation batterie :", color = InkingiTextSecondary, fontSize = 12.sp)
                        Text(
                            text = if (isIgnoringBatteryOptimizations) (if (isEn) "UNRESTRICTED" else "NON RESTREINT") else (if (isEn) "OPTIMIZED (RESTRICTED)" else "OPTIMISÉ (RESTREINT)"),
                            color = if (isIgnoringBatteryOptimizations) InkingiRiskLow else InkingiRiskMedium,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    if (!isIgnoringBatteryOptimizations && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                } catch (_: Exception) {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                                    } catch (_: Exception) {}
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("open_battery_optimization_settings_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = InkingiCyan)
                        ) {
                            Text(if (isEn) "Disable Battery Optimization (Background GPS)" else "Désactiver l'Optimisation Batterie (GPS Arrière-Plan)", fontSize = 11.sp)
                        }
                    }

                    // Disclaimer notice
                    Text(
                        text = if (isEn) "Notice: Android power management enforces best-effort tracking. Continuous 24/7 telemetry is battery-aware and cannot be absolute."
                        else "Note : Le système Android applique un suivi adapté à l'énergie. La télémétrie continue respecte la batterie et ne peut être garantie à 100% sans interruption.",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkingiTextMuted,
                        lineHeight = 14.sp
                    )
                }
            }
        }

        // Cryptographic Theft Dossier & Forensic Report
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, InkingiNavyBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = InkingiNavyCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.theft_report_title),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = InkingiTextPrimary
                        )
                        Icon(Icons.Default.Description, contentDescription = null, tint = InkingiCyan)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val report = uiState.latestTheftReport
                    if (report != null) {
                        Text(text = if (isEn) "Report ID: ${report.reportId}" else "ID Rapport : ${report.reportId}", style = MaterialTheme.typography.bodySmall, color = InkingiTextPrimary)
                        Text(text = if (isEn) "Signature: ${report.cryptographicSignature.take(16)}..." else "Signature : ${report.cryptographicSignature.take(16)}...", style = MaterialTheme.typography.labelSmall, color = InkingiCyan)
                        Text(text = if (isEn) "Last GPS: %.4f, %.4f".format(report.lastKnownLatitude, report.lastKnownLongitude) else "Dernier GPS : %.4f, %.4f".format(report.lastKnownLatitude, report.lastKnownLongitude), style = MaterialTheme.typography.labelSmall, color = InkingiTextSecondary)
                    } else {
                        Text(
                            text = if (isEn)
                                "No active theft incident filed. Confirming Stolen Mode automatically produces an immutable audit dossier."
                            else
                                "Aucun incident de vol actif enregistré. La confirmation du mode vol produit automatiquement un dossier d'audit immuable.",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkingiTextSecondary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = { showDossierDialog = true },
                        modifier = Modifier.fillMaxWidth().testTag("view_dossier_button"),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = InkingiCyan)
                    ) {
                        Text(stringResource(R.string.theft_action_view_dossier))
                    }

                    if (onGeneratePoliceReport != null) {
                        val reportState = uiState.policeReportState
                        val isSubmitting = reportState.status == PoliceReportSubmissionStatus.SUBMITTING
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { onGeneratePoliceReport() },
                            enabled = !isSubmitting,
                            modifier = Modifier.fillMaxWidth().testTag("generate_police_report_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                        ) {
                            Icon(Icons.Default.Shield, contentDescription = null, tint = InkingiNavyDark)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = when {
                                    isSubmitting && isEn -> "Submitting Report..."
                                    isSubmitting -> "Envoi du dossier..."
                                    isEn -> "Generate Police Report"
                                    else -> "Générer Dossier de Police"
                                },
                                color = InkingiNavyDark,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (reportState.status != PoliceReportSubmissionStatus.IDLE) {
                            val statusColor = when (reportState.status) {
                                PoliceReportSubmissionStatus.SUBMITTED -> InkingiRiskLow
                                PoliceReportSubmissionStatus.SUBMITTING -> InkingiCyan
                                PoliceReportSubmissionStatus.PENDING -> InkingiRiskMedium
                                PoliceReportSubmissionStatus.UNAVAILABLE -> InkingiRiskMedium
                                PoliceReportSubmissionStatus.FAILED -> InkingiRiskHigh
                                PoliceReportSubmissionStatus.IDLE -> InkingiTextMuted
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Card(
                                colors = CardDefaults.cardColors(containerColor = InkingiNavyDark),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                    .testTag("police_report_status_card")
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "${if (isEn) "Status" else "Statut"}: ${reportState.status.name}",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = statusColor,
                                        modifier = Modifier.testTag("police_report_status_label")
                                    )
                                    reportState.detailMessage?.let { msg ->
                                        Text(
                                            text = msg,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = InkingiTextPrimary
                                        )
                                    }
                                    reportState.evidenceSyncStatus?.let { syncLabel ->
                                        Text(
                                            text = "${if (isEn) "Cloud sync" else "Sync cloud"}: $syncLabel",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = InkingiTextMuted
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // TASK 7: Message Écran de Verrouillage (Lock-Screen Recovery Message)
        item {
            var lockMessageInput by remember(uiState.lockScreenRecoveryMessage) {
                mutableStateOf(
                    uiState.lockScreenRecoveryMessage.ifBlank {
                        if (isEn) {
                            "Device secured by InkingiGuard. Please contact the owner."
                        } else {
                            "Téléphone sécurisé par InkingiGuard. Veuillez contacter le propriétaire."
                        }
                    }
                )
            }
            var lockPhoneInput by remember(uiState.recoveryContactPhone) {
                mutableStateOf(
                    uiState.recoveryContactPhone.ifBlank {
                        uiState.trustedPersons.firstOrNull()?.phoneNumber ?: ""
                    }
                )
            }
            var lockMessageStatus by remember { mutableStateOf<String?>(null) }
            var lockMessageIsError by remember { mutableStateOf(false) }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, InkingiNavyBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = InkingiNavyCard)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = InkingiCyan)
                        Text(
                            text = if (isEn) "Lost Phone Lock-Screen Message" else "Message Écran de Verrouillage (Perte)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = InkingiTextPrimary
                        )
                    }

                    Text(
                        text = if (isEn)
                            "Displays owner instructions & emergency contact on the lock screen. Never exposes PINs or private credentials. Uses Device Policy lock screen info when available, with persistent public notification fallback."
                        else
                            "Affiche vos consignes et votre contact d'urgence sur l'écran verrouillé. N'expose aucun code PIN ni identifiant. Utilise l'écran Device Policy ou notification publique permanente.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiTextSecondary
                    )

                    OutlinedTextField(
                        value = lockMessageInput,
                        onValueChange = { lockMessageInput = it },
                        label = { Text(if (isEn) "Recovery Message" else "Message de Récupération") },
                        modifier = Modifier.fillMaxWidth().testTag("lock_screen_msg_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = InkingiCyan,
                            unfocusedBorderColor = InkingiNavyBorder
                        )
                    )

                    OutlinedTextField(
                        value = lockPhoneInput,
                        onValueChange = { lockPhoneInput = it },
                        label = { Text(if (isEn) "Emergency Contact Phone" else "Numéro de Contact d'Urgence") },
                        placeholder = {
                            Text(
                                text = if (isEn) "Enter recovery phone number" else "Saisir le numéro d'urgence",
                                color = InkingiTextMuted
                            )
                        },
                        modifier = Modifier.fillMaxWidth().testTag("lock_screen_phone_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = InkingiCyan,
                            unfocusedBorderColor = InkingiNavyBorder
                        )
                    )

                    Button(
                        onClick = {
                            if (onConfigureLockScreenMessage != null) {
                                val resultMsg = onConfigureLockScreenMessage(lockMessageInput, lockPhoneInput)
                                lockMessageIsError = resultMsg.startsWith("Error:") || resultMsg.startsWith("Erreur :")
                                lockMessageStatus = resultMsg
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("apply_lock_screen_msg_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = InkingiNavyCardElevated)
                    ) {
                        Text(
                            text = if (isEn) "Apply Lock-Screen Message" else "Appliquer le Message d'Urgence",
                            color = InkingiCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (lockMessageStatus != null) {
                        Text(
                            text = lockMessageStatus!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (lockMessageIsError) InkingiRiskHigh else InkingiRiskLow
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // PIN Verification Dialog
    if (showPinDialogForAction != null) {
        val action = showPinDialogForAction!!
        AlertDialog(
            onDismissRequest = {
                enteredPin = ""
                pinError = null
                showPinDialogForAction = null
            },
            containerColor = InkingiNavyCardElevated,
            title = {
                Text(
                    text = if (isEn) "Master Authorization Required" else "Autorisation Maître Requise",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = InkingiTextPrimary
                )
            },
            text = {
                Column {
                    Text(
                        text = if (!uiState.isVaultKeystoreHealthy) {
                            if (isEn)
                                "Hardware security vault unavailable. Owner PIN verification is blocked."
                            else
                                "Coffre matériel Keystore indisponible. Vérification PIN bloquée par sécurité."
                        } else if (isEn) {
                            "Enter enrolled Master PIN to authorize '${action.replace("_", " ")}':"
                        } else {
                            "Entrez le code PIN maître enregistré pour autoriser '${action.replace("_", " ")}' :"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!uiState.isVaultKeystoreHealthy) InkingiRiskHigh else InkingiTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = {
                            if (it.length <= 8 && it.all { ch -> ch.isDigit() }) {
                                enteredPin = it
                                pinError = null
                            }
                        },
                        label = { Text(if (isEn) "Master PIN" else "Code PIN Maître") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("pin_auth_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = InkingiCyan,
                            focusedLabelColor = InkingiCyan,
                            unfocusedBorderColor = InkingiNavyBorder
                        )
                    )
                    val displayError = pinError ?: uiState.pinActionError
                    if (displayError != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = displayError,
                            style = MaterialTheme.typography.labelSmall,
                            color = InkingiRiskHigh
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (action == "TEST_LOCK_CONFIRM") {
                            val verified = onVerifyMasterPin(enteredPin)
                            if (verified) {
                                val result = onAttemptDeviceLock()
                                lockResultDialogMessage = when (result) {
                                    is DeviceLockManager.LockExecutionResult.Success ->
                                        if (isEn) "SUCCESS: Device screen locked immediately via Android DevicePolicyManager.lockNow()."
                                        else "SUCCÈS : Écran verrouillé immédiatement via Android DevicePolicyManager.lockNow()."
                                    is DeviceLockManager.LockExecutionResult.Unavailable ->
                                        if (isEn) "UNAVAILABLE: ${result.reason}"
                                        else "INDISPONIBLE : ${result.reason}"
                                    is DeviceLockManager.LockExecutionResult.SecurityError ->
                                        if (isEn) "FAILED: ${result.message}"
                                        else "ÉCHEC : ${result.message}"
                                }
                                enteredPin = ""
                                pinError = null
                                showPinDialogForAction = null
                            } else {
                                pinError = uiState.pinActionError
                                    ?: if (isEn) "Incorrect PIN. Security failure logged." else "Code PIN incorrect. Échec consigné dans l'audit."
                            }
                        } else {
                            val ok = when (action) {
                                "CONFIRM_STOLEN" -> onConfirmStolen(enteredPin)
                                "ACTIVATE_RECOVERY" -> onActivateRecoveryMode(enteredPin)
                                "MARK_RECOVERED" -> onMarkRecovered(enteredPin)
                                "DISARM" -> onDisarm(enteredPin)
                                "STOP_ALARM" -> onStopFindMyPhone?.invoke(enteredPin) ?: false
                                else -> false
                            }
                            if (ok) {
                                enteredPin = ""
                                pinError = null
                                showPinDialogForAction = null
                            } else {
                                pinError = uiState.pinActionError
                                    ?: if (isEn) "Incorrect PIN. Security failure logged." else "Code PIN incorrect. Échec consigné dans l'audit."
                            }
                        }
                    },
                    modifier = Modifier.testTag("confirm_pin_execute_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                ) {
                    Text(stringResource(R.string.theft_dialog_confirm), color = InkingiNavyDark, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    enteredPin = ""
                    pinError = null
                    showPinDialogForAction = null
                }) {
                    Text(stringResource(R.string.theft_dialog_cancel), color = InkingiTextMuted)
                }
            }
        )
    }

    // Hardware Lock Result Dialog
    if (lockResultDialogMessage != null) {
        AlertDialog(
            onDismissRequest = { lockResultDialogMessage = null },
            containerColor = InkingiNavyCardElevated,
            title = {
                Text(
                    text = if (isEn) "Hardware Lock Status" else "État du Verrouillage Matériel",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = InkingiCyan
                )
            },
            text = {
                Text(
                    text = lockResultDialogMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = InkingiTextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = { lockResultDialogMessage = null },
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                ) {
                    Text(if (isEn) "Understood" else "Compris", color = InkingiNavyDark)
                }
            }
        )
    }

    // Forensic Dossier Dialog
    if (showDossierDialog) {
        AlertDialog(
            onDismissRequest = { showDossierDialog = false },
            containerColor = InkingiNavyCardElevated,
            title = {
                Text(
                    text = if (isEn) "Forensic Incident Dossier" else "Dossier d'Incident Judiciaire",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = InkingiCyan
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (isEn) "Device ID: ${uiState.deviceProfile.deviceId}" else "ID Appareil : ${uiState.deviceProfile.deviceId}", style = MaterialTheme.typography.bodySmall, color = InkingiTextPrimary)
                    val resolvedOwner = (uiState.authenticatedUserEmail ?: uiState.deviceProfile.ownerEmail)
                        .ifBlank { if (isEn) "Not configured" else "Non configuré" }
                    Text(if (isEn) "Owner Identity: $resolvedOwner" else "Identité Propriétaire : $resolvedOwner", style = MaterialTheme.typography.bodySmall, color = InkingiTextSecondary)
                    Text(if (isEn) "Security State: ${uiState.securityState.name}" else "État de Sécurité : ${uiState.securityState.name}", style = MaterialTheme.typography.bodySmall, color = InkingiTextSecondary)
                    Text(if (isEn) "Last Known GPS: ${uiState.location?.let { "%.4f, %.4f".format(it.latitude, it.longitude) } ?: "Awaiting Satellite Fix"}" else "Dernier GPS : ${uiState.location?.let { "%.4f, %.4f".format(it.latitude, it.longitude) } ?: "En attente du signal GPS"}", style = MaterialTheme.typography.bodySmall, color = InkingiTextSecondary)
                    Text(if (isEn) "Carrier: ${uiState.simInfo.carrierName} (SIM: ${uiState.simInfo.simState})" else "Opérateur : ${uiState.simInfo.carrierName} (SIM : ${uiState.simInfo.simState})", style = MaterialTheme.typography.bodySmall, color = InkingiTextSecondary)
                    Text(if (isEn) "Active Incident: ${uiState.activeIncident?.title ?: "None under active escalation"}" else "Incident Actif : ${uiState.activeIncident?.title ?: "Aucun incident sous escalade"}", style = MaterialTheme.typography.bodySmall, color = InkingiTextSecondary)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(if (isEn) "Cryptographic Ledger Integrity: VERIFIED" else "Intégrité du Registre Cryptographique : VÉRIFIÉE", style = MaterialTheme.typography.labelSmall, color = InkingiRiskLow)
                }
            },
            confirmButton = {
                Button(
                    onClick = { showDossierDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                ) {
                    Text(stringResource(R.string.diagnostics_close), color = InkingiNavyDark)
                }
            }
        )
    }

    // Step 1 Rationale Dialog: Foreground Location
    if (showForegroundPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showForegroundPermissionRationale = false },
            title = {
                Text(
                    text = if (isEn) "Location Permission (Step 1 of 2)" else "Autorisation de Localisation (Étape 1 sur 2)",
                    color = InkingiTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isEn)
                        "InkingiGuard AI requires precise GPS location to detect perimeter departures from trusted zones and provide live tracking on the recovery map."
                    else
                        "InkingiGuard AI a besoin de l'autorisation de localisation précise pour détecter les déplacements anormaux hors des zones de confiance et afficher la balise sur la carte en temps réel.",
                    color = InkingiTextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showForegroundPermissionRationale = false
                        val permissions = mutableListOf(
                            android.Manifest.permission.ACCESS_FINE_LOCATION,
                            android.Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                        foregroundPermissionLauncher.launch(permissions.toTypedArray())
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                ) {
                    Text(if (isEn) "Continue" else "Continuer", color = InkingiNavyDark, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showForegroundPermissionRationale = false }) {
                    Text(if (isEn) "Cancel" else "Annuler", color = InkingiTextMuted)
                }
            }
        )
    }

    // Least-Privilege Device Admin Rationale Dialog (never bypasses Android confirmation)
    if (showAdminRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showAdminRationaleDialog = false },
            containerColor = InkingiNavyCardElevated,
            title = {
                Text(
                    text = if (isEn) "Device Administrator Activation" else "Activation Administrateur d'Appareil",
                    color = InkingiTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = if (isEn)
                        "InkingiGuard requests ONLY the screen lock policy (force-lock) so you can lock your phone in confirmed theft mode. It never requests remote factory reset or data wipe. Declining keeps GPS tracking, zones, anti-scam, and reports fully working."
                    else
                        "InkingiGuard demande UNIQUEMENT le verrouillage d'écran (force-lock) en cas de vol confirmé. Aucune permission d'effacement ou de réinitialisation d'usine n'est demandée. En cas de refus, le suivi GPS, les zones, l'anti-arnaque et les rapports restent fonctionnels.",
                    color = InkingiTextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showAdminRationaleDialog = false
                        try {
                            val adminIntent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(
                                    android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                    android.content.ComponentName(context, com.example.domain.management.InkingiDeviceAdminReceiver::class.java)
                                )
                                putExtra(
                                    android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    if (isEn) "InkingiGuard AI requests screen lock (force-lock) only to lock the screen during confirmed theft. No data wipe permission is used."
                                    else "InkingiGuard AI demande uniquement le verrouillage d'écran (force-lock) en cas de vol confirmé. Aucun droit d'effacement n'est demandé."
                                )
                            }
                            context.startActivity(adminIntent)
                        } catch (_: Exception) {
                            try {
                                context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                            } catch (_: Exception) {}
                        }
                    },
                    modifier = Modifier.testTag("confirm_enable_admin_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                ) {
                    Text(if (isEn) "Continue to Android Settings" else "Continuer vers Android", color = InkingiNavyDark, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAdminRationaleDialog = false }) {
                    Text(if (isEn) "Not Now" else "Plus tard", color = InkingiTextMuted)
                }
            }
        )
    }

    // Step 2 Rationale Dialog: Background Location (Android 10 - 15)
    if (showBackgroundPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showBackgroundPermissionRationale = false },
            title = {
                Text(
                    text = if (isEn) "24/7 Background Protection (Step 2 of 2)" else "Protection 24/7 en Arrière-Plan (Étape 2 sur 2)",
                    color = InkingiTextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = if (isEn)
                            "For continuous anti-theft tracking when your screen is locked or the app is closed, InkingiGuard needs 'Allow all the time' location access."
                        else
                            "Pour maintenir la protection antivol et le suivi par balise même lorsque l'écran est éteint ou l'application fermée, InkingiGuard a besoin de l'option 'Toujours autoriser'.",
                        color = InkingiTextSecondary,
                        fontSize = 13.sp
                    )
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        Text(
                            text = if (isEn)
                                "On Android 11+, select 'Permissions' > 'Location' > 'Allow all the time' in the settings screen."
                            else
                                "Sur Android 11 et ultérieur, sélectionnez 'Autorisations' > 'Position' > 'Toujours autoriser' dans les paramètres.",
                            color = InkingiCyan,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showBackgroundPermissionRationale = false
                        if (android.os.Build.VERSION.SDK_INT == android.os.Build.VERSION_CODES.Q) {
                            backgroundPermissionLauncher.launch(android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        } else {
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = android.net.Uri.parse("package:${context.packageName}")
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {}
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                ) {
                    Text(if (isEn) "Open Settings / Allow" else "Ouvrir les Paramètres / Autoriser", color = InkingiNavyDark, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBackgroundPermissionRationale = false }) {
                    Text(if (isEn) "Cancel" else "Annuler", color = InkingiTextMuted)
                }
            }
        )
    }
}

@Composable
private fun CapabilityRow(label: String, isAvailable: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = InkingiTextMuted)
        Text(
            text = if (isAvailable) "YES" else "NO",
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = if (isAvailable) InkingiRiskLow else InkingiRiskHigh
        )
    }
}
