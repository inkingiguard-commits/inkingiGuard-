package com.example.domain.incident

import com.example.core.model.ConfidenceLevel
import com.example.core.model.Incident
import com.example.core.model.IncidentSeverity
import com.example.core.model.IncidentStatus
import com.example.core.model.LocationEvent
import com.example.core.model.RiskAssessment
import com.example.core.model.RiskLevel
import com.example.core.model.SecurityState
import com.example.core.model.SimSecurityInfo
import com.example.core.model.TheftReport
import com.example.core.security.SecurityStateManager
import com.example.data.local.dao.IncidentDao
import com.example.data.local.dao.TheftReportDao
import com.example.data.local.entity.IncidentEntity
import com.example.data.local.entity.TheftReportEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import java.util.UUID

/**
 * InkingiGuard Incident Response Engine.
 * Formulates incidents, coordinates owner verification protocols,
 * and compiles cryptographic Theft Reports.
 */
class IncidentResponseEngine(
    private val deviceId: String,
    private val ownerId: String,
    private val incidentDao: IncidentDao,
    private val theftReportDao: TheftReportDao,
    private val stateManager: SecurityStateManager
) {

    val allIncidents: Flow<List<Incident>> =
        incidentDao.getAllIncidents().map { entities -> entities.map { it.toDomain() } }

    val activeIncident: Flow<Incident?> =
        incidentDao.getActiveIncident().map { it?.toDomain() }

    val latestTheftReport: Flow<TheftReport?> =
        theftReportDao.getLatestTheftReport().map { it?.toDomain() }

    /**
     * Correlates telemetry signals into a tracked Incident.
     */
    suspend fun processIncidentFromAssessment(
        riskAssessment: RiskAssessment,
        confidence: ConfidenceLevel,
        currentIncident: Incident?
    ): Incident? {
        val score = riskAssessment.score

        // If score is baseline (<30), resolve active incident if one was pending investigation
        if (score < 30) {
            if (currentIncident != null && currentIncident.status == IncidentStatus.INVESTIGATING) {
                val resolved = currentIncident.copy(
                    status = IncidentStatus.RESOLVED,
                    resolvedTimestamp = System.currentTimeMillis()
                )
                incidentDao.updateIncident(IncidentEntity.fromDomain(resolved))
                stateManager.setActiveIncidentId(null)
            }
            return null
        }

        val severity = when (riskAssessment.level) {
            RiskLevel.LOW -> IncidentSeverity.LOW
            RiskLevel.MEDIUM -> IncidentSeverity.MEDIUM
            RiskLevel.HIGH -> if (score >= 80) IncidentSeverity.CRITICAL else IncidentSeverity.HIGH
        }

        val title = when (severity) {
            IncidentSeverity.CRITICAL -> "Critical Security Breach & Displacement"
            IncidentSeverity.HIGH -> "High-Risk Physical or Identity Anomaly"
            IncidentSeverity.MEDIUM -> "Telemetry Anomaly Under Investigation"
            IncidentSeverity.LOW -> "Minor System Signal Divergence"
        }

        if (currentIncident == null) {
            val newIncident = Incident(
                incidentId = "INC-" + UUID.randomUUID().toString().take(8).uppercase(),
                deviceId = deviceId,
                ownerId = ownerId,
                title = title,
                status = IncidentStatus.INVESTIGATING,
                severity = severity,
                riskScore = score,
                triggeringFactors = riskAssessment.reasons,
                createdTimestamp = System.currentTimeMillis()
            )
            incidentDao.insertIncident(IncidentEntity.fromDomain(newIncident))
            stateManager.setActiveIncidentId(newIncident.incidentId)
            return newIncident
        } else {
            val updated = currentIncident.copy(
                severity = severity,
                riskScore = score,
                triggeringFactors = riskAssessment.reasons
            )
            incidentDao.updateIncident(IncidentEntity.fromDomain(updated))
            return updated
        }
    }

    /**
     * Compiles an immutable, cryptographic Theft Report upon owner confirmation.
     */
    suspend fun generateTheftReport(
        activeIncident: Incident?,
        location: LocationEvent?,
        simInfo: SimSecurityInfo,
        batteryPercent: Int
    ): TheftReport {
        val reportId = "REP-THEFT-" + UUID.randomUUID().toString().take(8).uppercase()
        val incId = activeIncident?.incidentId ?: ("INC-" + UUID.randomUUID().toString().take(8).uppercase())

        val lat = location?.latitude ?: 0.0
        val lng = location?.longitude ?: 0.0

        val signatureString = "$reportId:$deviceId:$ownerId:$lat:$lng:${simInfo.carrierName}:$batteryPercent"
        val md = MessageDigest.getInstance("SHA-256")
        val signature = md.digest(signatureString.toByteArray()).joinToString("") { "%02x".format(it) }

        val report = TheftReport(
            reportId = reportId,
            deviceId = deviceId,
            ownerId = ownerId,
            incidentId = incId,
            timestamp = System.currentTimeMillis(),
            lastKnownLatitude = lat,
            lastKnownLongitude = lng,
            carrierName = simInfo.carrierName,
            simState = simInfo.simState,
            batteryPercentage = batteryPercent,
            cryptographicSignature = signature
        )

        theftReportDao.insertTheftReport(TheftReportEntity.fromDomain(report))

        // Update incident to confirmed theft
        if (activeIncident != null) {
            val confirmed = activeIncident.copy(
                status = IncidentStatus.CONFIRMED_THEFT,
                theftReportId = reportId
            )
            incidentDao.updateIncident(IncidentEntity.fromDomain(confirmed))
        }

        return report
    }
}
