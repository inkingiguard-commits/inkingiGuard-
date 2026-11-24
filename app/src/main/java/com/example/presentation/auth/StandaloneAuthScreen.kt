package com.example.presentation.auth

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.example.R
import com.example.presentation.InkingiUiState
import com.example.presentation.InkingiViewModel
import com.example.ui.theme.InkingiCyan
import com.example.ui.theme.InkingiNavyBorder
import com.example.ui.theme.InkingiNavyCard
import com.example.ui.theme.InkingiNavyCardElevated
import com.example.ui.theme.InkingiNavyDark
import com.example.ui.theme.InkingiRiskHigh
import com.example.ui.theme.InkingiRiskLow
import com.example.ui.theme.InkingiTextMuted
import com.example.ui.theme.InkingiTextPrimary
import com.example.ui.theme.InkingiTextSecondary
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch

private const val TAG = "AuthScreen"

/**
 * High-level internal navigation states for the standalone authentication flow.
 */
enum class AuthScreenMode {
    LOGIN,
    REGISTER,
    FORGOT_PASSWORD,
    EMAIL_VERIFICATION
}

/**
 * Dedicated full-screen authentication experience shown as the primary screen
 * whenever no Firebase user is authenticated.
 */
@Composable
fun StandaloneAuthScreen(
    viewModel: InkingiViewModel,
    uiState: InkingiUiState,
    onAuthSuccess: () -> Unit
) {
    var screenMode by remember {
        mutableStateOf(
            if (uiState.isAuthenticated && !uiState.isEmailVerified) AuthScreenMode.EMAIL_VERIFICATION
            else AuthScreenMode.LOGIN
        )
    }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // Clear messages when navigating between sub-screens
    fun navigateTo(mode: AuthScreenMode) {
        viewModel.clearAuthMessages()
        screenMode = mode
    }

    // Google Sign-In via Credential Manager
    fun handleGoogleSignIn() {
        val serverClientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (e: Exception) {
            ""
        }

        if (serverClientId.isBlank()) {
            Log.e(TAG, "OAuth Web Client ID is not configured in strings.xml (default_web_client_id).")
            return
        }

        coroutineScope.launch {
            try {
                val credentialManager = CredentialManager.create(context)
                val googleIdOption = GetSignInWithGoogleOption.Builder(serverClientId)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val result = credentialManager.getCredential(
                    request = request,
                    context = context
                )

                val credential = result.credential
                if (credential is androidx.credentials.CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val idToken = googleIdTokenCredential.idToken
                    viewModel.signInWithGoogle(idToken) { success ->
                        if (success) onAuthSuccess()
                    }
                } else {
                    Log.w(TAG, "Unexpected credential type returned: ${credential.type}")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.d(TAG, "Google sign-in was cancelled by user.")
            } catch (e: GetCredentialException) {
                Log.e(TAG, "Credential Manager error: ${e.message}", e)
                viewModel.clearAuthMessages()
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected Google Sign-In error: ${e.message}", e)
            }
        }
    }

    Scaffold(
        containerColor = InkingiNavyDark
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            InkingiNavyDark,
                            Color(0xFF030D1B),
                            Color(0xFF020710)
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 500.dp)
                ) {
                    when (screenMode) {
                        AuthScreenMode.LOGIN -> {
                            LoginView(
                                uiState = uiState,
                                onSignIn = { email, password ->
                                    viewModel.signInWithFirebase(email, password) { success ->
                                        if (success) {
                                            if (!uiState.isEmailVerified) {
                                                navigateTo(AuthScreenMode.EMAIL_VERIFICATION)
                                            } else {
                                                onAuthSuccess()
                                            }
                                        }
                                    }
                                },
                                onGoogleSignIn = { handleGoogleSignIn() },
                                onNavigateToRegister = { navigateTo(AuthScreenMode.REGISTER) },
                                onNavigateToForgotPassword = { navigateTo(AuthScreenMode.FORGOT_PASSWORD) },
                                onDismissError = { viewModel.clearAuthMessages() }
                            )
                        }

                        AuthScreenMode.REGISTER -> {
                            RegisterView(
                                uiState = uiState,
                                onRegister = { fullName, email, password, deviceName, masterPin ->
                                    viewModel.registerWithFirebase(
                                        email = email,
                                        password = password,
                                        deviceName = deviceName.ifBlank { "Android Secure Node" },
                                        masterPin = masterPin
                                    ) { success ->
                                        if (success) {
                                            navigateTo(AuthScreenMode.EMAIL_VERIFICATION)
                                        }
                                    }
                                },
                                onBackToLogin = { navigateTo(AuthScreenMode.LOGIN) },
                                onDismissError = { viewModel.clearAuthMessages() }
                            )
                        }

                        AuthScreenMode.FORGOT_PASSWORD -> {
                            ForgotPasswordView(
                                uiState = uiState,
                                onSendReset = { email ->
                                    viewModel.sendPasswordResetEmail(email)
                                },
                                onBackToLogin = { navigateTo(AuthScreenMode.LOGIN) },
                                onDismissError = { viewModel.clearAuthMessages() }
                            )
                        }

                        AuthScreenMode.EMAIL_VERIFICATION -> {
                            EmailVerificationView(
                                uiState = uiState,
                                onCheckStatus = {
                                    viewModel.checkEmailVerificationStatus { verified ->
                                        if (verified) onAuthSuccess()
                                    }
                                },
                                onResendEmail = {
                                    viewModel.sendEmailVerification()
                                },
                                onContinueAnyway = {
                                    // Owner can proceed into dashboard once email is verified
                                    onAuthSuccess()
                                },
                                onSignOut = {
                                    viewModel.signOutFromFirebase()
                                    navigateTo(AuthScreenMode.LOGIN)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Screen 1: Primary Login View with Email/Password, Google button, and registration navigation.
 */
@Composable
private fun LoginView(
    uiState: InkingiUiState,
    onSignIn: (email: String, password: String) -> Unit,
    onGoogleSignIn: () -> Unit,
    onNavigateToRegister: () -> Unit,
    onNavigateToForgotPassword: () -> Unit,
    onDismissError: () -> Unit
) {
    var email by remember { mutableStateOf(uiState.authenticatedUserEmail ?: "") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Shield Identity Banner
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(InkingiNavyCardElevated)
                .border(2.dp, InkingiCyan.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Shield,
                contentDescription = "InkingiGuard Shield",
                tint = InkingiCyan,
                modifier = Modifier.size(40.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "InkingiGuard AI",
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.Bold,
                color = InkingiTextPrimary,
                letterSpacing = 1.sp
            )
        )

        Text(
            text = "Système de Défense & Télémétrie Sécurisée",
            style = MaterialTheme.typography.bodyMedium.copy(
                color = InkingiTextMuted
            ),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Main Login Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = InkingiNavyCard),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, InkingiNavyBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                Text(
                    text = "AUTHENTIFICATION DE SÉCURITÉ",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = InkingiCyan,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                )

                Text(
                    text = "Connectez-vous pour autoriser les protocoles de surveillance et synchroniser le nœud.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = InkingiTextSecondary
                    ),
                    modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
                )

                // Error Banner
                AnimatedVisibility(visible = uiState.authErrorMessage != null) {
                    uiState.authErrorMessage?.let { error ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp),
                            color = InkingiRiskHigh.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, InkingiRiskHigh.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = InkingiRiskHigh,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall.copy(color = InkingiTextPrimary),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Email Field
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        onDismissError()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("login_email_input"),
                    label = { Text("Adresse Email") },
                    leadingIcon = {
                        Icon(Icons.Default.Mail, contentDescription = null, tint = InkingiCyan)
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = InkingiTextPrimary,
                        unfocusedTextColor = InkingiTextPrimary,
                        focusedBorderColor = InkingiCyan,
                        unfocusedBorderColor = InkingiNavyBorder,
                        focusedLabelColor = InkingiCyan,
                        unfocusedLabelColor = InkingiTextMuted,
                        focusedContainerColor = InkingiNavyCardElevated,
                        unfocusedContainerColor = InkingiNavyCardElevated
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Password Field
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        onDismissError()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("login_password_input"),
                    label = { Text("Mot de passe") },
                    leadingIcon = {
                        Icon(Icons.Default.Lock, contentDescription = null, tint = InkingiCyan)
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (passwordVisible) "Masquer" else "Afficher",
                                tint = InkingiTextMuted
                            )
                        }
                    },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = InkingiTextPrimary,
                        unfocusedTextColor = InkingiTextPrimary,
                        focusedBorderColor = InkingiCyan,
                        unfocusedBorderColor = InkingiNavyBorder,
                        focusedLabelColor = InkingiCyan,
                        unfocusedLabelColor = InkingiTextMuted,
                        focusedContainerColor = InkingiNavyCardElevated,
                        unfocusedContainerColor = InkingiNavyCardElevated
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                // Forgot Password link
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    Text(
                        text = "Mot de passe oublié ?",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = InkingiCyan,
                            fontWeight = FontWeight.Medium
                        ),
                        modifier = Modifier
                            .clickable { onNavigateToForgotPassword() }
                            .padding(vertical = 6.dp)
                            .testTag("forgot_password_button")
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Submit Button
                Button(
                    onClick = {
                        if (email.isNotBlank() && password.isNotBlank()) {
                            onSignIn(email.trim(), password)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("login_submit_button"),
                    enabled = !uiState.isAuthLoading && email.isNotBlank() && password.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = InkingiCyan,
                        contentColor = InkingiNavyDark
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.isAuthLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = InkingiNavyDark,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Authentification...", fontWeight = FontWeight.Bold)
                    } else {
                        Text("SE CONNECTER", fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Divider OR
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(InkingiNavyBorder)
                    )
                    Text(
                        text = "OU",
                        style = MaterialTheme.typography.labelSmall.copy(
                            color = InkingiTextMuted,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(InkingiNavyBorder)
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Continue with Google Button
                OutlinedButton(
                    onClick = onGoogleSignIn,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("google_signin_button"),
                    enabled = !uiState.isAuthLoading,
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, InkingiNavyBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = InkingiNavyCardElevated
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = "Google Icon",
                        tint = InkingiCyan,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Continuer avec Google",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = InkingiTextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                // Link to Create Account
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Nouveau sur InkingiGuard ? ",
                        style = MaterialTheme.typography.bodySmall.copy(color = InkingiTextMuted)
                    )
                    Text(
                        text = "Créer un compte",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = InkingiCyan,
                            fontWeight = FontWeight.Bold
                        ),
                        modifier = Modifier
                            .clickable { onNavigateToRegister() }
                            .padding(4.dp)
                            .testTag("navigate_to_register_button")
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Privacy and Security Reassurance Footer
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = InkingiTextMuted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Chiffrement matériel de bout en bout • Firebase Auth • Zéro télémétrie publique",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = InkingiTextMuted,
                    fontSize = 11.sp
                ),
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Screen 2: Separate Registration Screen with Full Name, Email, Password, Device Name, and Master PIN setup.
 */
@Composable
private fun RegisterView(
    uiState: InkingiUiState,
    onRegister: (fullName: String, email: String, password: String, deviceName: String, masterPin: String) -> Unit,
    onBackToLogin: () -> Unit,
    onDismissError: () -> Unit
) {
    var fullName by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var deviceName by remember { mutableStateOf("Mon Téléphone Sécurisé") }
    var masterPin by remember { mutableStateOf("") }
    var confirmMasterPin by remember { mutableStateOf("") }

    var passwordVisible by remember { mutableStateOf(false) }
    var localValidationMsg by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Back Navigation Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackToLogin,
                modifier = Modifier.testTag("register_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Retour",
                    tint = InkingiCyan
                )
            }
            Text(
                text = "Création de Compte Nœud",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = InkingiTextPrimary,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = InkingiNavyCard),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, InkingiNavyBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                Text(
                    text = "ENRÔLEMENT SÉCURISÉ DU NŒUD",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = InkingiCyan,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                )

                Text(
                    text = "Configurez vos identifiants Firebase Cloud et le code PIN Maître local (conservé exclusivement sur cet appareil).",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = InkingiTextSecondary
                    ),
                    modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
                )

                // Error Banner
                val currentError = localValidationMsg ?: uiState.authErrorMessage
                AnimatedVisibility(visible = currentError != null) {
                    currentError?.let { error ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            color = InkingiRiskHigh.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, InkingiRiskHigh.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = InkingiRiskHigh, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall.copy(color = InkingiTextPrimary),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }

                // Section 1: Compte Cloud
                Text(
                    text = "1. Compte Sécurisé Cloud",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = InkingiTextPrimary,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = fullName,
                    onValueChange = { fullName = it; localValidationMsg = null; onDismissError() },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Nom complet") },
                    leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = InkingiCyan) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = InkingiTextPrimary,
                        unfocusedTextColor = InkingiTextPrimary,
                        focusedBorderColor = InkingiCyan,
                        unfocusedBorderColor = InkingiNavyBorder,
                        focusedLabelColor = InkingiCyan,
                        unfocusedLabelColor = InkingiTextMuted,
                        focusedContainerColor = InkingiNavyCardElevated,
                        unfocusedContainerColor = InkingiNavyCardElevated
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; localValidationMsg = null; onDismissError() },
                    modifier = Modifier.fillMaxWidth().testTag("register_email_input"),
                    label = { Text("Email du propriétaire") },
                    leadingIcon = { Icon(Icons.Default.Mail, contentDescription = null, tint = InkingiCyan) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = InkingiTextPrimary,
                        unfocusedTextColor = InkingiTextPrimary,
                        focusedBorderColor = InkingiCyan,
                        unfocusedBorderColor = InkingiNavyBorder,
                        focusedLabelColor = InkingiCyan,
                        unfocusedLabelColor = InkingiTextMuted,
                        focusedContainerColor = InkingiNavyCardElevated,
                        unfocusedContainerColor = InkingiNavyCardElevated
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; localValidationMsg = null; onDismissError() },
                    modifier = Modifier.fillMaxWidth().testTag("register_password_input"),
                    label = { Text("Mot de passe (min 6 car.)") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = InkingiCyan) },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = null,
                                tint = InkingiTextMuted
                            )
                        }
                    },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = InkingiTextPrimary,
                        unfocusedTextColor = InkingiTextPrimary,
                        focusedBorderColor = InkingiCyan,
                        unfocusedBorderColor = InkingiNavyBorder,
                        focusedLabelColor = InkingiCyan,
                        unfocusedLabelColor = InkingiTextMuted,
                        focusedContainerColor = InkingiNavyCardElevated,
                        unfocusedContainerColor = InkingiNavyCardElevated
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it; localValidationMsg = null; onDismissError() },
                    modifier = Modifier.fillMaxWidth().testTag("register_confirm_password_input"),
                    label = { Text("Confirmer mot de passe") },
                    leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = InkingiCyan) },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = InkingiTextPrimary,
                        unfocusedTextColor = InkingiTextPrimary,
                        focusedBorderColor = InkingiCyan,
                        unfocusedBorderColor = InkingiNavyBorder,
                        focusedLabelColor = InkingiCyan,
                        unfocusedLabelColor = InkingiTextMuted,
                        focusedContainerColor = InkingiNavyCardElevated,
                        unfocusedContainerColor = InkingiNavyCardElevated
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(18.dp))

                // Section 2: Identité Locale de l'Appareil
                Text(
                    text = "2. Identité Matérielle Locale",
                    style = MaterialTheme.typography.labelMedium.copy(
                        color = InkingiTextPrimary,
                        fontWeight = FontWeight.SemiBold
                    ),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it; localValidationMsg = null; onDismissError() },
                    modifier = Modifier.fillMaxWidth().testTag("register_device_name_input"),
                    label = { Text("Nom de l'appareil") },
                    leadingIcon = { Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = InkingiCyan) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = InkingiTextPrimary,
                        unfocusedTextColor = InkingiTextPrimary,
                        focusedBorderColor = InkingiCyan,
                        unfocusedBorderColor = InkingiNavyBorder,
                        focusedLabelColor = InkingiCyan,
                        unfocusedLabelColor = InkingiTextMuted,
                        focusedContainerColor = InkingiNavyCardElevated,
                        unfocusedContainerColor = InkingiNavyCardElevated
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = masterPin,
                        onValueChange = {
                            if (it.length <= 8 && it.all { ch -> ch.isDigit() }) {
                                masterPin = it
                                localValidationMsg = null
                                onDismissError()
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("register_master_pin_input"),
                        label = { Text("Code PIN (4-8 ch.)") },
                        leadingIcon = { Icon(Icons.Default.Key, contentDescription = null, tint = InkingiCyan) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = InkingiTextPrimary,
                            unfocusedTextColor = InkingiTextPrimary,
                            focusedBorderColor = InkingiCyan,
                            unfocusedBorderColor = InkingiNavyBorder,
                            focusedLabelColor = InkingiCyan,
                            unfocusedLabelColor = InkingiTextMuted,
                            focusedContainerColor = InkingiNavyCardElevated,
                            unfocusedContainerColor = InkingiNavyCardElevated
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    OutlinedTextField(
                        value = confirmMasterPin,
                        onValueChange = {
                            if (it.length <= 8 && it.all { ch -> ch.isDigit() }) {
                                confirmMasterPin = it
                                localValidationMsg = null
                                onDismissError()
                            }
                        },
                        modifier = Modifier.weight(1f).testTag("register_confirm_master_pin_input"),
                        label = { Text("Confirmer PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = InkingiTextPrimary,
                            unfocusedTextColor = InkingiTextPrimary,
                            focusedBorderColor = InkingiCyan,
                            unfocusedBorderColor = InkingiNavyBorder,
                            focusedLabelColor = InkingiCyan,
                            unfocusedLabelColor = InkingiTextMuted,
                            focusedContainerColor = InkingiNavyCardElevated,
                            unfocusedContainerColor = InkingiNavyCardElevated
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Submit Button
                Button(
                    onClick = {
                        val pinValidation = com.example.domain.security.OfflineSecurityVault.validatePinStrength(masterPin)
                        when {
                            email.isBlank() || !email.contains("@") ->
                                localValidationMsg = "Veuillez saisir une adresse email valide."
                            password.length < 6 ->
                                localValidationMsg = "Le mot de passe doit comporter au moins 6 caractères."
                            password != confirmPassword ->
                                localValidationMsg = "Les deux mots de passe ne correspondent pas."
                            pinValidation is com.example.domain.security.OfflineSecurityVault.PinValidationResult.InvalidFormat ->
                                localValidationMsg = "Le code PIN Maître doit comporter entre 4 et 8 chiffres."
                            pinValidation is com.example.domain.security.OfflineSecurityVault.PinValidationResult.WeakPin ->
                                localValidationMsg = "Les codes PIN faibles ou par défaut (ex: 1234, 0000) sont interdits."
                            masterPin != confirmMasterPin ->
                                localValidationMsg = "Les codes PIN Maître ne correspondent pas."
                            else -> {
                                localValidationMsg = null
                                onRegister(fullName.trim(), email.trim(), password, deviceName.trim(), masterPin)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("register_submit_button"),
                    enabled = !uiState.isAuthLoading,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = InkingiCyan,
                        contentColor = InkingiNavyDark
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.isAuthLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = InkingiNavyDark, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Enrôlement en cours...", fontWeight = FontWeight.Bold)
                    } else {
                        Text("CRÉER LE COMPTE & SÉCURISER", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Screen 3: Forgot Password Screen for Firebase password reset requests.
 */
@Composable
private fun ForgotPasswordView(
    uiState: InkingiUiState,
    onSendReset: (email: String) -> Unit,
    onBackToLogin: () -> Unit,
    onDismissError: () -> Unit
) {
    var email by remember { mutableStateOf(uiState.authenticatedUserEmail ?: "") }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBackToLogin,
                modifier = Modifier.testTag("forgot_password_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Retour",
                    tint = InkingiCyan
                )
            }
            Text(
                text = "Récupération d'Accès",
                style = MaterialTheme.typography.titleMedium.copy(
                    color = InkingiTextPrimary,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.padding(start = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = InkingiNavyCard),
            shape = RoundedCornerShape(20.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, InkingiNavyBorder)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp)
            ) {
                Text(
                    text = "RÉINITIALISATION DU MOT DE PASSE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = InkingiCyan,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                )

                Text(
                    text = "Entrez l'adresse email associée à votre nœud de sécurité pour recevoir un lien de réinitialisation sécurisé par Firebase.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = InkingiTextSecondary
                    ),
                    modifier = Modifier.padding(top = 4.dp, bottom = 18.dp)
                )

                // Success confirmation
                AnimatedVisibility(visible = uiState.authResetEmailSent) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 14.dp),
                        color = InkingiRiskLow.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, InkingiRiskLow.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = InkingiRiskLow, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = uiState.authSuccessMessage ?: "Lien de réinitialisation envoyé ! Vérifiez votre boîte de réception.",
                                style = MaterialTheme.typography.bodySmall.copy(color = InkingiTextPrimary)
                            )
                        }
                    }
                }

                // Error message
                AnimatedVisibility(visible = uiState.authErrorMessage != null) {
                    uiState.authErrorMessage?.let { error ->
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 14.dp),
                            color = InkingiRiskHigh.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, InkingiRiskHigh.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = InkingiRiskHigh, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = error,
                                    style = MaterialTheme.typography.bodySmall.copy(color = InkingiTextPrimary)
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        onDismissError()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reset_password_email_input"),
                    label = { Text("Votre adresse email") },
                    leadingIcon = { Icon(Icons.Default.Mail, contentDescription = null, tint = InkingiCyan) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = InkingiTextPrimary,
                        unfocusedTextColor = InkingiTextPrimary,
                        focusedBorderColor = InkingiCyan,
                        unfocusedBorderColor = InkingiNavyBorder,
                        focusedLabelColor = InkingiCyan,
                        unfocusedLabelColor = InkingiTextMuted,
                        focusedContainerColor = InkingiNavyCardElevated,
                        unfocusedContainerColor = InkingiNavyCardElevated
                    ),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        if (email.isNotBlank()) {
                            onSendReset(email.trim())
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .testTag("send_reset_password_button"),
                    enabled = !uiState.isAuthLoading && email.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = InkingiCyan,
                        contentColor = InkingiNavyDark
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (uiState.isAuthLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = InkingiNavyDark, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Envoi en cours...", fontWeight = FontWeight.Bold)
                    } else {
                        Text("ENVOYER LE LIEN DE RÉINITIALISATION", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                TextButton(
                    onClick = onBackToLogin,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Retour à la connexion", color = InkingiTextMuted)
                }
            }
        }
    }
}

/**
 * Screen 4: Email Verification Waiting Screen.
 */
@Composable
private fun EmailVerificationView(
    uiState: InkingiUiState,
    onCheckStatus: () -> Unit,
    onResendEmail: () -> Unit,
    onContinueAnyway: () -> Unit,
    onSignOut: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = InkingiNavyCard),
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, InkingiNavyBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(InkingiNavyCardElevated)
                    .border(2.dp, InkingiCyan, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Mail,
                    contentDescription = null,
                    tint = InkingiCyan,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Vérification d'Email Requise",
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = InkingiTextPrimary
                )
            )

            Text(
                text = "Un lien de confirmation de sécurité a été envoyé à :",
                style = MaterialTheme.typography.bodySmall.copy(color = InkingiTextMuted),
                modifier = Modifier.padding(top = 6.dp)
            )

            Text(
                text = uiState.authenticatedUserEmail ?: "votre adresse email",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = InkingiCyan,
                    fontWeight = FontWeight.Bold
                ),
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Text(
                text = "Veuillez cliquer sur le lien dans votre boîte mail pour autoriser la transmission de télémétrie sur le Cloud.",
                style = MaterialTheme.typography.bodySmall.copy(
                    color = InkingiTextSecondary,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)
            )

            // Dynamic Error Banner
            AnimatedVisibility(visible = uiState.authErrorMessage != null) {
                uiState.authErrorMessage?.let { error ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        color = InkingiRiskHigh.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, InkingiRiskHigh.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = error,
                            style = MaterialTheme.typography.bodySmall.copy(color = InkingiTextPrimary),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }

            // Check Status Button
            Button(
                onClick = onCheckStatus,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("verify_status_refresh_button"),
                enabled = !uiState.isAuthLoading,
                colors = ButtonDefaults.buttonColors(
                    containerColor = InkingiCyan,
                    contentColor = InkingiNavyDark
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (uiState.isAuthLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = InkingiNavyDark, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Vérification en cours...")
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("J'ai vérifié mon email", fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Resend link button
            OutlinedButton(
                onClick = onResendEmail,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                enabled = !uiState.isAuthLoading,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, InkingiNavyBorder),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = InkingiNavyCardElevated)
            ) {
                Text("Renvoyer le lien de confirmation", color = InkingiTextPrimary)
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Change account / Sign out
            TextButton(
                onClick = onSignOut,
                modifier = Modifier.testTag("auth_sign_out_button")
            ) {
                Text("Se connecter avec un autre compte", color = InkingiTextMuted, fontSize = 12.sp)
            }
        }
    }
}
