package com.example.presentation.admin

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * Super Admin Console for InkingiGuard AI.
 * Gated exclusively to users authorized via Firestore "appconfig/admins" -> "adminEmails"
 * with verified email status.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SuperAdminConsoleScreen(
    uiState: InkingiUiState,
    onBack: () -> Unit,
    onRefreshAdminStatus: () -> Unit
) {
    val isEn = uiState.currentLanguage.equals("en", ignoreCase = true)
    var showUserManagementPlaceholder by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(InkingiNavyCardElevated)
                                .border(1.dp, InkingiRiskMedium, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.AdminPanelSettings,
                                contentDescription = null,
                                tint = InkingiRiskMedium,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = if (isEn) "SUPER ADMIN CONSOLE" else "CONSOLE SUPER ADMIN",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                    color = InkingiTextPrimary
                                )
                            )
                            Text(
                                text = "InkingiGuard AI • Authorization Level 0",
                                style = MaterialTheme.typography.labelSmall.copy(color = InkingiRiskMedium)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("admin_console_back_button")) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = InkingiCyan
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onRefreshAdminStatus,
                        enabled = !uiState.isCheckingSuperAdmin,
                        modifier = Modifier.testTag("admin_console_refresh_button")
                    ) {
                        if (uiState.isCheckingSuperAdmin) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = InkingiCyan,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = InkingiCyan)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = InkingiNavyDark)
            )
        },
        containerColor = InkingiNavyDark
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Authorization Banner
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = InkingiNavyCard),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, InkingiRiskMedium.copy(alpha = 0.5f))
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(InkingiRiskMedium)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isEn) "APPLICATION ADMINISTRATOR STATUS: VERIFIED" else "STATUT ADMINISTRATEUR : VÉRIFIÉ",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = InkingiRiskMedium,
                                    letterSpacing = 1.sp
                                )
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        Text(
                            text = if (isEn)
                                "You are authenticated with the official application Super Admin account. Firestore 'appconfig/admins' verified."
                            else
                                "Vous êtes authentifié avec le compte Super Admin officiel de l'application. Vérification 'appconfig/admins' confirmée.",
                            style = MaterialTheme.typography.bodySmall.copy(color = InkingiTextSecondary)
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = InkingiNavyCardElevated,
                            border = androidx.compose.foundation.BorderStroke(1.dp, InkingiNavyBorder)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = if (isEn) "Admin Account" else "Compte Admin",
                                        style = MaterialTheme.typography.labelSmall.copy(color = InkingiTextMuted)
                                    )
                                    Text(
                                        text = uiState.authenticatedUserEmail
                                            ?: uiState.deviceProfile.ownerEmail.ifBlank {
                                                if (isEn) "Authenticated session required" else "Session authentifiée requise"
                                            },
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            color = InkingiTextPrimary,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(InkingiRiskLow.copy(alpha = 0.15f))
                                        .border(1.dp, InkingiRiskLow.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "SUPER_ADMIN",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = InkingiRiskLow,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 2. Metrics & System Connectivity
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Registered Users metric
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = InkingiNavyCard),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, InkingiNavyBorder)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Group,
                                    contentDescription = null,
                                    tint = InkingiCyan,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isEn) "Users" else "Utilisateurs",
                                    style = MaterialTheme.typography.labelSmall.copy(color = InkingiTextMuted)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = uiState.superAdminUserCount?.toString() ?: if (isEn) "Unavailable" else "Indisponible",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = InkingiTextPrimary,
                                    fontSize = if (uiState.superAdminUserCount == null) 16.sp else 22.sp
                                )
                            )
                            Text(
                                text = if (isEn) "Secure stats audit" else "Statistique sécurisée",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = InkingiTextMuted,
                                    fontSize = 10.sp
                                )
                            )
                        }
                    }

                    // Cloud Security metric
                    Card(
                        modifier = Modifier.weight(1f),
                        colors = CardDefaults.cardColors(containerColor = InkingiNavyCard),
                        shape = RoundedCornerShape(14.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, InkingiNavyBorder)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Security,
                                    contentDescription = null,
                                    tint = InkingiRiskLow,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (isEn) "Security" else "Sécurité",
                                    style = MaterialTheme.typography.labelSmall.copy(color = InkingiTextMuted)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Zero-Trust",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = InkingiRiskLow
                                )
                            )
                            Text(
                                text = "Rules v2 hardened",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = InkingiTextMuted,
                                    fontSize = 10.sp
                                )
                            )
                        }
                    }
                }
            }

            // 3. Governance Architecture Details
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = InkingiNavyCard),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, InkingiNavyBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (isEn) "SECURITY ROLE HIERARCHY" else "HIÉRARCHIE DES RÔLES DE SÉCURITÉ",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = InkingiCyan,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        RoleDetailRow(
                            roleName = "SUPER_ADMIN",
                            scope = if (isEn) "Cloud Application Level" else "Niveau Application Cloud",
                            desc = if (isEn) "Full system oversight, appconfig access, emergency suspension." else "Supervision complète, accès appconfig, suspension d'urgence.",
                            badgeColor = InkingiRiskMedium
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        RoleDetailRow(
                            roleName = "OWNER",
                            scope = if (isEn) "Local Device Level" else "Niveau Matériel Local",
                            desc = if (isEn) "Full control over local device telemetry, master PIN, alarm disarm." else "Contrôle total du terminal local, PIN maître, désarmement.",
                            badgeColor = InkingiCyan
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        RoleDetailRow(
                            roleName = "TRUSTED_PERSON",
                            scope = if (isEn) "Local Device Level" else "Niveau Matériel Local",
                            desc = if (isEn) "Authorized delegate with non-destructive access." else "Personne de confiance autorisée, accès non destructif.",
                            badgeColor = InkingiRiskLow
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        RoleDetailRow(
                            roleName = "GUEST",
                            scope = if (isEn) "Local Device Level" else "Niveau Matériel Local",
                            desc = if (isEn) "Restricted profile without sensitive command privileges." else "Profil restreint sans privilèges sur les commandes sensibles.",
                            badgeColor = InkingiTextMuted
                        )
                    }
                }
            }

            // 4. Safe User Management Entry Point
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = InkingiNavyCard),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, InkingiNavyBorder)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = if (isEn) "USER MANAGEMENT & AUDIT" else "GESTION DES UTILISATEURS & AUDIT",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = InkingiCyan,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isEn)
                                "Access authorized user ledger and review security anomalies across enrolled nodes."
                            else
                                "Accédez au registre des utilisateurs autorisés et analysez les anomalies de sécurité sur les nœuds.",
                            style = MaterialTheme.typography.bodySmall.copy(color = InkingiTextSecondary)
                        )
                        Spacer(modifier = Modifier.height(14.dp))

                        Button(
                            onClick = { showUserManagementPlaceholder = !showUserManagementPlaceholder },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = InkingiCyan,
                                contentColor = InkingiNavyDark
                            ),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("admin_user_management_button")
                        ) {
                            Icon(Icons.Default.Group, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (isEn) "Access User Management" else "Consulter le Registre Utilisateurs",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        AnimatedVisibility(visible = showUserManagementPlaceholder) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp),
                                color = InkingiNavyCardElevated,
                                shape = RoundedCornerShape(10.dp),
                                border = androidx.compose.foundation.BorderStroke(1.dp, InkingiNavyBorder)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = if (isEn) "SECURE AUDIT PIPELINE ACTIVE" else "PIPELINE D'AUDIT SÉCURISÉ ACTIF",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = InkingiRiskLow,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (isEn)
                                            "All user data remains strictly owner-scoped under /users/{userId}. Super Admin governance is enforced at Firestore Security Rules boundary."
                                        else
                                            "Toutes les données utilisateurs demeurent strictement isolées sous /users/{userId}. La gouvernance Super Admin est appliquée au niveau des Règles de Sécurité Firestore.",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = InkingiTextMuted,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoleDetailRow(
    roleName: String,
    scope: String,
    desc: String,
    badgeColor: Color
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = InkingiNavyCardElevated,
        border = androidx.compose.foundation.BorderStroke(1.dp, InkingiNavyBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = roleName,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = badgeColor
                    )
                )
                Text(
                    text = scope,
                    style = MaterialTheme.typography.labelSmall.copy(color = InkingiTextMuted, fontSize = 10.sp)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall.copy(color = InkingiTextSecondary, fontSize = 11.sp)
            )
        }
    }
}
