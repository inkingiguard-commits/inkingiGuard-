package com.example.presentation.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CrisisAlert
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.RiskLevel
import com.example.core.model.SecurityState
import com.example.presentation.InkingiUiState
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
import kotlinx.coroutines.launch

/**
 * TAB 1: BOUCLIER - ANTI-VOL
 * 2 Simple Pillars / Cards:
 * Card 1: Apprentissage - Behaviour Learning (X/7 progress, baseline patterns, dans/hors baseline)
 * Card 2: Anti-Vol Action (Risk 0-100, 2 BIG RED BUTTONS: Vol Confirmé + Suivre Mon Téléphone Volé)
 */
@Composable
fun DashboardScreen(
    uiState: InkingiUiState,
    onConfirmStolenWithStrongLock: (String) -> Boolean,
    onTrackStolenPhone: suspend (String) -> Map<String, Any?>?,
    onResetBaseline: (() -> Unit)? = null,
    onPauseBaseline: (() -> Unit)? = null,
    onResumeBaseline: (() -> Unit)? = null,
    onStartObservingRemoteDevice: ((String) -> Unit)? = null,
    onStopObservingRemoteDevice: (() -> Unit)? = null,
    onSelectRemoteHistoryDate: ((Long) -> Unit)? = null
) {
    val isEn = uiState.currentLanguage.equals("en", ignoreCase = true)
    var showSetPinAndLockDialog by remember { mutableStateOf(false) }
    var pinInputValue by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }

    var showTrackPhoneDialog by remember { mutableStateOf(false) }
    var targetDeviceIdInput by remember { mutableStateOf(uiState.deviceProfile.deviceId) }
    var trackingResult by remember { mutableStateOf<Map<String, Any?>?>(null) }
    var isSearchingTarget by remember { mutableStateOf(false) }
    var searchNotFoundMessage by remember { mutableStateOf<String?>(null) }

    val coroutineScope = rememberCoroutineScope()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(InkingiNavyDark)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = if (isEn) "SHIELD — ANTI-THEFT" else "BOUCLIER — ANTI-VOL",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp),
                color = InkingiCyan
            )
            Text(
                text = if (isEn)
                    "Behavioral learning & Hardware emergency lock"
                else
                    "Apprentissage comportemental & Verrouillage d'urgence matériel",
                style = MaterialTheme.typography.bodySmall,
                color = InkingiTextSecondary
            )
        }

        // ==========================================================
        // CARD 1: Apprentissage - Behaviour Learning
        // ==========================================================
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, InkingiCyan.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = InkingiNavyCardElevated)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Psychology, contentDescription = null, tint = InkingiCyan, modifier = Modifier.size(24.dp))
                            Text(
                                text = if (isEn) "Behavioral Learning" else "Apprentissage Comportemental",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = InkingiTextPrimary
                            )
                        }

                        Text(
                            text = if (isEn) "Days: ${uiState.learnedDays}/7" else "Jours: ${uiState.learnedDays}/7",
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = InkingiCyan
                        )
                    }

                    // Progress bar 0 to 7 days
                    LinearProgressIndicator(
                        progress = { (uiState.learnedDays / 7f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = InkingiCyan,
                        trackColor = InkingiNavyDark
                    )

                    // Baseline summary card
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(InkingiNavyDark)
                            .padding(10.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isEn) "Learned baseline:" else "Ligne de base apprise :",
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                    color = InkingiTextMuted
                                )
                                Text(
                                    text = if (uiState.behaviorBaseline.isBaselineEstablished) {
                                        if (isEn) "Established" else "Établie"
                                    } else {
                                        if (isEn) "Learning phase" else "En apprentissage"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (uiState.behaviorBaseline.isBaselineEstablished) InkingiRiskLow else InkingiCyan
                                )
                            }
                            Text(
                                text = "${uiState.behaviorBaseline.frequentZonesSummary} | ${uiState.behaviorBaseline.frequentHoursSummary}",
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                color = InkingiTextPrimary
                            )
                        }
                    }

                    // Current posture indicator: Dans baseline vs Hors baseline
                    val isWithin = uiState.isWithinBaseline
                    val badgeColor = if (isWithin) InkingiRiskLow else InkingiRiskHigh
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(badgeColor.copy(alpha = 0.12f))
                            .border(1.dp, badgeColor.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isWithin) "✅" else "⚠️",
                            fontSize = 16.sp
                        )
                        Text(
                            text = uiState.baselineStatusMessage,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = badgeColor
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isPaused = uiState.isLearningPaused
                        TextButton(
                            onClick = {
                                if (isPaused) {
                                    onResumeBaseline?.invoke()
                                } else {
                                    onPauseBaseline?.invoke()
                                }
                            },
                            modifier = Modifier.testTag("toggle_learning_pause_btn")
                        ) {
                            Text(
                                text = if (isPaused) {
                                    if (isEn) "▶️ Resume Learning" else "▶️ Reprendre l'Apprentissage"
                                } else {
                                    if (isEn) "⏸️ Pause Learning" else "⏸️ Mettre en Pause"
                                },
                                color = InkingiCyan,
                                fontSize = 11.sp
                            )
                        }

                        if (onResetBaseline != null) {
                            TextButton(
                                onClick = onResetBaseline,
                                modifier = Modifier.testTag("reset_baseline_btn")
                            ) {
                                Text(
                                    text = if (isEn) "Reset baseline" else "Réinitialiser",
                                    color = InkingiRiskMedium,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // ==========================================================
        // CARD 2: Anti-Vol Action
        // ==========================================================
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, InkingiNavyBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = InkingiNavyCard)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        text = if (isEn) "Anti-Theft Protocol & Instant Lock" else "Protocole Anti-Vol & Verrouillage Immédiat",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = InkingiTextPrimary
                    )

                    // Gauge 0/100 simplified (Faible / Moyen / Élevé)
                    val score = uiState.riskAssessment.score
                    val riskLabel = if (isEn) {
                        when {
                            score >= 70 -> "High Risk"
                            score >= 35 -> "Medium Risk"
                            else -> "Low Risk"
                        }
                    } else {
                        when {
                            score >= 70 -> "Risque Élevé"
                            score >= 35 -> "Risque Moyen"
                            else -> "Risque Faible"
                        }
                    }
                    val riskColor = when {
                        score >= 70 -> InkingiRiskHigh
                        score >= 35 -> InkingiRiskMedium
                        else -> InkingiRiskLow
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = if (isEn) "EXPOSURE LEVEL" else "NIVEAU D'EXPOSITION",
                                style = MaterialTheme.typography.labelSmall,
                                color = InkingiTextMuted
                            )
                            Text(
                                text = riskLabel,
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                                color = riskColor
                            )
                        }

                        // Circular Gauge
                        Box(
                            modifier = Modifier.size(76.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(68.dp)) {
                                val strokeWidth = 7.dp.toPx()
                                drawArc(
                                    color = Color(0xFF1E2D4A),
                                    startAngle = 135f,
                                    sweepAngle = 270f,
                                    useCenter = false,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                                drawArc(
                                    color = riskColor,
                                    startAngle = 135f,
                                    sweepAngle = (score / 100f) * 270f,
                                    useCenter = false,
                                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                )
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "$score",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Black),
                                    color = InkingiTextPrimary
                                )
                                Text(text = "/100", style = MaterialTheme.typography.labelSmall, color = InkingiTextMuted, fontSize = 8.sp)
                            }
                        }
                    }

                    // Threat State & Multi-Signal Explainability Box
                    val threatLabel = if (isEn) {
                        when (uiState.threatState) {
                            com.example.core.model.CorrelationThreatState.THEFT_CONFIRMATION_REQUIRED -> "⚠️ Theft Confirmation Required (Multi-Signal)"
                            com.example.core.model.CorrelationThreatState.HIGH_RISK -> "🚨 High Risk (Owner Verification Required)"
                            com.example.core.model.CorrelationThreatState.SUSPICIOUS -> "⚡ Suspicious Activity Detected"
                            com.example.core.model.CorrelationThreatState.LOW_RISK -> "ℹ️ Minor Variance Detected"
                            com.example.core.model.CorrelationThreatState.NORMAL -> "🛡️ Protected Nominal Baseline"
                        }
                    } else {
                        when (uiState.threatState) {
                            com.example.core.model.CorrelationThreatState.THEFT_CONFIRMATION_REQUIRED -> "⚠️ Confirmation Vol Requise (Signaux Multiples)"
                            com.example.core.model.CorrelationThreatState.HIGH_RISK -> "🚨 Risque Élevé (Vérification Propriétaire Requise)"
                            com.example.core.model.CorrelationThreatState.SUSPICIOUS -> "⚡ Activité Suspecte Détectée"
                            com.example.core.model.CorrelationThreatState.LOW_RISK -> "ℹ️ Écart Mineur Constaté"
                            com.example.core.model.CorrelationThreatState.NORMAL -> "🛡️ Posture Nominale Protégée"
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(InkingiNavyDark.copy(alpha = 0.6f))
                            .border(1.dp, riskColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = threatLabel,
                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                color = riskColor
                            )
                            Text(
                                text = if (isEn) {
                                    if (uiState.isGpsAvailable) "GPS Active 📍" else "GPS Unavailable 📡"
                                } else {
                                    if (uiState.isGpsAvailable) "GPS Actif 📍" else "GPS Indisponible 📡"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (uiState.isGpsAvailable) InkingiCyan else InkingiRiskMedium
                            )
                        }

                        if (uiState.correlatedSignals.isNotEmpty()) {
                            Text(
                                text = (if (isEn) "Key risk factors: " else "Facteurs déterminants : ") + uiState.correlatedSignals.joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall,
                                color = InkingiTextSecondary,
                                lineHeight = 14.sp
                            )
                        } else {
                            Text(
                                text = if (isEn)
                                    "All sensory and geographic vectors are within normal profile."
                                else
                                    "Tous les vecteurs sensoriels et géographiques sont dans le profil normal.",
                                style = MaterialTheme.typography.labelSmall,
                                color = InkingiTextMuted
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 2 BIG RED BUTTONS
                    // Button A: VOL CONFIRMÉ - VERROUILLER
                    Button(
                        onClick = {
                            pinInputValue = ""
                            pinError = null
                            showSetPinAndLockDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("vol_confirme_verrouiller_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = InkingiRiskHigh),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isEn) "CONFIRM STOLEN — LOCK DEVICE" else "VOL CONFIRMÉ — VERROUILLER",
                            color = Color.White,
                            fontWeight = FontWeight.Black,
                            fontSize = 14.sp
                        )
                    }

                    // Button B: SUIVRE MON TÉLÉPHONE VOLÉ
                    Button(
                        onClick = {
                            trackingResult = null
                            searchNotFoundMessage = null
                            showTrackPhoneDialog = true
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("suivre_telephone_vole_button"),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B0000)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.GpsFixed, contentDescription = null, tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isEn) "TRACK MY STOLEN PHONE" else "SUIVRE MON TÉLÉPHONE VOLÉ",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Modal: Verrouillage matériel + PIN
    if (showSetPinAndLockDialog) {
        AlertDialog(
            onDismissRequest = { showSetPinAndLockDialog = false },
            containerColor = InkingiNavyCardElevated,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.CrisisAlert, contentDescription = null, tint = InkingiRiskHigh)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isEn) "Strong Anti-Theft Lock" else "Verrouillage Fort Anti-Vol",
                        color = InkingiRiskHigh,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (!uiState.isPinConfigured) {
                            if (isEn)
                                "No Master PIN is enrolled on this device yet. Enroll a Master PIN in Settings before executing owner theft lock."
                            else
                                "Aucun code PIN maître n'est encore enregistré sur cet appareil. Définissez un code PIN dans Paramètres avant de verrouiller."
                        } else if (isEn) {
                            "Enter your enrolled Master PIN. This immediately locks the screen via DevicePolicyManager and starts the Stolen Tracker beacon (GPS every 2 min)."
                        } else {
                            "Entrez votre code PIN maître enregistré. Ce code verrouille immédiatement l'écran via DevicePolicyManager et démarre la balise Suivi Vol (GPS toutes les 2 min)."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (!uiState.isPinConfigured) InkingiRiskMedium else InkingiTextSecondary
                    )
                    OutlinedTextField(
                        value = pinInputValue,
                        onValueChange = {
                            if (it.length <= 8 && it.all { ch -> ch.isDigit() }) pinInputValue = it
                            pinError = null
                        },
                        label = { Text(if (isEn) "Master PIN (4-8 digits)" else "Code PIN Maître (4-8 chiffres)") },
                        visualTransformation = PasswordVisualTransformation(),
                        isError = pinError != null,
                        supportingText = pinError?.let { { Text(it, color = InkingiRiskHigh) } },
                        modifier = Modifier.fillMaxWidth().testTag("master_lock_pin_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = InkingiTextPrimary,
                            unfocusedTextColor = InkingiTextPrimary,
                            focusedBorderColor = InkingiRiskHigh,
                            unfocusedBorderColor = InkingiNavyBorder
                        ),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (!uiState.isPinConfigured) {
                            pinError = if (isEn) "Enroll a Master PIN in Settings first." else "Définissez d'abord un code PIN maître dans Paramètres."
                        } else if (pinInputValue.length < 4) {
                            pinError = if (isEn) "Please enter at least 4 digits." else "Veuillez entrer au moins 4 chiffres."
                        } else {
                            val ok = onConfirmStolenWithStrongLock(pinInputValue)
                            if (ok) {
                                pinInputValue = ""
                                pinError = null
                                showSetPinAndLockDialog = false
                            } else {
                                pinError = uiState.pinActionError
                                    ?: if (isEn) "Incorrect PIN or vault unavailable." else "Code PIN incorrect ou coffre indisponible."
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiRiskHigh),
                    modifier = Modifier.testTag("execute_strong_lock_btn")
                ) {
                    Text(
                        text = if (isEn) "LOCK NOW" else "VERROUILLER MAINTENANT",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    pinInputValue = ""
                    pinError = null
                    showSetPinAndLockDialog = false
                }) {
                    Text(if (isEn) "Cancel" else "Annuler", color = InkingiTextMuted)
                }
            }
        )
    }

    // Modal: Suivre un téléphone volé via Firestore en temps réel
    if (showTrackPhoneDialog) {
        val activeRemoteTelemetry = uiState.remoteTrackedTelemetry?.takeIf {
            it.deviceId.equals(targetDeviceIdInput.trim(), ignoreCase = true)
        }
        AlertDialog(
            onDismissRequest = {
                onStopObservingRemoteDevice?.invoke()
                showTrackPhoneDialog = false
            },
            containerColor = InkingiNavyCardElevated,
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GpsFixed, contentDescription = null, tint = InkingiCyan)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isEn) "Live Remote Device Map" else "Suivi GPS Distant en Direct",
                        color = InkingiTextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = if (isEn)
                            "Select an enrolled device on your account or enter its Device ID to observe live GPS updates and movement history."
                        else
                            "Sélectionnez un appareil associé à votre compte ou saisissez son ID pour suivre sa position GPS et ses déplacements en temps réel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiTextSecondary
                    )

                    if (uiState.ownerRemoteDevices.isNotEmpty()) {
                        Text(
                            text = if (isEn) "Your Account Devices:" else "Vos Appareils Connectés :",
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = InkingiCyan
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            uiState.ownerRemoteDevices.forEach { dev ->
                                val isSelected = dev.deviceId.equals(targetDeviceIdInput.trim(), ignoreCase = true)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (isSelected) InkingiCyan.copy(alpha = 0.14f) else InkingiNavyDark)
                                        .border(
                                            1.dp,
                                            if (isSelected) InkingiCyan else InkingiNavyBorder,
                                            RoundedCornerShape(8.dp)
                                        )
                                        .clickable {
                                            targetDeviceIdInput = dev.deviceId
                                            searchNotFoundMessage = null
                                            onStartObservingRemoteDevice?.invoke(dev.deviceId)
                                            coroutineScope.launch {
                                                trackingResult = onTrackStolenPhone(dev.deviceId)
                                            }
                                        }
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                        .testTag("owner_device_row_${dev.deviceId}"),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.Devices, contentDescription = null, tint = InkingiCyan, modifier = Modifier.size(16.dp))
                                        Column {
                                            Text(
                                                text = dev.deviceName.ifBlank { dev.deviceId },
                                                color = InkingiTextPrimary,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = "${dev.deviceId} • ${dev.securityState}",
                                                color = InkingiTextMuted,
                                                fontSize = 10.sp
                                            )
                                        }
                                    }
                                    Text(
                                        text = if (dev.isNetworkConnected) (if (isEn) "ONLINE" else "EN LIGNE") else (if (isEn) "OFFLINE" else "HORS LIGNE"),
                                        color = if (dev.isNetworkConnected) InkingiRiskLow else InkingiRiskMedium,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = targetDeviceIdInput,
                        onValueChange = { targetDeviceIdInput = it },
                        label = { Text(if (isEn) "Phone ID (e.g. IG-D98E1455)" else "ID du téléphone (ex: IG-D98E1455)") },
                        modifier = Modifier.fillMaxWidth().testTag("target_device_id_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = InkingiTextPrimary,
                            unfocusedTextColor = InkingiTextPrimary,
                            focusedBorderColor = InkingiCyan,
                            unfocusedBorderColor = InkingiNavyBorder
                        )
                    )

                    Button(
                        onClick = {
                            val cleanId = targetDeviceIdInput.trim()
                            onStartObservingRemoteDevice?.invoke(cleanId)
                            coroutineScope.launch {
                                isSearchingTarget = true
                                searchNotFoundMessage = null
                                val data = onTrackStolenPhone(cleanId)
                                isSearchingTarget = false
                                if (data != null) {
                                    trackingResult = data
                                } else if (activeRemoteTelemetry == null) {
                                    searchNotFoundMessage = if (isEn)
                                        "No active beacon found for ID '$cleanId'."
                                    else
                                        "Aucune balise active pour l'identifiant '$cleanId'."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("localiser_stolen_phone_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = InkingiNavyDark)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isSearchingTarget || uiState.isObservingRemoteDevice) {
                                if (isEn) "Connecting live stream..." else "Connexion temps réel..."
                            } else {
                                if (isEn) "LOCATE & STREAM LIVE" else "LOCALISER EN DIRECT"
                            },
                            color = InkingiNavyDark,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    val errorToShow = uiState.remoteTrackingError ?: searchNotFoundMessage
                    if (errorToShow != null &&
                        activeRemoteTelemetry == null &&
                        trackingResult == null &&
                        !isSearchingTarget &&
                        !uiState.isObservingRemoteDevice
                    ) {
                        Text(
                            text = errorToShow,
                            color = InkingiRiskHigh,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.testTag("remote_tracking_error_text")
                        )
                    }

                    if (activeRemoteTelemetry != null) {
                        val mergedHistory = uiState.remoteDeviceBreadcrumbs.ifEmpty { activeRemoteTelemetry.locationHistory }
                        com.example.presentation.recovery.RealRecoveryMapView(
                            deviceId = activeRemoteTelemetry.deviceId,
                            deviceName = activeRemoteTelemetry.deviceName.ifBlank { activeRemoteTelemetry.deviceId },
                            latitude = activeRemoteTelemetry.lastKnownLat,
                            longitude = activeRemoteTelemetry.lastKnownLng,
                            accuracy = activeRemoteTelemetry.locationAccuracy,
                            timestamp = activeRemoteTelemetry.timestamp,
                            battery = "${activeRemoteTelemetry.batteryLevel}%",
                            isCharging = activeRemoteTelemetry.isCharging,
                            connectivity = activeRemoteTelemetry.connectivityStatus,
                            securityStatus = activeRemoteTelemetry.securityState,
                            isGpsAvailable = activeRemoteTelemetry.isGpsAvailable,
                            historyPoints = mergedHistory,
                            isFreshFix = activeRemoteTelemetry.isFreshFix,
                            locationSource = activeRemoteTelemetry.locationSource,
                            altitude = activeRemoteTelemetry.altitude,
                            speed = activeRemoteTelemetry.speed,
                            bearing = activeRemoteTelemetry.bearing,
                            syncStatus = activeRemoteTelemetry.syncStatus,
                            lastSyncedAt = activeRemoteTelemetry.lastSyncedAt,
                            selectedDateMillis = uiState.remoteSelectedDateMillis,
                            onSelectDate = onSelectRemoteHistoryDate,
                            isEn = isEn,
                            isRemoteDevice = true,
                            hasLocationPermission = true,
                            isTrackingActive = activeRemoteTelemetry.isGpsAvailable,
                            isNetworkConnected = uiState.networkInfo.isConnected,
                            onRetryGps = {
                                val cleanId = targetDeviceIdInput.trim()
                                if (cleanId.isNotBlank()) {
                                    onStartObservingRemoteDevice?.invoke(cleanId)
                                }
                            }
                        )
                    } else if (trackingResult != null) {
                        val lat = (trackingResult!!["lat"] as? Number)?.toDouble()
                        val lng = (trackingResult!!["lng"] as? Number)?.toDouble()
                        val battery = (trackingResult!!["battery"] as? String) ?: "—"
                        val time = (trackingResult!!["time"] as? Number)?.toLong() ?: 0L
                        val status = (trackingResult!!["status"] as? String) ?: "NORMAL"
                        val accuracy = (trackingResult!!["accuracy"] as? Number)?.toFloat()
                        val isCharging = (trackingResult!!["isCharging"] as? Boolean) ?: false
                        val connectivity = (trackingResult!!["connectivity"] as? String) ?: "OFFLINE"
                        val deviceName = (trackingResult!!["deviceName"] as? String) ?: targetDeviceIdInput
                        val isFresh = (trackingResult!!["isFreshFix"] as? Boolean) ?: false
                        val locSource = (trackingResult!!["locationSource"] as? String) ?: "LAST_KNOWN"
                        val isGpsAvail = (trackingResult!!["isGpsAvailable"] as? Boolean) ?: true
                        val lastSynced = (trackingResult!!["lastSyncedAt"] as? Number)?.toLong()
                        val syncStat = (trackingResult!!["syncStatus"] as? String) ?: "SYNCHRONISÉ"
                        val historyPoints = uiState.remoteDeviceBreadcrumbs.ifEmpty {
                            (trackingResult!!["historyPoints"] as? List<*>)?.filterIsInstance<com.example.domain.recovery.LocationBreadcrumbPoint>() ?: emptyList()
                        }

                        com.example.presentation.recovery.RealRecoveryMapView(
                            deviceId = targetDeviceIdInput,
                            deviceName = deviceName,
                            latitude = lat,
                            longitude = lng,
                            accuracy = accuracy,
                            timestamp = time,
                            battery = battery,
                            isCharging = isCharging,
                            connectivity = connectivity,
                            securityStatus = status,
                            isGpsAvailable = isGpsAvail,
                            historyPoints = historyPoints,
                            isFreshFix = isFresh,
                            locationSource = locSource,
                            altitude = (trackingResult!!["altitude"] as? Number)?.toDouble(),
                            speed = (trackingResult!!["speed"] as? Number)?.toFloat(),
                            bearing = (trackingResult!!["bearing"] as? Number)?.toFloat(),
                            syncStatus = syncStat,
                            lastSyncedAt = lastSynced,
                            selectedDateMillis = uiState.remoteSelectedDateMillis,
                            onSelectDate = onSelectRemoteHistoryDate,
                            isEn = isEn,
                            isRemoteDevice = true,
                            hasLocationPermission = true,
                            isTrackingActive = isGpsAvail,
                            isNetworkConnected = uiState.networkInfo.isConnected,
                            onRetryGps = {
                                val cleanId = targetDeviceIdInput.trim()
                                if (cleanId.isNotBlank()) {
                                    onStartObservingRemoteDevice?.invoke(cleanId)
                                }
                            }
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onStopObservingRemoteDevice?.invoke()
                        showTrackPhoneDialog = false
                    }
                ) {
                    Text(if (isEn) "Close" else "Fermer", color = InkingiCyan)
                }
            }
        )
    }
}
