package com.example.presentation.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.SecurityState
import com.example.ui.theme.InkingiCyan
import com.example.ui.theme.InkingiNavyCard
import com.example.ui.theme.InkingiNavyDark
import com.example.ui.theme.InkingiRiskHigh
import com.example.ui.theme.InkingiRiskLow
import com.example.ui.theme.InkingiRiskMedium
import com.example.ui.theme.InkingiTextMuted
import com.example.ui.theme.InkingiTextPrimary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InkingiTopBar(
    securityState: SecurityState,
    deviceId: String,
    onOpenDiagnostics: () -> Unit
) {
    val stateColor by animateColorAsState(
        targetValue = when (securityState) {
            SecurityState.NORMAL -> InkingiRiskLow
            SecurityState.WARNING -> InkingiRiskMedium
            SecurityState.DANGER, SecurityState.SUSPECTED_LOST -> InkingiRiskHigh
            SecurityState.OWNER_CONFIRMED_STOLEN, SecurityState.STOLEN_MODE -> InkingiRiskHigh
            SecurityState.RECOVERY_MODE -> InkingiRiskMedium
            SecurityState.RECOVERED -> InkingiCyan
        },
        label = "state_color"
    )

    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = InkingiNavyDark,
            titleContentColor = InkingiTextPrimary
        ),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(InkingiNavyCard)
                        .border(1.dp, InkingiCyan.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Inkingi Shield",
                        tint = InkingiCyan,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = "INKINGIGUARD AI",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        ),
                        color = InkingiTextPrimary
                    )
                    Text(
                        text = "Node: ${deviceId.ifBlank { "IG-D98E1455" }}",
                        style = MaterialTheme.typography.labelSmall,
                        color = InkingiTextMuted
                    )
                }
            }
        },
        actions = {
            // Live State Pill (NORMAL, etc.)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(stateColor.copy(alpha = 0.15f))
                    .border(1.dp, stateColor.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(stateColor)
                    )
                    val stateRes = when (securityState) {
                        SecurityState.NORMAL -> R.string.state_normal
                        SecurityState.WARNING -> R.string.state_warning
                        SecurityState.DANGER -> R.string.state_danger
                        SecurityState.SUSPECTED_LOST -> R.string.state_suspected_lost
                        SecurityState.OWNER_CONFIRMED_STOLEN -> R.string.state_owner_confirmed_stolen
                        SecurityState.STOLEN_MODE -> R.string.state_stolen_mode
                        SecurityState.RECOVERY_MODE -> R.string.state_recovery_mode
                        SecurityState.RECOVERED -> R.string.state_recovered
                    }
                    Text(
                        text = stringResource(stateRes),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = stateColor,
                        fontSize = 10.sp
                    )
                }
            }
        }
    )
}
