package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R
import com.example.presentation.InkingiViewModel
import com.example.presentation.auth.OnboardingAuthDialog
import com.example.presentation.components.InkingiBottomBar
import com.example.presentation.components.InkingiNavigationItem
import com.example.presentation.components.InkingiTopBar
import com.example.presentation.dashboard.DashboardScreen
import com.example.presentation.sensors.SensorsScreen
import com.example.presentation.settings.SettingsScreen
import com.example.presentation.theft.TheftRecoveryScreen
import com.example.presentation.timeline.TimelineScreen
import com.example.presentation.zones.ZonesScreen
import com.example.ui.theme.InkingiCyan
import com.example.ui.theme.InkingiNavyBorder
import com.example.ui.theme.InkingiNavyCardElevated
import com.example.ui.theme.InkingiNavyDark
import com.example.ui.theme.InkingiRiskHigh
import com.example.ui.theme.InkingiTextMuted
import com.example.ui.theme.InkingiTextPrimary
import com.example.ui.theme.InkingiTextSecondary
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as InkingiGuardApplication
        val container = app.container

        setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.activity.compose.LocalActivityResultRegistryOwner provides this
            ) {
                val viewModel: InkingiViewModel = viewModel(
                    factory = InkingiViewModel.Factory(container)
                )
                val uiState by viewModel.uiState.collectAsState()
                MyApplicationTheme(language = uiState.currentLanguage) {
                    if (!uiState.isAuthenticated) {
                        com.example.presentation.auth.StandaloneAuthScreen(
                            viewModel = viewModel,
                            uiState = uiState,
                            onAuthSuccess = {
                                // Session is authenticated; Compose recomposition displays InkingiGuardApp
                            }
                        )
                    } else {
                        InkingiGuardApp(viewModel = viewModel)
                    }
                }
            }
        }
    }
}

