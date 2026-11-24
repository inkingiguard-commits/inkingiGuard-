package com.example.presentation.auth

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.presentation.InkingiUiState
import com.example.ui.theme.InkingiCyan
import com.example.ui.theme.InkingiNavyBorder
import com.example.ui.theme.InkingiNavyCardElevated
import com.example.ui.theme.InkingiNavyDark
import com.example.ui.theme.InkingiRiskHigh
import com.example.ui.theme.InkingiRiskLow
import com.example.ui.theme.InkingiRiskMedium
import com.example.ui.theme.InkingiTextMuted
import com.example.ui.theme.InkingiTextPrimary
import com.example.ui.theme.InkingiTextSecondary

@Composable
fun OnboardingAuthDialog(
    uiState: InkingiUiState,
    initialDeviceName: String,
    initialOwnerEmail: String,
    onSignIn: (email: String, password: String) -> Unit,
    onRegister: (email: String, password: String, deviceName: String, masterPin: String) -> Unit,
    onSendVerificationEmail: () -> Unit,
    onCheckVerificationStatus: () -> Unit,
    onDismiss: () -> Unit
) {
    val isEn = uiState.currentLanguage.equals("en", ignoreCase = true)
    var selectedTab by remember { mutableIntStateOf(if (uiState.isAuthenticated) 0 else 1) }

    var email by remember { mutableStateOf(initialOwnerEmail) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf(initialDeviceName.ifBlank { "Android Secure Node" }) }
    var masterPin by remember { mutableStateOf("") }
    var confirmMasterPin by remember { mutableStateOf("") }

    var passwordVisible by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = InkingiNavyCardElevated,
        shape = RoundedCornerShape(16.dp),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = InkingiCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isEn) "Identity & Cloud Protection" else "Identité & Protection Cloud",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = InkingiTextPrimary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
            ) {
                // Firebase Status Banner
                if (!uiState.isFirebaseAvailable) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(InkingiRiskMedium.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, tint = InkingiRiskMedium, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isEn)
                                    "Firebase not configured on device. Local offline protection active."
                                else
                                    "Firebase non configuré sur cet appareil. Protection locale hors-ligne active.",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkingiRiskMedium
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // If currently authenticated but email unverified
                if (uiState.isAuthenticated && !uiState.isEmailVerified) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(InkingiCyan.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Mail, contentDescription = null, tint = InkingiCyan, modifier = Modifier.size(20.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (isEn) "Email Verification Required" else "Vérification de l'email requise",
                                    fontWeight = FontWeight.Bold,
                                    color = InkingiCyan,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = if (isEn)
                                    "A confirmation link was dispatched to ${uiState.authenticatedUserEmail ?: email}. Please verify your email to unlock all cloud security services."
                                else
                                    "Un lien de confirmation a été envoyé à ${uiState.authenticatedUserEmail ?: email}. Cliquez sur ce lien pour débloquer tous les services cloud.",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkingiTextSecondary
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = onCheckVerificationStatus,
                                    colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan),
                                    modifier = Modifier.weight(1f).testTag("auth_check_verify_btn")
                                ) {
                                    Text(
                                        text = if (isEn) "Check Status" else "Vérifier statut",
                                        color = InkingiNavyDark,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp
                                    )
                                }
                                OutlinedButton(
                                    onClick = onSendVerificationEmail,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = InkingiCyan),
                                    modifier = Modifier.weight(1f).testTag("auth_resend_verify_btn")
                                ) {
                                    Text(
                                        text = if (isEn) "Resend Email" else "Renvoyer",
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // If authenticated and verified
                if (uiState.isAuthenticated && uiState.isEmailVerified) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(InkingiRiskLow.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudDone, contentDescription = null, tint = InkingiRiskLow, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = if (isEn) "Authenticated & Verified" else "Authentifié & Vérifié",
                                    fontWeight = FontWeight.Bold,
                                    color = InkingiRiskLow,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    text = uiState.authenticatedUserEmail ?: email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = InkingiTextPrimary
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Tab selection: Sign In vs Register (if not authenticated)
                if (!uiState.isAuthenticated) {
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = InkingiNavyDark,
                        contentColor = InkingiCyan,
                        indicator = { tabPositions ->
                            TabRowDefaults.SecondaryIndicator(
                                Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                                color = InkingiCyan
                            )
                        }
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = {
                                selectedTab = 0
                                localError = null
                            },
                            text = { Text(if (isEn) "Sign In" else "Connexion", fontWeight = FontWeight.Bold) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = {
                                selectedTab = 1
                                localError = null
                            },
                            text = { Text(if (isEn) "Register" else "Créer Compte", fontWeight = FontWeight.Bold) }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Common Email input
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; localError = null },
                        label = { Text(if (isEn) "Email Address" else "Adresse Email") },
                        modifier = Modifier.fillMaxWidth().testTag("auth_email_input"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = InkingiCyan,
                            focusedLabelColor = InkingiCyan,
                            unfocusedBorderColor = InkingiNavyBorder,
                            focusedTextColor = InkingiTextPrimary,
                            unfocusedTextColor = InkingiTextPrimary
                        )
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Common Password input
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; localError = null },
                        label = { Text(if (isEn) "Password" else "Mot de passe") },
                        visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = InkingiTextMuted
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth().testTag("auth_password_input"),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = InkingiCyan,
                            focusedLabelColor = InkingiCyan,
                            unfocusedBorderColor = InkingiNavyBorder,
                            focusedTextColor = InkingiTextPrimary,
                            unfocusedTextColor = InkingiTextPrimary
                        )
                    )

                    // Registration Additional Fields
                    AnimatedVisibility(visible = selectedTab == 1) {
                        Column {
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = confirmPassword,
                                onValueChange = { confirmPassword = it; localError = null },
                                label = { Text(if (isEn) "Confirm Password" else "Confirmer le mot de passe") },
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth().testTag("auth_confirm_password_input"),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = InkingiCyan,
                                    focusedLabelColor = InkingiCyan,
                                    unfocusedBorderColor = InkingiNavyBorder,
                                    focusedTextColor = InkingiTextPrimary,
                                    unfocusedTextColor = InkingiTextPrimary
                                )
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = deviceName,
                                onValueChange = { deviceName = it; localError = null },
                                label = { Text(if (isEn) "Device Hardware Name" else "Nom du terminal") },
                                modifier = Modifier.fillMaxWidth().testTag("auth_device_name_input"),
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = InkingiCyan,
                                    focusedLabelColor = InkingiCyan,
                                    unfocusedBorderColor = InkingiNavyBorder,
                                    focusedTextColor = InkingiTextPrimary,
                                    unfocusedTextColor = InkingiTextPrimary
                                )
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = masterPin,
                                    onValueChange = {
                                        if (it.length <= 8 && it.all { ch -> ch.isDigit() }) {
                                            masterPin = it
                                            localError = null
                                        }
                                    },
                                    label = { Text(if (isEn) "Master PIN (4-8)" else "PIN Maître (4-8)") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.weight(1f).testTag("auth_master_pin_input"),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = InkingiCyan,
                                        focusedLabelColor = InkingiCyan,
                                        unfocusedBorderColor = InkingiNavyBorder,
                                        focusedTextColor = InkingiTextPrimary,
                                        unfocusedTextColor = InkingiTextPrimary
                                    )
                                )
                                OutlinedTextField(
                                    value = confirmMasterPin,
                                    onValueChange = {
                                        if (it.length <= 8 && it.all { ch -> ch.isDigit() }) {
                                            confirmMasterPin = it
                                            localError = null
                                        }
                                    },
                                    label = { Text(if (isEn) "Confirm PIN" else "Confirmer PIN") },
                                    visualTransformation = PasswordVisualTransformation(),
                                    modifier = Modifier.weight(1f).testTag("auth_confirm_pin_input"),
                                    singleLine = true,
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = InkingiCyan,
                                        focusedLabelColor = InkingiCyan,
                                        unfocusedBorderColor = InkingiNavyBorder,
                                        focusedTextColor = InkingiTextPrimary,
                                        unfocusedTextColor = InkingiTextPrimary
                                    )
                                )
                            }
                            Text(
                                text = if (isEn)
                                    "Note: Master PIN is kept strictly on-device for alarm disarm and local settings."
                                else
                                    "Note : Le code PIN reste strictement local sur l'appareil pour le désarmement.",
                                style = MaterialTheme.typography.labelSmall,
                                color = InkingiTextMuted,
                                modifier = Modifier.padding(top = 4.dp, start = 2.dp)
                            )
                        }
                    }
                }

                // Error Message Display
                val displayError = localError ?: uiState.authErrorMessage
                if (displayError != null) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = displayError,
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiRiskHigh,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.testTag("auth_error_text")
                    )
                }

                // Loading Indicator
                if (uiState.isAuthLoading) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            color = InkingiCyan,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!uiState.isAuthenticated) {
                Button(
                    onClick = {
                        localError = null
                        if (email.isBlank() || !email.contains("@")) {
                            localError = if (isEn) "Please enter a valid email address." else "Veuillez saisir une adresse email valide."
                            return@Button
                        }
                        if (password.length < 6) {
                            localError = if (isEn) "Password must be at least 6 characters." else "Le mot de passe doit comporter au moins 6 caractères."
                            return@Button
                        }

                        if (selectedTab == 0) {
                            onSignIn(email.trim(), password)
                        } else {
                            if (password != confirmPassword) {
                                localError = if (isEn) "Passwords do not match." else "Les mots de passe ne correspondent pas."
                                return@Button
                            }
                            val pinValidation = com.example.domain.security.OfflineSecurityVault.validatePinStrength(masterPin)
                            when (pinValidation) {
                                is com.example.domain.security.OfflineSecurityVault.PinValidationResult.InvalidFormat -> {
                                    localError = if (isEn)
                                        "Master PIN must be 4 to 8 numeric digits."
                                    else
                                        "Le PIN Maître doit comporter 4 à 8 chiffres."
                                    return@Button
                                }
                                is com.example.domain.security.OfflineSecurityVault.PinValidationResult.WeakPin -> {
                                    localError = if (isEn)
                                        "Weak or sequential PINs (e.g. 1234, 0000) are not allowed."
                                    else
                                        "Les codes PIN faibles ou séquentiels (ex: 1234, 0000) sont interdits."
                                    return@Button
                                }
                                is com.example.domain.security.OfflineSecurityVault.PinValidationResult.Valid -> Unit
                            }
                            if (masterPin != confirmMasterPin) {
                                localError = if (isEn) "Master PINs do not match." else "Les codes PIN ne correspondent pas."
                                return@Button
                            }
                            onRegister(email.trim(), password, deviceName.trim(), masterPin)
                        }
                    },
                    modifier = Modifier.testTag("auth_submit_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan),
                    enabled = !uiState.isAuthLoading
                ) {
                    Text(
                        text = if (selectedTab == 0) {
                            if (isEn) "Sign In" else "Se connecter"
                        } else {
                            if (isEn) "Register & Protect" else "Créer & Protéger"
                        },
                        color = InkingiNavyDark,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                ) {
                    Text(
                        text = if (isEn) "Continue" else "Continuer",
                        color = InkingiNavyDark,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = if (isEn) "Close / Offline" else "Fermer / Hors-ligne",
                    color = InkingiTextMuted
                )
            }
        }
    )
}
