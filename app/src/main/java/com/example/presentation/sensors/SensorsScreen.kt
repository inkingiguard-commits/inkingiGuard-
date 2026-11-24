package com.example.presentation.sensors

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotInterested
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.model.ConfidenceLevel
import com.example.core.model.MovementType
import com.example.domain.sensor.SensorIntelligenceEngine
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
fun SensorsScreen(
    uiState: InkingiUiState,
    onSimulateKineticImpact: () -> Unit,
    onResetKinetic: () -> Unit
) {
    val isEn = uiState.currentLanguage.equals("en", ignoreCase = true)
    val mesh = uiState.sensorMesh
    val motion = mesh.lastMotionEvent

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(InkingiNavyDark)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Spacer(modifier = Modifier.height(4.dp))
            Column {
                Text(
                    text = stringResource(R.string.sensors_title),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = InkingiTextPrimary
                )
                Text(
                    text = stringResource(R.string.sensors_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkingiTextSecondary
                )
            }
        }

        // Kinetic Motion Engine Analysis Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        1.dp,
                        if (motion?.movementType == MovementType.SUDDEN_ACCELERATION) InkingiRiskHigh else InkingiCyan.copy(alpha = 0.5f),
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Speed,
                                contentDescription = null,
                                tint = InkingiCyan,
                                modifier = Modifier.size(22.dp)
                            )
                            Text(
                                text = if (isEn) "Motion Analysis Engine" else "Moteur d'Analyse de Mouvement",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = InkingiTextPrimary
                            )
                        }

                        val motionColor = when (motion?.movementType) {
                            MovementType.SUDDEN_ACCELERATION, MovementType.UNUSUAL_ROTATION -> InkingiRiskHigh
                            MovementType.NORMAL_HANDHELD -> InkingiRiskMedium
                            else -> InkingiRiskLow
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(motionColor.copy(alpha = 0.2f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = motion?.movementType?.name?.replace("_", " ") ?: (if (isEn) "STATIONARY" else "IMMOBILE"),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                color = motionColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(text = stringResource(R.string.sensor_accel_mag), style = MaterialTheme.typography.labelSmall, color = InkingiTextMuted)
                            Text(
                                text = "%.2f m/s²".format(mesh.currentAccelerationMagnitude),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (mesh.currentAccelerationMagnitude > 18f) InkingiRiskHigh else InkingiTextPrimary
                            )
                        }
                        Column {
                            Text(text = stringResource(R.string.sensor_angular_vel), style = MaterialTheme.typography.labelSmall, color = InkingiTextMuted)
                            Text(
                                text = "%.2f rad/s".format(mesh.currentRotationRate),
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = if (mesh.currentRotationRate > 4f) InkingiRiskMedium else InkingiTextPrimary
                            )
                        }
                        Column {
                            Text(text = stringResource(R.string.sensor_confidence), style = MaterialTheme.typography.labelSmall, color = InkingiTextMuted)
                            Text(
                                text = motion?.confidence?.name?.replace("_CONFIDENCE", "") ?: "NOMINAL",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = InkingiCyan
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = onSimulateKineticImpact,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("simulate_kinetic_button"),
                            colors = ButtonDefaults.buttonColors(containerColor = InkingiRiskHigh)
                        ) {
                            Icon(Icons.Default.Vibration, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.sensor_simulate_jerk), fontSize = 11.sp)
                        }

                        OutlinedButton(
                            onClick = onResetKinetic,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("reset_kinetic_button"),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = InkingiTextPrimary)
                        ) {
                            Text(stringResource(R.string.sensor_reset_kinetic), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Live Telemetry Readouts
        item {
            Text(
                text = if (isEn) "Environmental & Hardware Telemetry" else "Télémétrie Matérielle & Environnementale",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = InkingiTextPrimary
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                TelemetryStatCard(
                    modifier = Modifier.weight(1f),
                    title = if (isEn) "Ambient Light" else "Lumière Ambiante",
                    value = mesh.currentLightLux?.let { "%.1f lux".format(it) } ?: (if (isEn) "Waiting sensor" else "En attente capteur"),
                    status = if (mesh.currentLightLux != null) (if (isEn) "Active" else "Actif") else (if (isEn) "Unavailable" else "Indisponible")
                )
                TelemetryStatCard(
                    modifier = Modifier.weight(1f),
                    title = if (isEn) "Proximity" else "Proximité",
                    value = mesh.currentProximityCm?.let { "%.1f cm".format(it) } ?: (if (isEn) "Waiting sensor" else "En attente capteur"),
                    status = if (mesh.currentProximityCm != null) (if (isEn) "Active" else "Actif") else (if (isEn) "Unavailable" else "Indisponible")
                )
            }
        }

        item {
            Text(
                text = if (isEn)
                    "Dynamic Hardware Audit (${mesh.availableSensors.count { it.isAvailable }}/${mesh.availableSensors.size})"
                else
                    "Audit Matériel Dynamique (${mesh.availableSensors.count { it.isAvailable }}/${mesh.availableSensors.size})",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = InkingiTextPrimary
            )
        }

        items(mesh.availableSensors) { sensor ->
            SensorAuditItem(sensor, isEn)
        }

        item {
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TelemetryStatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    status: String
) {
    Card(
        modifier = modifier.border(1.dp, InkingiNavyBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = InkingiNavyCard)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelSmall, color = InkingiTextMuted)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), color = InkingiCyan)
            Text(text = status, style = MaterialTheme.typography.labelSmall, color = InkingiTextSecondary, fontSize = 10.sp)
        }
    }
}

@Composable
private fun SensorAuditItem(sensor: SensorIntelligenceEngine.SensorHardwareStatus, isEn: Boolean) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, InkingiNavyBorder, RoundedCornerShape(12.dp)),
        colors = CardDefaults.cardColors(containerColor = InkingiNavyCard)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (sensor.isAvailable) InkingiRiskLow.copy(alpha = 0.15f) else InkingiNavyBorder),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (sensor.isAvailable) Icons.Default.CheckCircle else Icons.Default.NotInterested,
                    contentDescription = null,
                    tint = if (sensor.isAvailable) InkingiRiskLow else InkingiTextMuted,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = sensor.displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = InkingiTextPrimary
                )
                Text(
                    text = if (sensor.isAvailable) {
                        "Vendor: ${sensor.vendor ?: "Android OEM"} • ${sensor.powerMa} mA"
                    } else {
                        stringResource(R.string.sensor_unavailable)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = InkingiTextSecondary
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sensor.isAvailable) InkingiRiskLow.copy(alpha = 0.2f) else InkingiNavyBorder.copy(alpha = 0.4f))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = if (sensor.isAvailable) (if (isEn) "DETECTED" else "DÉTECTÉ") else (if (isEn) "ABSENT" else "ABSENT"),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = if (sensor.isAvailable) InkingiRiskLow else InkingiTextMuted,
                    fontSize = 9.sp
                )
            }
        }
    }
}