@Composable
fun InkingiGuardApp(viewModel: InkingiViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var currentScreen by remember { mutableStateOf(InkingiNavigationItem.DASHBOARD) }

    var showDisarmDialog by remember { mutableStateOf(false) }
    var disarmPin by remember { mutableStateOf("") }
    var disarmError by remember { mutableStateOf<String?>(null) }
    var showEnrollDialog by remember { mutableStateOf(!uiState.deviceProfile.isRegistered && !uiState.isAuthenticated) }
    var showAdminConsole by remember { mutableStateOf(false) }

    val context = androidx.compose.ui.platform.LocalContext.current

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Grant and denial are both handled safely; app continues operating normally
    }

    // Runtime permission launcher for legitimate location & telephony monitoring
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.onLocationPermissionsUpdated()
        // Permissions handled gracefully by Sensor & Location engines
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNotifications = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNotifications) {
                try {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(Unit) {
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasPhone = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED
        val hasNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (!hasFine || !hasPhone) {
            kotlinx.coroutines.delay(600L)
            try {
                permissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.READ_PHONE_STATE
                    )
                )
            } catch (_: Exception) {}
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifications) {
            kotlinx.coroutines.delay(600L)
            try {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } catch (_: Exception) {}
        }
    }

    // Back handler to navigate back to Dashboard
    if (currentScreen != InkingiNavigationItem.DASHBOARD) {
        BackHandler {
            currentScreen = InkingiNavigationItem.DASHBOARD
        }
    }

    // Refresh Device Admin status, battery, and location capabilities whenever the owner returns to the app
    androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
        viewModel.refreshCapabilities()
        onPauseOrDispose {}
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = InkingiNavyDark,
        topBar = {
            InkingiTopBar(
                securityState = uiState.securityState,
                deviceId = uiState.deviceProfile.deviceId,
                onOpenDiagnostics = { currentScreen = InkingiNavigationItem.TIMELINE }
            )
        },
        bottomBar = {
            InkingiBottomBar(
                currentScreen = currentScreen,
                securityState = uiState.securityState,
                onNavigate = { currentScreen = it }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (currentScreen) {
                InkingiNavigationItem.DASHBOARD -> DashboardScreen(
                    uiState = uiState,
                    onConfirmStolenWithStrongLock = { pin -> viewModel.confirmStolenWithStrongLock(pin) },
                    onTrackStolenPhone = { id -> viewModel.trackStolenPhone(id) },
                    onResetBaseline = { viewModel.resetBaselineLearning() },
                    onPauseBaseline = { viewModel.pauseBaselineLearning() },
                    onResumeBaseline = { viewModel.resumeBaselineLearning() },
                    onStartObservingRemoteDevice = { id -> viewModel.startObservingRemoteDevice(id) },
                    onStopObservingRemoteDevice = { viewModel.stopObservingRemoteDevice() },
                    onSelectRemoteHistoryDate = { day -> viewModel.selectRemoteHistoryDate(day) }
                )
                InkingiNavigationItem.SENSORS -> com.example.presentation.sensors.SensorsScreen(
                    uiState = uiState,
                    onSimulateKineticImpact = { viewModel.simulateKineticAnomaly() },
                    onResetKinetic = { viewModel.resetDiagnosticSimulations() }
                )
                InkingiNavigationItem.TIMELINE -> TimelineScreen(
                    uiState = uiState,
                    onClearLedger = { viewModel.clearLedger() }
                )
                InkingiNavigationItem.ZONES -> ZonesScreen(
                    uiState = uiState
                )
                InkingiNavigationItem.THEFT_RECOVERY -> com.example.presentation.theft.TheftRecoveryScreen(
                    uiState = uiState,
                    onConfirmStolen = { pin -> viewModel.confirmStolenByOwner(pin) },
                    onActivateRecoveryMode = { pin -> viewModel.activateRecoveryMode(pin) },
                    onMarkRecovered = { pin -> viewModel.markRecovered(pin) },
                    onDisarm = { pin -> viewModel.disarmWithPin(pin) },
                    onVerifyMasterPin = { pin -> viewModel.verifyMasterPin(pin) },
                    onAttemptDeviceLock = { viewModel.attemptDeviceLock() },
                    onTriggerSync = { viewModel.triggerOfflineSync() },
                    onGeneratePoliceReport = { viewModel.generatePoliceReportAction() },
                    onTriggerFindMyPhone = { viewModel.triggerFindMyPhoneAlarm() },
                    onStopFindMyPhone = { pin -> viewModel.stopFindMyPhoneAlarm(pin) },
                    onConfigureLockScreenMessage = { msg, phone -> viewModel.configureLockScreenMessage(msg, phone) },
                    onSelectLocalHistoryDate = { day -> viewModel.selectLocalHistoryDate(day) },
                    onPermissionsUpdated = { viewModel.onLocationPermissionsUpdated() },
                    onRetryGps = { viewModel.retryGpsAcquisition() }
                )
                InkingiNavigationItem.SETTINGS -> com.example.presentation.settings.SettingsScreen(
                    uiState = uiState,
                    onSetLanguage = { lang -> viewModel.setLanguage(lang) },
                    onSwitchUserRole = { role, pin -> viewModel.switchUserRole(role, pin) },
                    onAddTrustedPerson = { name, phone, rel, pin -> viewModel.addTrustedPerson(name, phone, rel, pin) },
                    onRemoveTrustedPerson = { id, pin -> viewModel.removeTrustedPerson(id, pin) },
                    onSimulateSimChange = { viewModel.simulateSimAlteration() },
                    onSimulateDisplacement = { viewModel.simulateDisplacementOutsideZone() },
                    onSimulateKineticImpact = { viewModel.simulateKineticAnomaly() },
                    onSimulateReboot = { viewModel.simulateRebootAnomaly() },
                    onSimulateNetworkDrop = { viewModel.simulateNetworkSevered() },
                    onResetSimulations = { viewModel.resetDiagnosticSimulations() },
                    onOpenAuthDialog = { viewModel.setAuthDialogVisible(true) },
                    onSignOut = { viewModel.signOutFromFirebase() },
                    onSendVerificationEmail = { viewModel.sendEmailVerification() },
                    onCheckVerificationStatus = { viewModel.checkEmailVerificationStatus() },
                    onNavigateToAdminConsole = { showAdminConsole = true },
                    onEnrollMasterPin = { pin -> viewModel.enrollMasterPin(pin) },
                    onChangeMasterPin = { currentPin, newPin -> viewModel.changeMasterPin(currentPin, newPin) }
                )
            }
        }
    }

    // Super Admin Console Screen (Gated to verified Super Admin)
    if (showAdminConsole && uiState.isSuperAdmin) {
        BackHandler { showAdminConsole = false }
        com.example.presentation.admin.SuperAdminConsoleScreen(
            uiState = uiState,
            onBack = { showAdminConsole = false },
            onRefreshAdminStatus = { viewModel.evaluateSuperAdminAuthorization(forceRefresh = true) }
        )
    }

    // Owner Disarm Dialog with Master PIN verification
    if (showDisarmDialog) {
        AlertDialog(
            onDismissRequest = {
                showDisarmDialog = false
                disarmPin = ""
                disarmError = null
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = InkingiCyan
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.dialog_disarm_title),
                        color = InkingiTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column {
                    Text(
                        text = stringResource(R.string.dialog_disarm_desc),
                        color = InkingiTextSecondary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = disarmPin,
                        onValueChange = {
                            if (it.length <= 6) disarmPin = it
                            disarmError = null
                        },
                        label = { Text(stringResource(R.string.master_pin_label)) },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = disarmError != null,
                        supportingText = disarmError?.let { { Text(it, color = InkingiRiskHigh) } },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = InkingiTextPrimary,
                            unfocusedTextColor = InkingiTextPrimary,
                            focusedBorderColor = InkingiCyan,
                            unfocusedBorderColor = InkingiNavyBorder
                        ),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("disarm_pin_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val success = viewModel.disarmWithPin(disarmPin)
                        if (success) {
                            showDisarmDialog = false
                            disarmPin = ""
                            disarmError = null
                        } else {
                            disarmError = uiState.pinActionError ?: if (uiState.currentLanguage.equals("en", ignoreCase = true))
                                "Invalid master PIN code."
                            else
                                "Code PIN maître invalide."
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan),
                    modifier = Modifier.testTag("confirm_disarm_btn")
                ) {
                    Text(
                        stringResource(R.string.dialog_disarm_btn),
                        color = InkingiNavyDark,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDisarmDialog = false
                        disarmPin = ""
                        disarmError = null
                    }
                ) {
                    Text(stringResource(R.string.dialog_cancel), color = InkingiTextMuted)
                }
            },
            containerColor = InkingiNavyCardElevated
        )
    }

    if (uiState.showAuthDialog) {
        OnboardingAuthDialog(
            uiState = uiState,
            initialDeviceName = uiState.deviceProfile.deviceName,
            initialOwnerEmail = uiState.deviceProfile.ownerEmail,
            onSignIn = { email, password ->
                viewModel.signInWithFirebase(email, password)
            },
            onRegister = { email, password, name, pin ->
                viewModel.registerWithFirebase(email, password, name, pin)
            },
            onSendVerificationEmail = {
                viewModel.sendEmailVerification()
            },
            onCheckVerificationStatus = {
                viewModel.checkEmailVerificationStatus()
            },
            onDismiss = {
                viewModel.setAuthDialogVisible(false)
            }
        )
    }
}
