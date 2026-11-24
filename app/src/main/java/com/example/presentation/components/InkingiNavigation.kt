package com.example.presentation.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CrisisAlert
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.model.SecurityState
import com.example.ui.theme.InkingiCyan
import com.example.ui.theme.InkingiNavyCard
import com.example.ui.theme.InkingiRiskHigh
import com.example.ui.theme.InkingiTextMuted
import com.example.ui.theme.InkingiTextPrimary

/**
 * Six Tabs for InkingiGuard AI:
 * 1. SHIELD (Bouclier): Apprentissage, Anti-vol action & risk scoring
 * 2. SENSORS (Capteurs): Sensor mesh, telemetry & health
 * 3. TIMELINE (Historique): Authentic event history
 * 4. ZONES (Anti-Arnaque): General Anti-Scam heuristics, phishing & link protection
 * 5. RECOVERY (Récupération): Incident response console, theft confirmation, GPS tracking
 * 6. SETTINGS (Paramètres): Hardware lock capabilities & preferences
 */
enum class InkingiNavigationItem(
    val titleRes: Int,
    val icon: ImageVector,
    val testTag: String
) {
    DASHBOARD(R.string.nav_dashboard, Icons.Default.Shield, "nav_dashboard_tab"),
    SENSORS(R.string.nav_sensors, Icons.Default.Sensors, "nav_sensors_tab"),
    TIMELINE(R.string.nav_timeline, Icons.Default.History, "nav_timeline_tab"),
    ZONES(R.string.nav_zones, Icons.Default.LocationOn, "nav_zones_tab"),
    THEFT_RECOVERY(R.string.nav_theft_recovery, Icons.Default.CrisisAlert, "nav_recovery_tab"),
    SETTINGS(R.string.nav_settings, Icons.Default.Settings, "nav_settings_tab")
}

@Composable
fun InkingiBottomBar(
    currentScreen: InkingiNavigationItem,
    securityState: SecurityState,
    onNavigate: (InkingiNavigationItem) -> Unit
) {
    NavigationBar(
        containerColor = InkingiNavyCard,
        contentColor = InkingiTextPrimary
    ) {
        InkingiNavigationItem.entries.forEach { item ->
            val isSelected = currentScreen == item
            val isTheftAlert = (item == InkingiNavigationItem.DASHBOARD || item == InkingiNavigationItem.THEFT_RECOVERY) &&
                    (securityState == SecurityState.STOLEN_MODE ||
                     securityState == SecurityState.OWNER_CONFIRMED_STOLEN ||
                     securityState == SecurityState.SUSPECTED_LOST ||
                     securityState == SecurityState.DANGER)

            NavigationBarItem(
                selected = isSelected,
                onClick = { onNavigate(item) },
                icon = {
                    if (isTheftAlert) {
                        BadgedBox(badge = { Badge(containerColor = InkingiRiskHigh) { Text("!") } }) {
                            Icon(item.icon, contentDescription = stringResource(item.titleRes))
                        }
                    } else {
                        Icon(item.icon, contentDescription = stringResource(item.titleRes))
                    }
                },
                label = {
                    Text(
                        text = stringResource(item.titleRes),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 9.sp,
                        maxLines = 1
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = InkingiCyan,
                    selectedTextColor = InkingiCyan,
                    unselectedIconColor = InkingiTextMuted,
                    unselectedTextColor = InkingiTextMuted,
                    indicatorColor = InkingiNavyCard.copy(alpha = 0.8f)
                ),
                modifier = Modifier.testTag(item.testTag)
            )
        }
    }
}
