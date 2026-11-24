package com.example.presentation.zones

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FindInPage
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.ai.AntiArnaqueEngine
import com.example.presentation.InkingiUiState
import com.example.ui.theme.InkingiCyan
import com.example.ui.theme.InkingiNavyBorder
import com.example.ui.theme.InkingiNavyCard
import com.example.ui.theme.InkingiNavyCardElevated
import com.example.ui.theme.InkingiNavyDark
import com.example.ui.theme.InkingiRiskHigh
import com.example.ui.theme.InkingiRiskLow
import com.example.ui.theme.InkingiTextPrimary
import com.example.ui.theme.InkingiTextSecondary

/**
 * TAB: ANTI-SCAM / ANTI-ARNAQUE
 * Dedicated General Anti-Scam Shield:
 * Card 1: Phishing & Malicious Link Scanner (SMS & Links analyzer with adaptive learning).
 * Card 2: Active Anti-Scam Shield & Adaptive Threat Memory (real-time indicators, blocked scams metric).
 */
@Composable
fun ZonesScreen(
    uiState: InkingiUiState
) {
    val isEn = uiState.currentLanguage.equals("en", ignoreCase = true)
    var inputText by remember { mutableStateOf("") }
    var analysisResult by remember { mutableStateOf<AntiArnaqueEngine.AnalysisResult?>(null) }

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
                text = if (isEn) "ANTI-SCAM SHIELD" else "BOUCLIER ANTI-ARNAQUE",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp),
                color = InkingiCyan
            )
            Text(
                text = if (isEn)
                    "Social engineering defense, SMS phishing detection, and malicious link inspection."
                else
                    "Détection d'ingénierie sociale, hameçonnage SMS et analyse de liens malveillants.",
                style = MaterialTheme.typography.bodySmall,
                color = InkingiTextSecondary
            )
        }

        // ==========================================================
        // CARD 1: Scanner Arnaque / Scam Scanner
        // ==========================================================
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, InkingiNavyBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = InkingiNavyCardElevated)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.FindInPage, contentDescription = null, tint = InkingiCyan)
                        Text(
                            text = if (isEn) "Phishing & Link Scanner" else "Scanner Arnaque (SMS & Liens)",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = InkingiTextPrimary
                        )
                    }

                    Text(
                        text = if (isEn)
                            "Inspects suspicious SMS texts, fake delivery notices, fraudulent lottery gains, urgent credential requests, and malicious web links."
                        else
                            "Analyse les SMS suspects, faux avis de livraison, fausses récompenses, demandes d'identifiants urgentes et liens web frauduleux.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiTextSecondary
                    )

                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(if (isEn) "Paste suspicious message or web link..." else "Collez le message ou le lien suspect...")
                        },
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("scam_analyzer_input"),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = InkingiTextPrimary,
                            unfocusedTextColor = InkingiTextPrimary,
                            focusedBorderColor = InkingiCyan,
                            unfocusedBorderColor = InkingiNavyBorder
                        )
                    )

                    Button(
                        onClick = {
                            val res = AntiArnaqueEngine.analyzeTextOrUrl(inputText, lang = uiState.currentLanguage)
                            analysisResult = res
                            if (res.isSuspicious) {
                                AntiArnaqueEngine.recordConfirmedScam(inputText)
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("analyze_scam_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = InkingiCyan),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Shield, contentDescription = null, tint = InkingiNavyDark)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isEn) "Analyze Message" else "Analyser le Message",
                            color = InkingiNavyDark,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (analysisResult != null) {
                        val res = analysisResult!!
                        val resultColor = if (res.isSuspicious) InkingiRiskHigh else InkingiRiskLow
                        Card(
                            colors = CardDefaults.cardColors(containerColor = InkingiNavyDark),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, resultColor.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = res.verdict,
                                    fontWeight = FontWeight.Black,
                                    color = resultColor,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(text = res.advice, color = InkingiTextPrimary, style = MaterialTheme.typography.bodySmall)
                                res.detectedIndicators.forEach { ind ->
                                    Text(text = "• $ind", color = InkingiTextSecondary, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ==========================================================
        // CARD 2: Active Anti-Scam Shield & Adaptive Threat Memory
        // ==========================================================
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, InkingiNavyBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = InkingiNavyCard)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = InkingiCyan)
                            Text(
                                text = if (isEn) "Active Defense Shield" else "Bouclier de Défense Actif",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = InkingiTextPrimary
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = InkingiRiskLow, modifier = Modifier.size(16.dp))
                            Text(
                                text = if (isEn) "PROTECTED" else "PROTÉGÉ",
                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                color = InkingiRiskLow
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(InkingiNavyDark)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = if (isEn)
                                "Real-time algorithmic defense against deceptive social engineering, phishing campaigns, credential theft, and fraudulent link redirection."
                            else
                                "Défense algorithmique en temps réel contre l'ingénierie sociale, les campagnes d'hameçonnage, le vol d'identifiants et les liens frauduleux.",
                            style = MaterialTheme.typography.bodySmall,
                            color = InkingiTextSecondary
                        )
                    }

                    // Metrics
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (isEn) "Scams neutralized: ${uiState.totalBlockedScams}" else "Arnaques neutralisées : ${uiState.totalBlockedScams}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = InkingiRiskLow
                        )
                        Text(
                            text = if (isEn) "Signatures learned: ${uiState.totalLearnedScamPatterns.coerceAtLeast(3)}" else "Signatures apprises : ${uiState.totalLearnedScamPatterns.coerceAtLeast(3)}",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = InkingiCyan
                        )
                    }
                }
            }
        }

        // TASK 9: Compliance & CallScreeningService Integration Policy
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, InkingiNavyBorder, RoundedCornerShape(16.dp)),
                colors = CardDefaults.cardColors(containerColor = InkingiNavyCard)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isEn) "Telephony & SMS Security Architecture" else "Architecture Sécurité Téléphonie & SMS",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = InkingiTextPrimary
                    )

                    Text(
                        text = if (isEn)
                            "• Google Play Compliant: InkingiGuard never secretly reads private SMS/messages, intercepts calls, or records phone audio.\n• On-Device Manual Analysis: Only the text or links you explicitly paste into the scanner above are analyzed locally.\n• Privacy Preservation: All heuristic scoring runs locally without uploading your personal messages."
                        else
                            "• Conforme Google Play : InkingiGuard ne lit jamais vos SMS privés, n'intercepte aucun appel et n'enregistre aucun son.\n• Analyse Manuelle Locale : Seuls les messages ou liens que vous collez volontairement ci-dessus sont inspectés.\n• Respect de la Vie Privée : Toute l'analyse heuristique s'exécute localement sans envoyer vos messages.",
                        style = MaterialTheme.typography.bodySmall,
                        color = InkingiTextSecondary,
                        lineHeight = 16.sp
                    )
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
