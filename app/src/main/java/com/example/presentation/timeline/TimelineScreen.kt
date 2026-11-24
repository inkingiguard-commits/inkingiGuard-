package com.example.presentation.timeline

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.EventSeverity
import com.example.presentation.InkingiUiState
import com.example.ui.theme.InkingiCyan
import com.example.ui.theme.InkingiNavyBorder
import com.example.ui.theme.InkingiNavyCard
import com.example.ui.theme.InkingiNavyDark
import com.example.ui.theme.InkingiRiskHigh
import com.example.ui.theme.InkingiRiskLow
import com.example.ui.theme.InkingiTextMuted
import com.example.ui.theme.InkingiTextPrimary
import com.example.ui.theme.InkingiTextSecondary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SimpleTimelineItem(
    val time: String,
    val iconEmoji: String,
    val title: String,
    val subtitle: String,
    val isPositive: Boolean
)

/**
 * TAB 3: HISTORIQUE
 * Displays ONLY genuine events from Room database:
 * - "Dans baseline" / "Hors baseline"
 * - "Arnaque détectée ou bloquée"
 * - "Verrouillage réussi ou échoué"
 * - "Dernière localisation synchronisée"
 * - "Permission désactivée ou service indisponible"
 *
 * Each event has a real timestamp and meaningful details. Includes empty state when no events exist.
 */
@Composable
fun TimelineScreen(
    uiState: InkingiUiState,
    onClearLedger: () -> Unit
) {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    val isEn = uiState.currentLanguage.equals("en", ignoreCase = true)

    // Map genuine recorded SecurityEvents from Room into the clean 2-pillar timeline
    val genuineItems = uiState.recentEvents.mapNotNull { event ->
        val formattedTime = try {
            timeFormat.format(Date(event.timestamp))
        } catch (_: Exception) {
            "--:--"
        }

        when {
            event.title.contains("Dans baseline", ignoreCase = true) || event.title.contains("Within baseline", ignoreCase = true) -> {
                SimpleTimelineItem(
                    time = formattedTime,
                    iconEmoji = "✅",
                    title = if (isEn) "Within baseline" else "Dans baseline",
                    subtitle = event.description,
                    isPositive = true
                )
            }
            event.title.contains("Hors baseline", ignoreCase = true) || event.title.contains("Outside baseline", ignoreCase = true) -> {
                SimpleTimelineItem(
                    time = formattedTime,
                    iconEmoji = "⚠️",
                    title = if (isEn) "Outside baseline" else "Hors baseline",
                    subtitle = event.description,
                    isPositive = false
                )
            }
            event.title.contains("Arnaque", ignoreCase = true) || event.title.contains("Scam", ignoreCase = true) || event.title.contains("Phishing", ignoreCase = true) -> {
                SimpleTimelineItem(
                    time = formattedTime,
                    iconEmoji = "🛡️",
                    title = event.title,
                    subtitle = event.description,
                    isPositive = true
                )
            }
            event.title.contains("Verrouillage", ignoreCase = true) || event.title.contains("Lock", ignoreCase = true) -> {
                val isSuccess = event.severity != EventSeverity.WARNING && event.severity != EventSeverity.CRITICAL
                SimpleTimelineItem(
                    time = formattedTime,
                    iconEmoji = if (isSuccess) "🔒" else "❌",
                    title = event.title,
                    subtitle = event.description,
                    isPositive = isSuccess
                )
            }
            event.title.contains("Localisation", ignoreCase = true) || event.title.contains("Location", ignoreCase = true) || event.title.contains("GPS", ignoreCase = true) -> {
                SimpleTimelineItem(
                    time = formattedTime,
                    iconEmoji = "📍",
                    title = event.title,
                    subtitle = event.description,
                    isPositive = true
                )
            }
            event.severity == EventSeverity.CRITICAL -> {
                SimpleTimelineItem(
                    time = formattedTime,
                    iconEmoji = "🚨",
                    title = event.title,
                    subtitle = event.description,
                    isPositive = false
                )
            }
            else -> {
                // Relevant defense actions
                SimpleTimelineItem(
                    time = formattedTime,
                    iconEmoji = "🛡️",
                    title = event.title,
                    subtitle = event.description,
                    isPositive = event.severity == EventSeverity.INFO
                )
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(InkingiNavyDark)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = if (isEn) "SECURITY TIMELINE" else "HISTORIQUE DU SYSTÈME",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp),
                        color = InkingiCyan
                    )
                    Text(
                        text = if (isEn) "Authentic security events logged in real time" else "Événements authentiques enregistrés en temps réel",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiTextSecondary
                    )
                }

                if (genuineItems.isNotEmpty()) {
                    IconButton(
                        onClick = onClearLedger,
                        modifier = Modifier.testTag("clear_timeline_button")
                    ) {
                        Icon(Icons.Default.DeleteSweep, contentDescription = if (isEn) "Clear" else "Effacer", tint = InkingiTextMuted)
                    }
                }
            }
        }

        if (genuineItems.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "🛡️",
                            fontSize = 36.sp
                        )
                        Text(
                            text = if (isEn) "No security events recorded" else "Aucun événement enregistré",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = InkingiTextPrimary
                        )
                        Text(
                            text = if (isEn)
                                "Learning observations, scam detections, and security alerts will appear here with real timestamps."
                            else
                                "Les observations d'apprentissage, blocages d'arnaques et alertes apparaîtront ici avec leur horodatage réel.",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkingiTextSecondary,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            }
        } else {
            items(genuineItems) { item ->
                val borderColor = if (item.isPositive) InkingiRiskLow.copy(alpha = 0.4f) else InkingiRiskHigh.copy(alpha = 0.5f)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = InkingiNavyCard)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = item.iconEmoji,
                            fontSize = 24.sp
                        )

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = InkingiTextPrimary
                                )
                                Text(
                                    text = item.time,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = InkingiTextMuted
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = item.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = InkingiTextSecondary
                            )
                        }
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
