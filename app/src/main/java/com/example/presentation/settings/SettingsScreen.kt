package com.example.presentation.settings

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Surface
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.model.UserRole
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

@Composable
fun SettingsScreen(
    uiState: InkingiUiState,
    onSetLanguage: (String) -> Unit,
    onSwitchUserRole: (UserRole, String?) -> Boolean,
    onAddTrustedPerson: (String, String, String, String) -> Boolean,
    onRemoveTrustedPerson: (String, String) -> Boolean,
    onSimulateSimChange: () -> Unit,
    onSimulateDisplacement: () -> Unit,
    onSimulateKineticImpact: () -> Unit,
    onSimulateReboot: () -> Unit,
    onSimulateNetworkDrop: () -> Unit,
    onResetSimulations: () -> Unit,
    onOpenAuthDialog: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onSendVerificationEmail: () -> Unit = {},
    onCheckVerificationStatus: () -> Unit = {},
    onNavigateToAdminConsole: () -> Unit = {},
    onEnrollMasterPin: (String) -> Boolean = { false },
    onChangeMasterPin: (String, String) -> Boolean = { _, _ -> false }
) {
    var showPinDialogForRoleSwitch by remember { mutableStateOf<UserRole?>(null) }
    var enteredRolePin by remember { mutableStateOf("") }
    var rolePinError by remember { mutableStateOf<String?>(null) }

    var showAddPersonDialog by remember { mutableStateOf(false) }
    var newPersonName by remember { mutableStateOf("") }
    var newPersonPhone by remember { mutableStateOf("") }
    var newPersonRelation by remember { mutableStateOf("Famille") }
    var newPersonPin by remember { mutableStateOf("") }
    var addPersonError by remember { mutableStateOf<String?>(null) }

    var personToDeleteId by remember { mutableStateOf<String?>(null) }
    var deletePersonPin by remember { mutableStateOf("") }
    var deletePersonError by remember { mutableStateOf<String?>(null) }

    var showPinSetupDialog by remember { mutableStateOf(false) }
    var currentPinInput by remember { mutableStateOf("") }
    var newPinInput by remember { mutableStateOf("") }
    var confirmPinInput by remember { mutableStateOf("") }
    var pinSetupError by remember { mutableStateOf<String?>(null) }

    val isEn = uiState.currentLanguage.equals("en", ignoreCase = true)
    val isGuestMode = uiState.currentUserRole == UserRole.GUEST

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(InkingiNavyDark)
            .padding(horizontal = 16.dp)
            .testTag("settings_list"),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Column {
                Text(
                    text = stringResource(R.string.nav_settings),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = InkingiTextPrimary
                )
                Text(
                    text = if (isEn)
                        "Role governance (Owner/Guest), trusted contacts and hardware diagnostics"
                    else
                        "Gouvernance des rôles (Propriétaire/Invité), personnes de confiance et diagnostics",
                    style = MaterialTheme.typography.bodySmall,
                    color = InkingiTextSecondary
                )
            }
        }

        // Firebase Cloud Account & Authentication Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (uiState.isAuthenticated) InkingiCyan.copy(alpha = 0.5f) else InkingiNavyBorder,
                        RoundedCornerShape(16.dp)
                    ),
                colors = CardDefaults.cardColors(containerColor = InkingiNavyCardElevated)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                imageVector = if (uiState.isAuthenticated) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = if (uiState.isAuthenticated) InkingiCyan else InkingiTextMuted
                            )
                            Text(
                                text = if (isEn) "Firebase Cloud Account" else "Compte Firebase Cloud",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = InkingiTextPrimary
                            )
                        }

                        if (uiState.isAuthenticated) {
                            Surface(
                                color = if (uiState.isEmailVerified) InkingiRiskLow.copy(alpha = 0.2f) else InkingiRiskMedium.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    text = if (uiState.isEmailVerified) {
                                        if (isEn) "Verified" else "Vérifié"
                                    } else {
                                        if (isEn) "Unverified" else "Non vérifié"
                                    },
                                    color = if (uiState.isEmailVerified) InkingiRiskLow else InkingiRiskMedium,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (uiState.isAuthenticated) {
                        Text(
                            text = (uiState.authenticatedUserEmail ?: uiState.deviceProfile.ownerEmail)
                                .ifBlank { if (isEn) "No email linked" else "Aucun e-mail lié" },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = InkingiTextPrimary
                        )
                        uiState.authenticatedUserId?.let { uid ->
                            Text(
                                text = "UID : $uid",
                                style = MaterialTheme.typography.labelSmall,
                                color = InkingiTextMuted
                            )
                        }

                        if (!uiState.isEmailVerified) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = if (isEn)
                                    "Your email is pending verification. Check your inbox and click the verification link."
                                else
                                    "Email en attente de vérification. Consultez vos e-mails et cliquez sur le lien.",
                                style = MaterialTheme.typography.bodySmall,
                                color = InkingiRiskMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = onCheckVerificationStatus,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = InkingiCyan),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (isEn) "Check Status" else "Vérifier statut", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = onSendVerificationEmail,
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = InkingiCyan),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(if (isEn) "Resend Email" else "Renvoyer", fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Super Admin Console Button (Only visible if verified Super Admin)
                        if (uiState.isSuperAdmin) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp),
                                colors = CardDefaults.cardColors(containerColor = InkingiNavyCardElevated),
                                shape = RoundedCornerShape(12.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, InkingiRiskMedium)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                .background(InkingiRiskMedium)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = if (isEn) "SUPER ADMIN PRIVILEGES ACTIVE" else "PRIVILÈGES SUPER ADMIN ACTIFS",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                color = InkingiRiskMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = if (isEn)
                                            "Access system governance, verify users, and monitor cloud security rules."
                                        else
                                            "Accédez à la gouvernance système, contrôlez les utilisateurs et supervisez les règles cloud.",
                                        style = MaterialTheme.typography.bodySmall.copy(color = InkingiTextSecondary, fontSize = 11.sp)
                                    )
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Button(
                                        onClick = onNavigateToAdminConsole,
                                        colors = ButtonDefaults.buttonColors(containerColor = InkingiRiskMedium),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("open_super_admin_console_button")
                                    ) {
                                        Text(
                                            text = if (isEn) "OPEN SUPER ADMIN CONSOLE" else "OUVRIR LA CONSOLE SUPER ADMIN",
                                            color = InkingiNavyDark,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = onSignOut,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = InkingiRiskHigh),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(if (isEn) "Sign Out from Firebase" else "Se déconnecter de Firebase", fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text(
                            text = if (isEn)
                                "Not connected to Firebase. Sign in with your account to synchronize multi-device recovery telemetry."
                            else
                                "Non connecté à Firebase. Connectez-vous avec votre compte pour synchroniser la télémétrie de suivi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkingiTextSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = onOpenAuthDialog,
                            colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (isEn) "Sign In / Register Cloud Account" else "Connexion / Inscription Cloud",
                                color = InkingiNavyDark,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Active Role & Governance Card
        item {
            val roleColor = when (uiState.currentUserRole) {
                UserRole.OWNER -> InkingiCyan
                UserRole.GUEST -> InkingiRiskMedium
                UserRole.TRUSTED_PERSON -> InkingiRiskLow
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, roleColor.copy(alpha = 0.6f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = InkingiNavyCardElevated)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Person, contentDescription = null, tint = roleColor)
                            Text(
                                text = if (isEn) "Active Profile: ${uiState.currentUserRole.name}" else "Profil Actif : ${uiState.currentUserRole.name}",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = InkingiTextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (isGuestMode) {
                            if (isEn)
                                "Guest Mode active: Restricted secure access. System modifications, alarm dismissal, and anti-theft controls require owner authentication."
                            else
                                "Mode Invité actif : Accès sécurisé restreint. Les modifications système, arrêts d'alarme et actions anti-vol requièrent l'authentification maître du propriétaire."
                        } else {
                            if (isEn)
                                "Owner Mode active: Full privileges for security governance, emergency contacts, and hardware lock."
                            else
                                "Mode Propriétaire actif : Privilèges complets de gestion de la sécurité, des contacts d'urgence et du verrouillage matériel."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiTextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        UserRole.entries.forEach { role ->
                            val isSelected = uiState.currentUserRole == role
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    if (role == UserRole.OWNER && isGuestMode) {
                                        enteredRolePin = ""
                                        rolePinError = null
                                        showPinDialogForRoleSwitch = role
                                    } else {
                                        onSwitchUserRole(role, null)
                                    }
                                },
                                label = { Text(role.name) },
                                modifier = Modifier.testTag("role_chip_${role.name.lowercase()}"),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = roleColor,
                                    selectedLabelColor = InkingiNavyDark,
                                    containerColor = InkingiNavyBorder,
                                    labelColor = InkingiTextPrimary
                                )
                            )
                        }
                    }
                }
            }
        }

        // Trusted Persons Registry Card
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, tint = InkingiCyan, modifier = Modifier.size(20.dp))
                            Text(
                                text = if (isEn) "Trusted Contacts (${uiState.trustedPersons.size})" else "Personnes de Confiance (${uiState.trustedPersons.size})",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = InkingiTextPrimary
                            )
                        }

                        if (!isGuestMode) {
                            OutlinedButton(
                                onClick = {
                                    newPersonName = ""
                                    newPersonPhone = ""
                                    newPersonRelation = if (isEn) "Family" else "Famille"
                                    newPersonPin = ""
                                    addPersonError = null
                                    showAddPersonDialog = true
                                },
                                modifier = Modifier.testTag("add_trusted_person_button"),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = InkingiCyan)
                            ) {
                                Text(if (isEn) "+ Add" else "+ Ajouter", fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isEn)
                            "Authorized emergency contacts stored on device. Alerts are queued only for enabled contacts under an active owner session."
                        else
                            "Contacts d'urgence autorisés enregistrés sur l'appareil. Les alertes ne sont mises en file d'attente que pour les contacts activés sous session propriétaire.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiTextSecondary
                    )

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = if (uiState.alertDispatchStatus.isExternalContactDispatchConfigured) {
                            if (isEn) "External alert dispatch service: Configured"
                            else "Service d'envoi externe aux contacts : Configuré"
                        } else {
                            if (isEn) "External alert dispatch (Push/SMS/Cloud Function): Not configured — no automatic SMS/Push is sent without a configured backend relay."
                            else "Envoi externe aux contacts (Push/SMS/Cloud Function) : Non configuré — aucun SMS/Push automatique n'est envoyé sans service relais backend configuré."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (uiState.alertDispatchStatus.isExternalContactDispatchConfigured) InkingiRiskLow else InkingiRiskMedium,
                        modifier = Modifier.testTag("settings_trusted_dispatch_readiness")
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    if (uiState.trustedPersons.isEmpty()) {
                        Text(
                            text = if (isEn) "No trusted contacts registered." else "Aucune personne de confiance enregistrée.",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkingiTextMuted
                        )
                    } else {
                        uiState.trustedPersons.forEach { person ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "${person.name} (${person.relationship})",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                        color = InkingiTextPrimary
                                    )
                                    Text(
                                        text = person.phoneNumber,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = InkingiTextSecondary
                                    )
                                }

                                if (!isGuestMode) {
                                    IconButton(
                                        onClick = {
                                            personToDeleteId = person.id
                                            deletePersonPin = ""
                                            deletePersonError = null
                                        },
                                        modifier = Modifier.testTag("delete_person_${person.id}")
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = if (isEn) "Remove" else "Retirer", tint = InkingiRiskHigh, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Language Selection Card
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
                        Icon(Icons.Default.Language, contentDescription = null, tint = InkingiCyan, modifier = Modifier.size(20.dp))
                        Text(
                            text = stringResource(R.string.language_selection),
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = InkingiTextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            Triple("fr", "Français", "lang_fr_chip"),
                            Triple("en", "English", "lang_en_chip")
                        ).forEach { (code, label, tag) ->
                            val isSelected = uiState.currentLanguage == code
                            FilterChip(
                                selected = isSelected,
                                onClick = { onSetLanguage(code) },
                                label = { Text(label) },
                                modifier = Modifier.testTag(tag),
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = InkingiCyan,
                                    selectedLabelColor = InkingiNavyDark,
                                    containerColor = InkingiNavyBorder,
                                    labelColor = InkingiTextPrimary
                                )
                            )
                        }
                    }
                }
            }
        }

        // Device Profile Card
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
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = InkingiCyan, modifier = Modifier.size(20.dp))
                        Text(
                            text = if (isEn) "Enrolled Hardware Identity" else "Identité Matérielle Enregistrée",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = InkingiTextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val ownerEmailDisplay = (uiState.authenticatedUserEmail ?: uiState.deviceProfile.ownerEmail)
                        .ifBlank { if (isEn) "No email linked" else "Aucun e-mail lié" }
                    ProfileRow(if (isEn) "Node Identifier" else "Identifiant du Nœud", uiState.deviceProfile.deviceId)
                    ProfileRow(if (isEn) "Device Name" else "Nom de l'Appareil", uiState.deviceProfile.deviceName)
                    ProfileRow(if (isEn) "Security Owner" else "Propriétaire Sécurité", ownerEmailDisplay)
                    ProfileRow(
                        if (isEn) "Master PIN Vault" else "Coffre PIN Maître",
                        if (uiState.isPinConfigured) {
                            if (isEn) "Enrolled (PBKDF2 + Keystore)" else "Enregistré (PBKDF2 + Keystore)"
                        } else {
                            if (isEn) "Not set" else "Non défini"
                        }
                    )
                    ProfileRow(
                        if (isEn) "Hardware Keystore" else "Keystore Matériel",
                        if (uiState.isVaultKeystoreHealthy) {
                            if (isEn) "Verified / Active" else "Vérifié / Actif"
                        } else {
                            if (isEn) "Unavailable (Fail-Closed)" else "Indisponible (Bloqué)"
                        }
                    )
                    ProfileRow(if (isEn) "Biometric Auth" else "Auth Biométrique", if (uiState.deviceProfile.biometricEnabled) (if (isEn) "Active" else "Actif") else (if (isEn) "Disabled" else "Désactivé"))
                    ProfileRow(if (isEn) "Architecture" else "Architecture", "InkingiGuard Phase 1 Native Mesh")

                    if (!isGuestMode) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                currentPinInput = ""
                                newPinInput = ""
                                confirmPinInput = ""
                                pinSetupError = null
                                showPinSetupDialog = true
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("enroll_or_change_pin_button")
                        ) {
                            Icon(Icons.Default.Lock, contentDescription = null, tint = InkingiNavyDark, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (uiState.isPinConfigured) {
                                    if (isEn) "Change Master PIN" else "Modifier le Code PIN Maître"
                                } else {
                                    if (isEn) "Set Master PIN" else "Définir le Code PIN Maître"
                                },
                                color = InkingiNavyDark,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Deterministic Risk Simulator Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, InkingiRiskMedium.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = InkingiNavyCard)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isEn) "Telemetry Anomaly Diagnostics" else "Diagnostic d'Anomalies Télémétriques",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = InkingiTextPrimary
                    )
                    Text(
                        text = if (isEn)
                            "Trigger real-time anomalous vectors to observe deterministic RiskEngine calculations:"
                        else
                            "Déclenchez des vecteurs d'anomalies en temps réel pour observer les calculs du RiskEngine :",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiTextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onSimulateSimChange,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("diag_sim_change_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = InkingiNavyBorder)
                            ) {
                                Text(stringResource(R.string.settings_diag_sim), fontSize = 11.sp, color = InkingiRiskHigh)
                            }
                            Button(
                                onClick = onSimulateDisplacement,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("diag_displacement_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = InkingiNavyBorder)
                            ) {
                                Text(stringResource(R.string.settings_diag_zone), fontSize = 11.sp, color = InkingiRiskMedium)
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onSimulateKineticImpact,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("diag_kinetic_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = InkingiNavyBorder)
                            ) {
                                Text(stringResource(R.string.settings_diag_kinetic), fontSize = 11.sp, color = InkingiCyan)
                            }
                            Button(
                                onClick = onSimulateReboot,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("diag_reboot_button"),
                                colors = ButtonDefaults.buttonColors(containerColor = InkingiNavyBorder)
                            ) {
                                Text(stringResource(R.string.settings_diag_reboot), fontSize = 11.sp, color = InkingiRiskMedium)
                            }
                        }

                        Button(
                            onClick = onSimulateNetworkDrop,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("diag_network_drop_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = InkingiNavyBorder)
                        ) {
                            Text(stringResource(R.string.settings_diag_network), fontSize = 11.sp, color = InkingiRiskHigh)
                        }

                        OutlinedButton(
                            onClick = onResetSimulations,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("diag_reset_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = InkingiCyan)
                        ) {
                            Text(stringResource(R.string.settings_diag_reset))
                        }
                    }
                }
            }
        }

        // Ethics and Privacy Transparency Card
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
                        Icon(Icons.Default.Security, contentDescription = null, tint = InkingiCyan, modifier = Modifier.size(20.dp))
                        Text(
                            text = if (isEn) "Ethics & Privacy Transparency" else "Éthique et Transparence de Confidentialité",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = InkingiTextPrimary
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = if (isEn)
                            "• ZERO COVERT SENSORS: InkingiGuard never covertly records audio, photographs individuals, or intercepts personal messages.\n" +
                            "• LOCAL EVALUATION: Sensor normalization, motion acceleration vectoring, and deterministic scoring execute on-device.\n" +
                            "• ADVISORY AI: Deterministic rules prioritize authoritative control. Artificial Intelligence provides contextual explanations only."
                        else
                            "• ZÉRO CAPTEUR SECRET : InkingiGuard n'enregistre jamais d'audio, ne prend pas de photos et n'intercepte aucun message privé.\n" +
                            "• ÉVALUATION LOCALE : La normalisation des capteurs et le calcul de risque s'exécutent entièrement sur l'appareil.\n" +
                            "• IA CONSULTATIVE : Les règles déterministes contrôlent la sécurité. L'intelligence artificielle fournit des explications contextuelles.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiTextSecondary,
                        lineHeight = 18.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = stringResource(R.string.app_version),
                        style = MaterialTheme.typography.labelSmall,
                        color = InkingiTextMuted
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // PIN Dialog for Switching Back to Owner
    if (showPinDialogForRoleSwitch != null) {
        AlertDialog(
            onDismissRequest = { showPinDialogForRoleSwitch = null },
            containerColor = InkingiNavyCardElevated,
            title = {
                Text(
                    text = if (isEn) "Owner Authentication" else "Authentification Propriétaire",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = InkingiCyan
                )
            },
            text = {
                Column {
                    Text(
                        text = if (isEn)
                            "Enter master PIN to exit guest mode and restore Owner profile:"
                        else
                            "Entrez le code PIN maître pour quitter le mode invité et restaurer le profil Propriétaire :",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = enteredRolePin,
                        onValueChange = { enteredRolePin = it },
                        label = { Text(if (isEn) "Master PIN" else "Code PIN Maître") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("role_pin_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = InkingiCyan,
                            focusedLabelColor = InkingiCyan,
                            unfocusedBorderColor = InkingiNavyBorder
                        )
                    )
                    if (rolePinError != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = rolePinError!!, style = MaterialTheme.typography.labelSmall, color = InkingiRiskHigh)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val success = onSwitchUserRole(UserRole.OWNER, enteredRolePin)
                        if (success) {
                            showPinDialogForRoleSwitch = null
                        } else {
                            rolePinError = if (isEn) "Invalid PIN. Owner access denied." else "Code PIN invalide. Accès propriétaire refusé."
                        }
                    },
                    modifier = Modifier.testTag("confirm_role_switch_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                ) {
                    Text(if (isEn) "Confirm" else "Confirmer", color = InkingiNavyDark, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinDialogForRoleSwitch = null }) {
                    Text(if (isEn) "Cancel" else "Annuler", color = InkingiTextMuted)
                }
            }
        )
    }

    // Dialog for Adding a Trusted Person
    if (showAddPersonDialog) {
        AlertDialog(
            onDismissRequest = { showAddPersonDialog = false },
            containerColor = InkingiNavyCardElevated,
            title = {
                Text(
                    text = if (isEn) "Add Trusted Contact" else "Ajouter un contact de confiance",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = InkingiCyan
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newPersonName,
                        onValueChange = { newPersonName = it },
                        label = { Text(if (isEn) "Full Name" else "Nom complet") },
                        modifier = Modifier.fillMaxWidth().testTag("person_name_input")
                    )
                    OutlinedTextField(
                        value = newPersonPhone,
                        onValueChange = { newPersonPhone = it },
                        label = { Text(if (isEn) "Phone (e.g. +257 ...)" else "Téléphone (ex: +257 ...)") },
                        modifier = Modifier.fillMaxWidth().testTag("person_phone_input")
                    )
                    OutlinedTextField(
                        value = newPersonRelation,
                        onValueChange = { newPersonRelation = it },
                        label = { Text(if (isEn) "Relationship (Family, Spouse, etc.)" else "Lien (Famille, Conjoint, etc.)") },
                        modifier = Modifier.fillMaxWidth().testTag("person_relation_input")
                    )
                    OutlinedTextField(
                        value = newPersonPin,
                        onValueChange = { newPersonPin = it },
                        label = { Text(if (isEn) "Owner Master PIN" else "Code PIN Propriétaire") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("person_owner_pin_input")
                    )
                    if (addPersonError != null) {
                        Text(text = addPersonError!!, style = MaterialTheme.typography.labelSmall, color = InkingiRiskHigh)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPersonName.isBlank() || newPersonPhone.isBlank()) {
                            addPersonError = if (isEn) "Name and phone required." else "Nom et téléphone obligatoires."
                            return@Button
                        }
                        val success = onAddTrustedPerson(newPersonName, newPersonPhone, newPersonRelation, newPersonPin)
                        if (success) {
                            showAddPersonDialog = false
                        } else {
                            addPersonError = if (isEn) "Invalid PIN. Authorization required." else "Code PIN invalide. Autorisation requise."
                        }
                    },
                    modifier = Modifier.testTag("save_trusted_person_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                ) {
                    Text(if (isEn) "Save" else "Enregistrer", color = InkingiNavyDark, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddPersonDialog = false }) {
                    Text(if (isEn) "Cancel" else "Annuler", color = InkingiTextMuted)
                }
            }
        )
    }

    // Dialog for Deleting a Trusted Person
    if (personToDeleteId != null) {
        AlertDialog(
            onDismissRequest = { personToDeleteId = null },
            containerColor = InkingiNavyCardElevated,
            title = {
                Text(
                    text = if (isEn) "Remove Trusted Contact" else "Retirer la personne de confiance",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = InkingiRiskHigh
                )
            },
            text = {
                Column {
                    Text(
                        text = if (isEn)
                            "Enter your master PIN to confirm removing this trusted contact:"
                        else
                            "Entrez votre code PIN maître pour confirmer la suppression de ce contact de confiance :",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiTextSecondary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = deletePersonPin,
                        onValueChange = { deletePersonPin = it },
                        label = { Text(if (isEn) "Master PIN" else "Code PIN Maître") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth().testTag("delete_person_pin_input")
                    )
                    if (deletePersonError != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(text = deletePersonError!!, style = MaterialTheme.typography.labelSmall, color = InkingiRiskHigh)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val success = onRemoveTrustedPerson(personToDeleteId!!, deletePersonPin)
                        if (success) {
                            personToDeleteId = null
                        } else {
                            deletePersonError = if (isEn) "Incorrect PIN code." else "Code PIN incorrect."
                        }
                    },
                    modifier = Modifier.testTag("confirm_delete_person_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiRiskHigh)
                ) {
                    Text(if (isEn) "Delete" else "Supprimer", color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { personToDeleteId = null }) {
                    Text(if (isEn) "Cancel" else "Annuler", color = InkingiTextMuted)
                }
            }
        )
    }

    // Dialog for Master PIN Enrollment or Update
    if (showPinSetupDialog) {
        AlertDialog(
            onDismissRequest = { showPinSetupDialog = false },
            containerColor = InkingiNavyCardElevated,
            title = {
                Text(
                    text = if (uiState.isPinConfigured) {
                        if (isEn) "Update Master PIN" else "Mettre à jour le PIN Maître"
                    } else {
                        if (isEn) "Enroll Master PIN" else "Enregistrer le PIN Maître"
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = InkingiCyan
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isEn)
                            "Choose a non-sequential 4–8 digit PIN. Stored using PBKDF2-HMAC-SHA256 inside Android Keystore."
                        else
                            "Choisissez un code PIN non séquentiel de 4 à 8 chiffres, chiffré via PBKDF2-HMAC-SHA256 et Android Keystore.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiTextSecondary
                    )
                    if (uiState.isPinConfigured) {
                        OutlinedTextField(
                            value = currentPinInput,
                            onValueChange = {
                                if (it.length <= 8 && it.all { ch -> ch.isDigit() }) {
                                    currentPinInput = it
                                    pinSetupError = null
                                }
                            },
                            label = { Text(if (isEn) "Current Master PIN" else "Code PIN actuel") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("current_pin_setup_input")
                        )
                    }
                    OutlinedTextField(
                        value = newPinInput,
                        onValueChange = {
                            if (it.length <= 8 && it.all { ch -> ch.isDigit() }) {
                                newPinInput = it
                                pinSetupError = null
                            }
                        },
                        label = { Text(if (isEn) "New Master PIN (4-8 digits)" else "Nouveau PIN Maître (4-8 chiffres)") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("new_pin_setup_input")
                    )
                    OutlinedTextField(
                        value = confirmPinInput,
                        onValueChange = {
                            if (it.length <= 8 && it.all { ch -> ch.isDigit() }) {
                                confirmPinInput = it
                                pinSetupError = null
                            }
                        },
                        label = { Text(if (isEn) "Confirm Master PIN" else "Confirmer le PIN Maître") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("confirm_pin_setup_input")
                    )
                    val displayError = pinSetupError ?: uiState.pinActionError
                    if (displayError != null) {
                        Text(
                            text = displayError,
                            style = MaterialTheme.typography.labelSmall,
                            color = InkingiRiskHigh,
                            modifier = Modifier.testTag("pin_setup_error_text")
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newPinInput != confirmPinInput) {
                            pinSetupError = if (isEn) "PINs do not match." else "Les codes PIN ne correspondent pas."
                            return@Button
                        }
                        val ok = if (uiState.isPinConfigured) {
                            onChangeMasterPin(currentPinInput, newPinInput)
                        } else {
                            onEnrollMasterPin(newPinInput)
                        }
                        if (ok) {
                            currentPinInput = ""
                            newPinInput = ""
                            confirmPinInput = ""
                            pinSetupError = null
                            showPinSetupDialog = false
                        } else {
                            pinSetupError = uiState.pinActionError
                                ?: if (isEn) "Could not save Master PIN." else "Impossible d'enregistrer le PIN maître."
                        }
                    },
                    modifier = Modifier.testTag("save_pin_setup_button"),
                    colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan)
                ) {
                    Text(if (isEn) "Save PIN" else "Enregistrer", color = InkingiNavyDark, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPinSetupDialog = false }) {
                    Text(if (isEn) "Cancel" else "Annuler", color = InkingiTextMuted)
                }
            }
        )
    }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = InkingiTextMuted)
        Text(text = value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = InkingiTextPrimary)
    }
}
